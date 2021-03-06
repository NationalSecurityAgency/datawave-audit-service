package datawave.microservice.audit.replay;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.io.Files;
import datawave.microservice.audit.AuditServiceTest;
import datawave.microservice.audit.common.AuditMessage;
import datawave.microservice.audit.config.AuditProperties;
import datawave.microservice.audit.config.AuditServiceConfig;
import datawave.microservice.audit.health.HealthChecker;
import datawave.microservice.audit.replay.config.ReplayProperties;
import datawave.microservice.audit.replay.remote.Request;
import datawave.microservice.audit.replay.status.Status;
import datawave.microservice.authorization.jwt.JWTRestTemplate;
import datawave.microservice.authorization.user.ProxiedUserDetails;
import datawave.security.authorization.DatawaveUser;
import datawave.security.authorization.SubjectIssuerDNPair;
import org.apache.commons.io.FileUtils;
import org.joda.time.format.ISODateTimeFormat;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.cloud.stream.test.binder.MessageCollector;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.Message;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.File;
import java.io.IOException;
import java.sql.Date;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static datawave.security.authorization.DatawaveUser.UserType.USER;
import static datawave.webservice.common.audit.AuditParameters.AUDIT_ID;
import static datawave.webservice.common.audit.AuditParameters.QUERY_AUDIT_TYPE;
import static datawave.webservice.common.audit.AuditParameters.QUERY_AUTHORIZATIONS;
import static datawave.webservice.common.audit.AuditParameters.QUERY_DATE;
import static datawave.webservice.common.audit.AuditParameters.QUERY_LOGIC_CLASS;
import static datawave.webservice.common.audit.AuditParameters.QUERY_SECURITY_MARKING_COLVIZ;
import static datawave.webservice.common.audit.AuditParameters.QUERY_STRING;
import static datawave.webservice.common.audit.AuditParameters.USER_DN;
import static org.junit.Assert.assertNotNull;

@RunWith(SpringRunner.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ContextConfiguration(classes = AuditReplayTest.AuditReplayTestConfiguration.class)
@ActiveProfiles({"AuditReplayTest", "replay-config"})
public class AuditReplayTest {
    
    @LocalServerPort
    private int webServicePort;
    
    @Autowired
    private RestTemplateBuilder restTemplateBuilder;
    
    private JWTRestTemplate jwtRestTemplate;
    
    @Autowired
    private MessageCollector messageCollector;
    
    @Autowired
    private AuditServiceConfig.AuditSourceBinding auditSourceBinding;
    
    @Autowired
    private AuditProperties auditProperties;
    
    @Autowired
    private ReplayProperties replayProperties;
    
    @Autowired
    private ReplayController replayController;
    
    private static File tempDir;
    
    private SubjectIssuerDNPair DN;
    private String userDN = "userDn";
    
    private static Boolean isHealthy = Boolean.TRUE;
    
    @BeforeClass
    public static void classSetup() {
        // create a temp dir for each test
        tempDir = Files.createTempDir();
        tempDir.deleteOnExit();
    }
    
    // Before method clears temp dir, then writes multiple files to temp dir, of varying states
    @Before
    public void setup() throws Exception {
        FileUtils.cleanDirectory(tempDir);
        
        // Copy replay files before each test
        File dataDir = new File("src/test/resources/data");
        for (File file : dataDir.listFiles())
            FileUtils.copyFileToDirectory(file, tempDir);
        
        isHealthy = true;
        jwtRestTemplate = restTemplateBuilder.build(JWTRestTemplate.class);
        DN = SubjectIssuerDNPair.of(userDN, "issuerDn");
    }
    
    // Test unauthorized user
    @Test(expected = HttpClientErrorException.class)
    public void unauthorizedUserTest() {
        Collection<String> roles = Collections.singleton("AuthorizedUser");
        DatawaveUser uathDWUser = new DatawaveUser(DN, USER, null, roles, null, System.currentTimeMillis());
        ProxiedUserDetails authUser = new ProxiedUserDetails(Collections.singleton(uathDWUser), uathDWUser.getCreationTime());
        
        UriComponents uri = UriComponentsBuilder.newInstance().scheme("https").host("localhost").port(webServicePort).path("/audit/v1/replay/createAndStart")
                        .build();
        
        MultiValueMap<String,String> map = new LinkedMultiValueMap<>();
        map.add("pathUri", tempDir.toURI().toString());
        
        RequestEntity requestEntity = jwtRestTemplate.createRequestEntity(authUser, map, null, HttpMethod.POST, uri);
        jwtRestTemplate.exchange(requestEntity, String.class);
    }
    
    // Test create (0 msgs/sec), status, stop, status, update, status, resume, verify status, delete, verify file names, verify messageCollector
    @Test
    @DirtiesContext
    public void singleAuditReplayIgnoreUnfinishedTest() throws Exception {
        replayProperties.setIdleTimeoutMillis(TimeUnit.SECONDS.toMillis(30));
        
        Collection<String> roles = Collections.singleton("Administrator");
        DatawaveUser uathDWUser = new DatawaveUser(DN, USER, null, roles, null, System.currentTimeMillis());
        ProxiedUserDetails authUser = new ProxiedUserDetails(Collections.singleton(uathDWUser), uathDWUser.getCreationTime());
        
        UriComponents createUri = UriComponentsBuilder.newInstance().scheme("https").host("localhost").port(webServicePort).path("/audit/v1/replay/create")
                        .build();
        
        MultiValueMap<String,String> map = new LinkedMultiValueMap<>();
        map.add("pathUri", tempDir.toURI().toString());
        map.add("sendRate", "0");
        
        // Create the audit replay request
        RequestEntity requestEntity = jwtRestTemplate.createRequestEntity(authUser, map, null, HttpMethod.POST, createUri);
        ResponseEntity<String> response = jwtRestTemplate.exchange(requestEntity, String.class);
        String replayId = response.getBody();
        
        // Get the status of the audit replay request
        UriComponents statusUri = UriComponentsBuilder.newInstance().scheme("https").host("localhost").port(webServicePort)
                        .path("/audit/v1/replay/" + replayId + "/status").build();
        Status status = toStatus(jwtRestTemplate.exchange(authUser, HttpMethod.GET, statusUri, String.class));
        
        // @formatter:off
        assertStatus(
                Status.ReplayState.CREATED,
                tempDir.toURI().toString(),
                0L,
                0,
                false,
                status);
        // @formatter:on
        
        // Test stop on a replay that's not running
        UriComponents stopUri = UriComponentsBuilder.newInstance().scheme("https").host("localhost").port(webServicePort)
                        .path("/audit/v1/replay/" + replayId + "/stop").build();
        
        Exception exception = null;
        try {
            jwtRestTemplate.exchange(authUser, HttpMethod.PUT, stopUri, String.class);
        } catch (Exception e) {
            exception = e;
        }
        Assert.assertNotNull(exception);
        Assert.assertTrue(exception instanceof HttpClientErrorException);
        Assert.assertTrue(((HttpClientErrorException) exception).getResponseBodyAsString().contains("Cannot stop audit replay with id " + replayId));
        
        // Start the audit replay request
        UriComponents startUri = UriComponentsBuilder.newInstance().scheme("https").host("localhost").port(webServicePort)
                        .path("/audit/v1/replay/" + replayId + "/start").build();
        ResponseEntity<String> startResp = jwtRestTemplate.exchange(authUser, HttpMethod.PUT, startUri, String.class);
        
        Assert.assertEquals(200, startResp.getStatusCode().value());
        
        // Get the status of the audit replay request
        status = toStatus(jwtRestTemplate.exchange(authUser, HttpMethod.GET, statusUri, String.class));
        
        int numRetries = 20;
        while ((status.getFiles().size() == 0 || status.getFiles().get(0).getState() != Status.FileState.RUNNING) && numRetries-- > 0) {
            Thread.sleep(250);
            status = toStatus(jwtRestTemplate.exchange(authUser, HttpMethod.GET, statusUri, String.class));
        }
        
        // @formatter:off
        assertStatus(
                Status.ReplayState.RUNNING,
                tempDir.toURI().toString(),
                0L,
                2,
                false,
                status);
        // @formatter:on
        
        Status.FileStatus fileStatus = status.getFiles().get(0);
        // @formatter:off
        assertFileStatus(
                Status.FileState.RUNNING,
                "_RUNNING.audit-20190227_000000.000.json",
                0,
                0,
                0,
                0,
                false,
                fileStatus);
        // @formatter:on
        
        fileStatus = status.getFiles().get(1);
        // @formatter:off
        assertFileStatus(
                Status.FileState.QUEUED,
                "_QUEUED.audit-20190228_000000.000.json",
                0,
                0,
                0,
                0,
                false,
                fileStatus);
        // @formatter:on
        
        // Stop the audit replay request
        ResponseEntity<String> stopResp = jwtRestTemplate.exchange(authUser, HttpMethod.PUT, stopUri, String.class);
        
        Assert.assertEquals(200, stopResp.getStatusCode().value());
        
        // Get the status of the audit replay request
        status = toStatus(jwtRestTemplate.exchange(authUser, HttpMethod.GET, statusUri, String.class));
        
        // @formatter:off
        assertStatus(
                Status.ReplayState.STOPPED,
                tempDir.toURI().toString(),
                0L,
                2,
                false,
                status);
        // @formatter:on
        
        fileStatus = status.getFiles().get(0);
        // @formatter:off
        assertFileStatus(
                Status.FileState.RUNNING,
                "_RUNNING.audit-20190227_000000.000.json",
                0,
                0,
                0,
                0,
                false,
                fileStatus);
        // @formatter:on
        
        fileStatus = status.getFiles().get(1);
        // @formatter:off
        assertFileStatus(
                Status.FileState.QUEUED,
                "_QUEUED.audit-20190228_000000.000.json",
                0,
                0,
                0,
                0,
                false,
                fileStatus);
        // @formatter:on
        
        // Update the audit replay request
        UriComponents updateUri = UriComponentsBuilder.newInstance().scheme("https").host("localhost").port(webServicePort)
                        .path("/audit/v1/replay/" + replayId + "/update").build();
        
        map = new LinkedMultiValueMap<>();
        map.add("sendRate", "200");
        
        requestEntity = jwtRestTemplate.createRequestEntity(authUser, map, null, HttpMethod.PUT, updateUri);
        ResponseEntity<String> updateResp = jwtRestTemplate.exchange(requestEntity, String.class);
        
        Assert.assertEquals(200, updateResp.getStatusCode().value());
        
        // Get the status of the audit replay request
        status = toStatus(jwtRestTemplate.exchange(authUser, HttpMethod.GET, statusUri, String.class));
        
        // @formatter:off
        assertStatus(
                Status.ReplayState.STOPPED,
                tempDir.toURI().toString(),
                200L,
                2,
                false,
                status);
        // @formatter:on
        
        fileStatus = status.getFiles().get(0);
        // @formatter:off
        assertFileStatus(
                Status.FileState.RUNNING,
                "_RUNNING.audit-20190227_000000.000.json",
                0,
                0,
                0,
                0,
                false,
                fileStatus);
        // @formatter:on
        
        fileStatus = status.getFiles().get(1);
        // @formatter:off
        assertFileStatus(
                Status.FileState.QUEUED,
                "_QUEUED.audit-20190228_000000.000.json",
                0,
                0,
                0,
                0,
                false,
                fileStatus);
        // @formatter:on
        
        // Resume the audit replay request
        UriComponents resumeUri = UriComponentsBuilder.newInstance().scheme("https").host("localhost").port(webServicePort)
                        .path("/audit/v1/replay/" + replayId + "/resume").build();
        ResponseEntity<String> resumeResp = jwtRestTemplate.exchange(authUser, HttpMethod.PUT, resumeUri, String.class);
        
        Assert.assertEquals(200, resumeResp.getStatusCode().value());
        
        // Check the status until it is not running
        status = toStatus(jwtRestTemplate.exchange(authUser, HttpMethod.GET, statusUri, String.class));
        numRetries = 20;
        while (status.getState() == Status.ReplayState.RUNNING && numRetries-- != 0) {
            Thread.sleep(250);
            status = toStatus(jwtRestTemplate.exchange(authUser, HttpMethod.GET, statusUri, String.class));
        }
        
        // @formatter:off
        assertStatus(
                Status.ReplayState.FINISHED,
                tempDir.toURI().toString(),
                200L,
                2,
                false,
                status);
        // @formatter:on
        
        fileStatus = status.getFiles().get(0);
        // @formatter:off
        assertFileStatus(
                Status.FileState.FINISHED,
                "_FINISHED.audit-20190227_000000.000.json",
                1,
                1,
                0,
                0,
                false,
                fileStatus);
        // @formatter:on
        
        fileStatus = status.getFiles().get(1);
        // @formatter:off
        assertFileStatus(
                Status.FileState.FINISHED,
                "_FINISHED.audit-20190228_000000.000.json",
                1,
                1,
                0,
                0,
                false,
                fileStatus);
        // @formatter:on
        
        // Test resume on a finished audit replay
        exception = null;
        try {
            jwtRestTemplate.exchange(authUser, HttpMethod.PUT, resumeUri, String.class);
        } catch (Exception e) {
            exception = e;
        }
        
        Assert.assertNotNull(exception);
        Assert.assertTrue(exception instanceof HttpClientErrorException);
        Assert.assertTrue(((HttpClientErrorException) exception).getResponseBodyAsString().contains("Cannot resume audit replay with id " + replayId));
        
        // Test start on a finished audit replay
        exception = null;
        try {
            jwtRestTemplate.exchange(authUser, HttpMethod.PUT, startUri, String.class);
        } catch (Exception e) {
            exception = e;
        }
        
        Assert.assertNotNull(exception);
        Assert.assertTrue(exception instanceof HttpClientErrorException);
        Assert.assertTrue(((HttpClientErrorException) exception).getResponseBodyAsString().contains("Cannot start audit replay with state FINISHED"));
        
        // Delete the audit replay request
        UriComponents deleteUri = UriComponentsBuilder.newInstance().scheme("https").host("localhost").port(webServicePort)
                        .path("/audit/v1/replay/" + replayId + "/delete").build();
        ResponseEntity<String> deleteResp = jwtRestTemplate.exchange(authUser, HttpMethod.DELETE, deleteUri, String.class);
        
        Assert.assertEquals(200, deleteResp.getStatusCode().value());
        
        exception = null;
        try {
            jwtRestTemplate.exchange(authUser, HttpMethod.GET, statusUri, String.class);
        } catch (Exception e) {
            exception = e;
        }
        
        Assert.assertNotNull(exception);
        Assert.assertTrue(exception instanceof HttpClientErrorException);
        
        // Verify the message collector has our audit messages
        @SuppressWarnings("unchecked")
        Message<AuditMessage> msg = (Message<AuditMessage>) messageCollector.forChannel(auditSourceBinding.auditSource()).poll();
        assertNotNull(msg);
        Map<String,String> received = msg.getPayload().getAuditParameters();
        
        // @formatter:off
        assertAuditMessage(
                "readyAuditId1",
                "readyUser1",
                "20190227000000",
                "READY",
                "ready query",
                "PASSIVE",
                "EventQuery",
                "READY",
                received);
        // @formatter:on
        
        @SuppressWarnings("unchecked")
        Message<AuditMessage> msg2 = (Message<AuditMessage>) messageCollector.forChannel(auditSourceBinding.auditSource()).poll();
        assertNotNull(msg2);
        received = msg2.getPayload().getAuditParameters();
        
        // @formatter:off
        assertAuditMessage(
                "readyAuditId2",
                "readyUser2",
                "20190228000000",
                "READY",
                "ready query",
                "PASSIVE",
                "EventQuery",
                "READY",
                received);
        // @formatter:on
        
        Assert.assertEquals(0, messageCollector.forChannel(auditSourceBinding.auditSource()).size());
    }
    
    // Test create (0 msgs/sec), status, stop, status, update, status, resume, verify status, delete, verify file names, verify messageCollector
    @Test
    @DirtiesContext
    public void singleAuditReplayCompleteUnfinishedTest() throws Exception {
        replayProperties.setIdleTimeoutMillis(TimeUnit.SECONDS.toMillis(30));
        
        Collection<String> roles = Collections.singleton("Administrator");
        DatawaveUser uathDWUser = new DatawaveUser(DN, USER, null, roles, null, System.currentTimeMillis());
        ProxiedUserDetails authUser = new ProxiedUserDetails(Collections.singleton(uathDWUser), uathDWUser.getCreationTime());
        
        UriComponents createUri = UriComponentsBuilder.newInstance().scheme("https").host("localhost").port(webServicePort).path("/audit/v1/replay/create")
                        .build();
        
        MultiValueMap<String,String> map = new LinkedMultiValueMap<>();
        map.add("pathUri", tempDir.toURI().toString());
        map.add("sendRate", "0");
        map.add("replayUnfinishedFiles", "true");
        
        // Create the audit replay request
        RequestEntity requestEntity = jwtRestTemplate.createRequestEntity(authUser, map, null, HttpMethod.POST, createUri);
        ResponseEntity<String> response = jwtRestTemplate.exchange(requestEntity, String.class);
        String replayId = response.getBody();
        
        // Get the status of the audit replay request
        UriComponents statusUri = UriComponentsBuilder.newInstance().scheme("https").host("localhost").port(webServicePort)
                        .path("/audit/v1/replay/" + replayId + "/status").build();
        Status status = toStatus(jwtRestTemplate.exchange(authUser, HttpMethod.GET, statusUri, String.class));
        
        // @formatter:off
        assertStatus(
                Status.ReplayState.CREATED,
                tempDir.toURI().toString(),
                0L,
                0,
                true,
                status);
        // @formatter:on
        
        // Start the audit replay request
        UriComponents startUri = UriComponentsBuilder.newInstance().scheme("https").host("localhost").port(webServicePort)
                        .path("/audit/v1/replay/" + replayId + "/start").build();
        ResponseEntity<String> startResp = jwtRestTemplate.exchange(authUser, HttpMethod.PUT, startUri, String.class);
        
        Assert.assertEquals(200, startResp.getStatusCode().value());
        
        // Get the status of the audit replay request
        status = toStatus(jwtRestTemplate.exchange(authUser, HttpMethod.GET, statusUri, String.class));
        
        int numRetries = 20;
        while ((status.getFiles().size() == 0 || status.getFiles().get(0).getState() != Status.FileState.RUNNING) && numRetries-- > 0) {
            Thread.sleep(250);
            status = toStatus(jwtRestTemplate.exchange(authUser, HttpMethod.GET, statusUri, String.class));
        }
        
        // @formatter:off
        assertStatus(
                Status.ReplayState.RUNNING,
                tempDir.toURI().toString(),
                0L,
                4,
                true,
                status);
        // @formatter:on
        
        Status.FileStatus fileStatus = status.getFiles().get(0);
        // @formatter:off
        assertFileStatus(
                Status.FileState.RUNNING,
                "_RUNNING.audit-20150602_000000.000.json",
                0,
                0,
                0,
                0,
                false,
                fileStatus);
        // @formatter:on
        
        fileStatus = status.getFiles().get(1);
        // @formatter:off
        assertFileStatus(
                Status.FileState.QUEUED,
                "_QUEUED.audit-20180515_000000.000.json",
                0,
                0,
                0,
                0,
                false,
                fileStatus);
        // @formatter:on
        
        fileStatus = status.getFiles().get(2);
        // @formatter:off
        assertFileStatus(
                Status.FileState.QUEUED,
                "_QUEUED.audit-20190227_000000.000.json",
                0,
                0,
                0,
                0,
                false,
                fileStatus);
        // @formatter:on
        
        fileStatus = status.getFiles().get(3);
        // @formatter:off
        assertFileStatus(
                Status.FileState.QUEUED,
                "_QUEUED.audit-20190228_000000.000.json",
                0,
                0,
                0,
                0,
                false,
                fileStatus);
        // @formatter:on
        
        // Stop the audit replay request
        UriComponents stopUri = UriComponentsBuilder.newInstance().scheme("https").host("localhost").port(webServicePort)
                        .path("/audit/v1/replay/" + replayId + "/stop").build();
        ResponseEntity<String> stopResp = jwtRestTemplate.exchange(authUser, HttpMethod.PUT, stopUri, String.class);
        
        Assert.assertEquals(200, stopResp.getStatusCode().value());
        
        // Get the status of the audit replay request
        status = toStatus(jwtRestTemplate.exchange(authUser, HttpMethod.GET, statusUri, String.class));
        
        // @formatter:off
        assertStatus(
                Status.ReplayState.STOPPED,
                tempDir.toURI().toString(),
                0L,
                4,
                true,
                status);
        // @formatter:on
        
        fileStatus = status.getFiles().get(0);
        // @formatter:off
        assertFileStatus(
                Status.FileState.RUNNING,
                "_RUNNING.audit-20150602_000000.000.json",
                0,
                0,
                0,
                0,
                false,
                fileStatus);
        // @formatter:on
        
        fileStatus = status.getFiles().get(1);
        // @formatter:off
        assertFileStatus(
                Status.FileState.QUEUED,
                "_QUEUED.audit-20180515_000000.000.json",
                0,
                0,
                0,
                0,
                false,
                fileStatus);
        // @formatter:on
        
        fileStatus = status.getFiles().get(2);
        // @formatter:off
        assertFileStatus(
                Status.FileState.QUEUED,
                "_QUEUED.audit-20190227_000000.000.json",
                0,
                0,
                0,
                0,
                false,
                fileStatus);
        // @formatter:on
        
        fileStatus = status.getFiles().get(3);
        // @formatter:off
        assertFileStatus(
                Status.FileState.QUEUED,
                "_QUEUED.audit-20190228_000000.000.json",
                0,
                0,
                0,
                0,
                false,
                fileStatus);
        // @formatter:on
        
        // Update the audit replay request
        UriComponents updateUri = UriComponentsBuilder.newInstance().scheme("https").host("localhost").port(webServicePort)
                        .path("/audit/v1/replay/" + replayId + "/update").build();
        
        map = new LinkedMultiValueMap<>();
        map.add("sendRate", "200");
        
        // Create the audit replay request
        requestEntity = jwtRestTemplate.createRequestEntity(authUser, map, null, HttpMethod.PUT, updateUri);
        ResponseEntity<String> updateResp = jwtRestTemplate.exchange(requestEntity, String.class);
        
        Assert.assertEquals(200, updateResp.getStatusCode().value());
        
        // Get the status of the audit replay request
        status = toStatus(jwtRestTemplate.exchange(authUser, HttpMethod.GET, statusUri, String.class));
        
        // @formatter:off
        assertStatus(
                Status.ReplayState.STOPPED,
                tempDir.toURI().toString(),
                200L,
                4,
                true,
                status);
        // @formatter:on
        
        fileStatus = status.getFiles().get(0);
        // @formatter:off
        assertFileStatus(
                Status.FileState.RUNNING,
                "_RUNNING.audit-20150602_000000.000.json",
                0,
                0,
                0,
                0,
                false,
                fileStatus);
        // @formatter:on
        
        fileStatus = status.getFiles().get(1);
        // @formatter:off
        assertFileStatus(
                Status.FileState.QUEUED,
                "_QUEUED.audit-20180515_000000.000.json",
                0,
                0,
                0,
                0,
                false,
                fileStatus);
        // @formatter:on
        
        fileStatus = status.getFiles().get(2);
        // @formatter:off
        assertFileStatus(
                Status.FileState.QUEUED,
                "_QUEUED.audit-20190227_000000.000.json",
                0,
                0,
                0,
                0,
                false,
                fileStatus);
        // @formatter:on
        
        fileStatus = status.getFiles().get(3);
        // @formatter:off
        assertFileStatus(
                Status.FileState.QUEUED,
                "_QUEUED.audit-20190228_000000.000.json",
                0,
                0,
                0,
                0,
                false,
                fileStatus);
        // @formatter:on
        
        // Resume the audit replay request
        UriComponents resumeUri = UriComponentsBuilder.newInstance().scheme("https").host("localhost").port(webServicePort)
                        .path("/audit/v1/replay/" + replayId + "/resume").build();
        ResponseEntity<String> resumeResp = jwtRestTemplate.exchange(authUser, HttpMethod.PUT, resumeUri, String.class);
        
        Assert.assertEquals(200, resumeResp.getStatusCode().value());
        
        // Check the status until it is not running
        status = toStatus(jwtRestTemplate.exchange(authUser, HttpMethod.GET, statusUri, String.class));
        numRetries = 20;
        while (status.getState() == Status.ReplayState.RUNNING && numRetries-- != 0) {
            Thread.sleep(250);
            status = toStatus(jwtRestTemplate.exchange(authUser, HttpMethod.GET, statusUri, String.class));
        }
        
        // @formatter:off
        assertStatus(
                Status.ReplayState.FINISHED,
                tempDir.toURI().toString(),
                200L,
                4,
                true,
                status);
        // @formatter:on
        
        fileStatus = status.getFiles().get(0);
        // @formatter:off
        assertFileStatus(
                Status.FileState.FINISHED,
                "_FINISHED.audit-20150602_000000.000.json",
                1,
                1,
                0,
                0,
                false,
                fileStatus);
        // @formatter:on
        
        fileStatus = status.getFiles().get(1);
        // @formatter:off
        assertFileStatus(
                Status.FileState.FINISHED,
                "_FINISHED.audit-20180515_000000.000.json",
                1,
                1,
                0,
                0,
                false,
                fileStatus);
        // @formatter:on
        
        fileStatus = status.getFiles().get(2);
        // @formatter:off
        assertFileStatus(
                Status.FileState.FINISHED,
                "_FINISHED.audit-20190227_000000.000.json",
                1,
                1,
                0,
                0,
                false,
                fileStatus);
        // @formatter:on
        
        fileStatus = status.getFiles().get(3);
        // @formatter:off
        assertFileStatus(
                Status.FileState.FINISHED,
                "_FINISHED.audit-20190228_000000.000.json",
                1,
                1,
                0,
                0,
                false,
                fileStatus);
        // @formatter:on
        
        // Delete the audit replay request
        UriComponents deleteUri = UriComponentsBuilder.newInstance().scheme("https").host("localhost").port(webServicePort)
                        .path("/audit/v1/replay/" + replayId + "/delete").build();
        ResponseEntity<String> deleteResp = jwtRestTemplate.exchange(authUser, HttpMethod.DELETE, deleteUri, String.class);
        
        Assert.assertEquals(200, deleteResp.getStatusCode().value());
        
        Exception exception = null;
        try {
            jwtRestTemplate.exchange(authUser, HttpMethod.GET, statusUri, String.class);
            
        } catch (Exception e) {
            exception = e;
        }
        
        Assert.assertNotNull(exception);
        Assert.assertTrue(exception instanceof HttpClientErrorException);
        
        // Verify the message collector has our audit messages
        @SuppressWarnings("unchecked")
        Message<AuditMessage> msg = (Message<AuditMessage>) messageCollector.forChannel(auditSourceBinding.auditSource()).poll();
        assertNotNull(msg);
        Map<String,String> received = msg.getPayload().getAuditParameters();
        
        // @formatter:off
        assertAuditMessage(
                "runningAuditId",
                "runningUser",
                "20150602000000",
                "RUNNING",
                "running query",
                "PASSIVE",
                "EventQuery",
                "RUNNING",
                received);
        // @formatter:on
        
        @SuppressWarnings("unchecked")
        Message<AuditMessage> msg2 = (Message<AuditMessage>) messageCollector.forChannel(auditSourceBinding.auditSource()).poll();
        assertNotNull(msg2);
        received = msg2.getPayload().getAuditParameters();
        
        // @formatter:off
        assertAuditMessage(
                "queuedAuditId",
                "queuedUser",
                "20180515000000",
                "QUEUED",
                "queued query",
                "PASSIVE",
                "EventQuery",
                "QUEUED",
                received);
        // @formatter:on
        
        @SuppressWarnings("unchecked")
        Message<AuditMessage> msg3 = (Message<AuditMessage>) messageCollector.forChannel(auditSourceBinding.auditSource()).poll();
        assertNotNull(msg3);
        received = msg3.getPayload().getAuditParameters();
        
        // @formatter:off
        assertAuditMessage(
                "readyAuditId1",
                "readyUser1",
                "20190227000000",
                "READY",
                "ready query",
                "PASSIVE",
                "EventQuery",
                "READY",
                received);
        // @formatter:on
        
        @SuppressWarnings("unchecked")
        Message<AuditMessage> msg4 = (Message<AuditMessage>) messageCollector.forChannel(auditSourceBinding.auditSource()).poll();
        assertNotNull(msg4);
        received = msg4.getPayload().getAuditParameters();
        
        // @formatter:off
        assertAuditMessage(
                "readyAuditId2",
                "readyUser2",
                "20190228000000",
                "READY",
                "ready query",
                "PASSIVE",
                "EventQuery",
                "READY",
                received);
        // @formatter:on
        
        Assert.assertEquals(0, messageCollector.forChannel(auditSourceBinding.auditSource()).size());
    }
    
    // Test multiple create (0 msgs/sec), stopAll, updateAll, resumeAll, verify statusAll, deleteAll, verify file names, verify messageCollector
    @Test
    @DirtiesContext
    public void multipleAuditReplaysTest() throws Exception {
        replayProperties.setIdleTimeoutMillis(TimeUnit.SECONDS.toMillis(30));
        
        Collection<String> roles = Collections.singleton("Administrator");
        DatawaveUser uathDWUser = new DatawaveUser(DN, USER, null, roles, null, System.currentTimeMillis());
        ProxiedUserDetails authUser = new ProxiedUserDetails(Collections.singleton(uathDWUser), uathDWUser.getCreationTime());
        
        // Create the audit replay requests
        UriComponents createUri1 = UriComponentsBuilder.newInstance().scheme("https").host("localhost").port(webServicePort).path("/audit/v1/replay/create")
                        .build();
        
        MultiValueMap<String,String> map = new LinkedMultiValueMap<>();
        map.add("pathUri", tempDir.toURI().toString() + "/audit-20190227_000000.000.json");
        map.add("sendRate", "0");
        
        // Create the audit replay request
        RequestEntity requestEntity = jwtRestTemplate.createRequestEntity(authUser, map, null, HttpMethod.POST, createUri1);
        ResponseEntity<String> response = jwtRestTemplate.exchange(requestEntity, String.class);
        String replayId1 = response.getBody();
        
        UriComponents createUri2 = UriComponentsBuilder.newInstance().scheme("https").host("localhost").port(webServicePort).path("/audit/v1/replay/create")
                        .build();
        
        map = new LinkedMultiValueMap<>();
        map.add("pathUri", tempDir.toURI().toString() + "/audit-20190228_000000.000.json");
        map.add("sendRate", "0");
        
        // Create the audit replay request
        requestEntity = jwtRestTemplate.createRequestEntity(authUser, map, null, HttpMethod.POST, createUri2);
        response = jwtRestTemplate.exchange(requestEntity, String.class);
        String replayId2 = response.getBody();
        
        // Get the status of the audit replay requests
        UriComponents statusAllUri = UriComponentsBuilder.newInstance().scheme("https").host("localhost").port(webServicePort)
                        .path("/audit/v1/replay/statusAll").build();
        List<Status> statuses = toStatuses(jwtRestTemplate.exchange(authUser, HttpMethod.GET, statusAllUri, String.class));
        
        Status status = statuses.stream().filter(s -> s.getId().equals(replayId1)).findAny().orElse(null);
        Assert.assertNotNull(status);
        // @formatter:off
        assertStatus(
                Status.ReplayState.CREATED,
                tempDir.toURI().toString()+ "/audit-20190227_000000.000.json",
                0L,
                0,
                false,
                status);
        // @formatter:on
        
        status = statuses.stream().filter(s -> s.getId().equals(replayId2)).findAny().orElse(null);
        Assert.assertNotNull(status);
        // @formatter:off
        assertStatus(
                Status.ReplayState.CREATED,
                tempDir.toURI().toString()+ "/audit-20190228_000000.000.json",
                0L,
                0,
                false,
                status);
        // @formatter:on
        
        // Start all of the audit replay requests
        UriComponents startUri = UriComponentsBuilder.newInstance().scheme("https").host("localhost").port(webServicePort).path("/audit/v1/replay/startAll")
                        .build();
        ResponseEntity<String> startResp = jwtRestTemplate.exchange(authUser, HttpMethod.PUT, startUri, String.class);
        
        Assert.assertEquals(200, startResp.getStatusCode().value());
        
        // Get the status of the audit replay requests
        statuses = toStatuses(jwtRestTemplate.exchange(authUser, HttpMethod.GET, statusAllUri, String.class));
        
        int numRetries = 20;
        while (numRetries-- > 0) {
            Thread.sleep(250);
            
            boolean shouldBreak = true;
            for (Status theStatus : statuses)
                shouldBreak &= theStatus.getFiles().size() != 0 && theStatus.getFiles().get(0).getState() == Status.FileState.RUNNING;
            
            if (shouldBreak)
                break;
            
            statuses = toStatuses(jwtRestTemplate.exchange(authUser, HttpMethod.GET, statusAllUri, String.class));
        }
        
        status = statuses.stream().filter(s -> s.getId().equals(replayId1)).findAny().orElse(null);
        Assert.assertNotNull(status);
        // @formatter:off
        assertStatus(
                Status.ReplayState.RUNNING,
                tempDir.toURI().toString() + "/audit-20190227_000000.000.json",
                0L,
                1,
                false,
                status);
        // @formatter:on
        
        Status.FileStatus fileStatus = status.getFiles().get(0);
        // @formatter:off
        assertFileStatus(
                Status.FileState.RUNNING,
                "_RUNNING.audit-20190227_000000.000.json",
                0,
                0,
                0,
                0,
                false,
                fileStatus);
        // @formatter:on
        
        status = statuses.stream().filter(s -> s.getId().equals(replayId2)).findAny().orElse(null);
        Assert.assertNotNull(status);
        // @formatter:off
        assertStatus(
                Status.ReplayState.RUNNING,
                tempDir.toURI().toString() + "/audit-20190228_000000.000.json",
                0L,
                1,
                false,
                status);
        // @formatter:on
        
        fileStatus = status.getFiles().get(0);
        // @formatter:off
        assertFileStatus(
                Status.FileState.RUNNING,
                "_RUNNING.audit-20190228_000000.000.json",
                0,
                0,
                0,
                0,
                false,
                fileStatus);
        // @formatter:on
        
        // Stop the audit replay request
        UriComponents stopAllUri = UriComponentsBuilder.newInstance().scheme("https").host("localhost").port(webServicePort).path("/audit/v1/replay/stopAll")
                        .build();
        ResponseEntity<String> stopResp = jwtRestTemplate.exchange(authUser, HttpMethod.PUT, stopAllUri, String.class);
        
        Assert.assertEquals(200, stopResp.getStatusCode().value());
        
        // Get the status of the audit replay request
        statuses = toStatuses(jwtRestTemplate.exchange(authUser, HttpMethod.GET, statusAllUri, String.class));
        
        numRetries = 20;
        while (numRetries-- > 0) {
            Thread.sleep(250);
            
            boolean shouldBreak = true;
            for (Status theStatus : statuses)
                shouldBreak &= theStatus.getFiles().size() != 0 && theStatus.getFiles().get(0).getState() == Status.FileState.RUNNING;
            
            if (shouldBreak)
                break;
            
            statuses = toStatuses(jwtRestTemplate.exchange(authUser, HttpMethod.GET, statusAllUri, String.class));
        }
        
        status = statuses.stream().filter(s -> s.getId().equals(replayId1)).findAny().orElse(null);
        Assert.assertNotNull(status);
        // @formatter:off
        assertStatus(
                Status.ReplayState.STOPPED,
                tempDir.toURI().toString() + "/audit-20190227_000000.000.json",
                0L,
                1,
                false,
                status);
        // @formatter:on
        
        fileStatus = status.getFiles().get(0);
        // @formatter:off
        assertFileStatus(
                Status.FileState.RUNNING,
                "_RUNNING.audit-20190227_000000.000.json",
                0,
                0,
                0,
                0,
                false,
                fileStatus);
        // @formatter:on
        
        status = statuses.stream().filter(s -> s.getId().equals(replayId2)).findAny().orElse(null);
        Assert.assertNotNull(status);
        // @formatter:off
        assertStatus(
                Status.ReplayState.STOPPED,
                tempDir.toURI().toString() + "/audit-20190228_000000.000.json",
                0L,
                1,
                false,
                status);
        // @formatter:on
        
        fileStatus = status.getFiles().get(0);
        // @formatter:off
        assertFileStatus(
                Status.FileState.RUNNING,
                "_RUNNING.audit-20190228_000000.000.json",
                0,
                0,
                0,
                0,
                false,
                fileStatus);
        // @formatter:on
        
        // Update the audit replay request
        UriComponents updateAllUri = UriComponentsBuilder.newInstance().scheme("https").host("localhost").port(webServicePort)
                        .path("/audit/v1/replay/updateAll").build();
        
        map = new LinkedMultiValueMap<>();
        map.add("sendRate", "200");
        
        // Create the audit replay request
        requestEntity = jwtRestTemplate.createRequestEntity(authUser, map, null, HttpMethod.PUT, updateAllUri);
        ResponseEntity<String> updateResp = jwtRestTemplate.exchange(requestEntity, String.class);
        
        Assert.assertEquals(200, updateResp.getStatusCode().value());
        
        // Get the status of the audit replay request
        statuses = toStatuses(jwtRestTemplate.exchange(authUser, HttpMethod.GET, statusAllUri, String.class));
        
        status = statuses.stream().filter(s -> s.getId().equals(replayId1)).findAny().orElse(null);
        Assert.assertNotNull(status);
        // @formatter:off
        assertStatus(
                Status.ReplayState.STOPPED,
                tempDir.toURI().toString() + "/audit-20190227_000000.000.json",
                200L,
                1,
                false,
                status);
        // @formatter:on
        
        fileStatus = status.getFiles().get(0);
        // @formatter:off
        assertFileStatus(
                Status.FileState.RUNNING,
                "_RUNNING.audit-20190227_000000.000.json",
                0,
                0,
                0,
                0,
                false,
                fileStatus);
        // @formatter:on
        
        status = statuses.stream().filter(s -> s.getId().equals(replayId2)).findAny().orElse(null);
        Assert.assertNotNull(status);
        // @formatter:off
        assertStatus(
                Status.ReplayState.STOPPED,
                tempDir.toURI().toString() + "/audit-20190228_000000.000.json",
                200L,
                1,
                false,
                status);
        // @formatter:on
        
        fileStatus = status.getFiles().get(0);
        // @formatter:off
        assertFileStatus(
                Status.FileState.RUNNING,
                "_RUNNING.audit-20190228_000000.000.json",
                0,
                0,
                0,
                0,
                false,
                fileStatus);
        // @formatter:on
        
        // Resume the audit replay request
        UriComponents resumeAllUri = UriComponentsBuilder.newInstance().scheme("https").host("localhost").port(webServicePort)
                        .path("/audit/v1/replay/resumeAll").build();
        ResponseEntity<String> resumeResp = jwtRestTemplate.exchange(authUser, HttpMethod.PUT, resumeAllUri, String.class);
        
        Assert.assertEquals(200, resumeResp.getStatusCode().value());
        
        // Check the statuses until they are all finished running
        statuses = toStatuses(jwtRestTemplate.exchange(authUser, HttpMethod.GET, statusAllUri, String.class));
        numRetries = 20;
        while (numRetries-- > 0) {
            Thread.sleep(250);
            
            boolean shouldBreak = true;
            for (Status theStatus : statuses)
                shouldBreak &= theStatus.getState() == Status.ReplayState.FINISHED;
            
            if (shouldBreak)
                break;
            
            statuses = toStatuses(jwtRestTemplate.exchange(authUser, HttpMethod.GET, statusAllUri, String.class));
        }
        
        status = statuses.stream().filter(s -> s.getId().equals(replayId1)).findAny().orElse(null);
        Assert.assertNotNull(status);
        // @formatter:off
        assertStatus(
                Status.ReplayState.FINISHED,
                tempDir.toURI().toString() + "/audit-20190227_000000.000.json",
                200L,
                1,
                false,
                status);
        // @formatter:on
        
        fileStatus = status.getFiles().get(0);
        // @formatter:off
        assertFileStatus(
                Status.FileState.FINISHED,
                "_FINISHED.audit-20190227_000000.000.json",
                1,
                1,
                0,
                0,
                false,
                fileStatus);
        // @formatter:on
        
        status = statuses.stream().filter(s -> s.getId().equals(replayId2)).findAny().orElse(null);
        Assert.assertNotNull(status);
        // @formatter:off
        assertStatus(
                Status.ReplayState.FINISHED,
                tempDir.toURI().toString() + "/audit-20190228_000000.000.json",
                200L,
                1,
                false,
                status);
        // @formatter:on
        
        fileStatus = status.getFiles().get(0);
        // @formatter:off
        assertFileStatus(
                Status.FileState.FINISHED,
                "_FINISHED.audit-20190228_000000.000.json",
                1,
                1,
                0,
                0,
                false,
                fileStatus);
        // @formatter:on
        
        // Delete the audit replay requests
        UriComponents deleteUri = UriComponentsBuilder.newInstance().scheme("https").host("localhost").port(webServicePort).path("/audit/v1/replay/deleteAll")
                        .build();
        ResponseEntity<String> deleteResp = jwtRestTemplate.exchange(authUser, HttpMethod.DELETE, deleteUri, String.class);
        
        Assert.assertEquals(200, deleteResp.getStatusCode().value());
        
        statuses = toStatuses(jwtRestTemplate.exchange(authUser, HttpMethod.GET, statusAllUri, String.class));
        
        Assert.assertNotNull(statuses);
        Assert.assertEquals(0, statuses.size());
        
        List<Map<String,String>> receivedMessages = new ArrayList<>();
        
        // Verify the message collector has our audit messages
        @SuppressWarnings("unchecked")
        Message<AuditMessage> msg = (Message<AuditMessage>) messageCollector.forChannel(auditSourceBinding.auditSource()).poll();
        assertNotNull(msg);
        receivedMessages.add(msg.getPayload().getAuditParameters());
        
        @SuppressWarnings("unchecked")
        Message<AuditMessage> msg2 = (Message<AuditMessage>) messageCollector.forChannel(auditSourceBinding.auditSource()).poll();
        assertNotNull(msg2);
        receivedMessages.add(msg2.getPayload().getAuditParameters());
        
        Map<String,String> received = receivedMessages.stream().filter(r -> r.get(AUDIT_ID).equals("readyAuditId1")).findAny().orElse(null);
        Assert.assertNotNull(received);
        // @formatter:off
        assertAuditMessage(
                "readyAuditId1",
                "readyUser1",
                "20190227000000",
                "READY",
                "ready query",
                "PASSIVE",
                "EventQuery",
                "READY",
                received);
        // @formatter:on
        
        received = receivedMessages.stream().filter(r -> r.get(AUDIT_ID).equals("readyAuditId2")).findAny().orElse(null);
        Assert.assertNotNull(received);
        // @formatter:off
        assertAuditMessage(
                "readyAuditId2",
                "readyUser2",
                "20190228000000",
                "READY",
                "ready query",
                "PASSIVE",
                "EventQuery",
                "READY",
                received);
        // @formatter:on
        
        Assert.assertEquals(0, messageCollector.forChannel(auditSourceBinding.auditSource()).size());
    }
    
    // Test createAndStart, verify status, idleCheck, resume, verify audit messages
    @Test
    public void createAndStartIdleCheckTest() throws Exception {
        Collection<String> roles = Collections.singleton("Administrator");
        DatawaveUser uathDWUser = new DatawaveUser(DN, USER, null, roles, null, System.currentTimeMillis());
        ProxiedUserDetails authUser = new ProxiedUserDetails(Collections.singleton(uathDWUser), uathDWUser.getCreationTime());
        
        UriComponents createAndStartUri = UriComponentsBuilder.newInstance().scheme("https").host("localhost").port(webServicePort)
                        .path("/audit/v1/replay/createAndStart").build();
        
        MultiValueMap<String,String> map = new LinkedMultiValueMap<>();
        map.add("pathUri", tempDir.toURI().toString());
        map.add("sendRate", "0");
        
        // Create and start the audit replay request
        RequestEntity requestEntity = jwtRestTemplate.createRequestEntity(authUser, map, null, HttpMethod.POST, createAndStartUri);
        ResponseEntity<String> response = jwtRestTemplate.exchange(requestEntity, String.class);
        
        String replayId = response.getBody();
        
        // Get the status of the audit replay request
        UriComponents statusUri = UriComponentsBuilder.newInstance().scheme("https").host("localhost").port(webServicePort)
                        .path("/audit/v1/replay/" + replayId + "/status").build();
        Status status = toStatus(jwtRestTemplate.exchange(authUser, HttpMethod.GET, statusUri, String.class));
        
        int numRetries = 20;
        while ((status.getFiles().size() == 0 || status.getFiles().get(0).getState() != Status.FileState.RUNNING) && numRetries-- > 0) {
            Thread.sleep(125);
            status = toStatus(jwtRestTemplate.exchange(authUser, HttpMethod.GET, statusUri, String.class));
        }
        
        // @formatter:off
        assertStatus(
                Status.ReplayState.RUNNING,
                tempDir.toURI().toString(),
                0L,
                2,
                false,
                status);
        // @formatter:on
        
        Status.FileStatus fileStatus = status.getFiles().get(0);
        // @formatter:off
        assertFileStatus(
                Status.FileState.RUNNING,
                "_RUNNING.audit-20190227_000000.000.json",
                0,
                0,
                0,
                0,
                false,
                fileStatus);
        // @formatter:on
        
        fileStatus = status.getFiles().get(1);
        // @formatter:off
        assertFileStatus(
                Status.FileState.QUEUED,
                "_QUEUED.audit-20190228_000000.000.json",
                0,
                0,
                0,
                0,
                false,
                fileStatus);
        // @formatter:on
        
        // Check the status until it is stopped
        status = toStatus(jwtRestTemplate.exchange(authUser, HttpMethod.GET, statusUri, String.class));
        numRetries = 20;
        while (status.getState() != Status.ReplayState.STOPPED && numRetries-- != 0) {
            Thread.sleep(250);
            status = toStatus(jwtRestTemplate.exchange(authUser, HttpMethod.GET, statusUri, String.class));
        }
        
        // @formatter:off
        assertStatus(
                Status.ReplayState.STOPPED,
                tempDir.toURI().toString(),
                0L,
                2,
                false,
                status);
        // @formatter:on
        
        // Update the audit replay request
        UriComponents updateUri = UriComponentsBuilder.newInstance().scheme("https").host("localhost").port(webServicePort)
                        .path("/audit/v1/replay/" + replayId + "/update").build();
        
        map = new LinkedMultiValueMap<>();
        map.add("sendRate", "200");
        
        // Update replay request
        requestEntity = jwtRestTemplate.createRequestEntity(authUser, map, null, HttpMethod.PUT, updateUri);
        ResponseEntity<String> updateResp = jwtRestTemplate.exchange(requestEntity, String.class);
        
        Assert.assertEquals(200, updateResp.getStatusCode().value());
        
        // Get the status of the audit replay request
        status = toStatus(jwtRestTemplate.exchange(authUser, HttpMethod.GET, statusUri, String.class));
        
        // @formatter:off
        assertStatus(
                Status.ReplayState.STOPPED,
                tempDir.toURI().toString(),
                200L,
                2,
                false,
                status);
        // @formatter:on
        
        fileStatus = status.getFiles().get(0);
        // @formatter:off
        assertFileStatus(
                Status.FileState.RUNNING,
                "_RUNNING.audit-20190227_000000.000.json",
                0,
                0,
                0,
                0,
                false,
                fileStatus);
        // @formatter:on
        
        fileStatus = status.getFiles().get(1);
        // @formatter:off
        assertFileStatus(
                Status.FileState.QUEUED,
                "_QUEUED.audit-20190228_000000.000.json",
                0,
                0,
                0,
                0,
                false,
                fileStatus);
        // @formatter:on
        
        // Resume the audit replay request
        UriComponents resumeUri = UriComponentsBuilder.newInstance().scheme("https").host("localhost").port(webServicePort)
                        .path("/audit/v1/replay/" + replayId + "/resume").build();
        ResponseEntity<String> resumeResp = jwtRestTemplate.exchange(authUser, HttpMethod.PUT, resumeUri, String.class);
        
        Assert.assertEquals(200, resumeResp.getStatusCode().value());
        
        // Check the status until it is not running
        status = toStatus(jwtRestTemplate.exchange(authUser, HttpMethod.GET, statusUri, String.class));
        numRetries = 20;
        while (status.getState() == Status.ReplayState.RUNNING && numRetries-- != 0) {
            Thread.sleep(250);
            status = toStatus(jwtRestTemplate.exchange(authUser, HttpMethod.GET, statusUri, String.class));
        }
        
        // @formatter:off
        assertStatus(
                Status.ReplayState.FINISHED,
                tempDir.toURI().toString(),
                200L,
                2,
                false,
                status);
        // @formatter:on
        
        fileStatus = status.getFiles().get(0);
        // @formatter:off
        assertFileStatus(
                Status.FileState.FINISHED,
                "_FINISHED.audit-20190227_000000.000.json",
                1,
                1,
                0,
                0,
                false,
                fileStatus);
        // @formatter:on
        
        fileStatus = status.getFiles().get(1);
        // @formatter:off
        assertFileStatus(
                Status.FileState.FINISHED,
                "_FINISHED.audit-20190228_000000.000.json",
                1,
                1,
                0,
                0,
                false,
                fileStatus);
        // @formatter:on
        
        // Delete the audit replay request
        UriComponents deleteUri = UriComponentsBuilder.newInstance().scheme("https").host("localhost").port(webServicePort)
                        .path("/audit/v1/replay/" + replayId + "/delete").build();
        ResponseEntity<String> deleteResp = jwtRestTemplate.exchange(authUser, HttpMethod.DELETE, deleteUri, String.class);
        
        Assert.assertEquals(200, deleteResp.getStatusCode().value());
        
        Exception exception = null;
        try {
            jwtRestTemplate.exchange(authUser, HttpMethod.GET, statusUri, String.class);
            
        } catch (Exception e) {
            exception = e;
        }
        
        Assert.assertNotNull(exception);
        Assert.assertTrue(exception instanceof HttpClientErrorException);
        
        // Verify the message collector has our audit messages
        @SuppressWarnings("unchecked")
        Message<AuditMessage> msg = (Message<AuditMessage>) messageCollector.forChannel(auditSourceBinding.auditSource()).poll();
        assertNotNull(msg);
        Map<String,String> received = msg.getPayload().getAuditParameters();
        
        // @formatter:off
        assertAuditMessage(
                "readyAuditId1",
                "readyUser1",
                "20190227000000",
                "READY",
                "ready query",
                "PASSIVE",
                "EventQuery",
                "READY",
                received);
        // @formatter:on
        
        @SuppressWarnings("unchecked")
        Message<AuditMessage> msg2 = (Message<AuditMessage>) messageCollector.forChannel(auditSourceBinding.auditSource()).poll();
        assertNotNull(msg2);
        received = msg2.getPayload().getAuditParameters();
        
        // @formatter:off
        assertAuditMessage(
                "readyAuditId2",
                "readyUser2",
                "20190228000000",
                "READY",
                "ready query",
                "PASSIVE",
                "EventQuery",
                "READY",
                received);
        // @formatter:on
        
        Assert.assertEquals(0, messageCollector.forChannel(auditSourceBinding.auditSource()).size());
    }
    
    // Test delete on running replay
    @Test
    @DirtiesContext
    public void deleteRunningReplayTest() throws Exception {
        replayProperties.setIdleTimeoutMillis(TimeUnit.SECONDS.toMillis(30));
        
        Collection<String> roles = Collections.singleton("Administrator");
        DatawaveUser uathDWUser = new DatawaveUser(DN, USER, null, roles, null, System.currentTimeMillis());
        ProxiedUserDetails authUser = new ProxiedUserDetails(Collections.singleton(uathDWUser), uathDWUser.getCreationTime());
        
        UriComponents createAndStartUri = UriComponentsBuilder.newInstance().scheme("https").host("localhost").port(webServicePort)
                        .path("/audit/v1/replay/createAndStart").build();
        
        MultiValueMap<String,String> map = new LinkedMultiValueMap<>();
        map.add("pathUri", tempDir.toURI().toString());
        map.add("sendRate", "0");
        
        // Create and start the audit replay request
        RequestEntity requestEntity = jwtRestTemplate.createRequestEntity(authUser, map, null, HttpMethod.POST, createAndStartUri);
        ResponseEntity<String> response = jwtRestTemplate.exchange(requestEntity, String.class);
        String replayId = response.getBody();
        
        // Get the status of the audit replay request
        UriComponents statusUri = UriComponentsBuilder.newInstance().scheme("https").host("localhost").port(webServicePort)
                        .path("/audit/v1/replay/" + replayId + "/status").build();
        Status status = toStatus(jwtRestTemplate.exchange(authUser, HttpMethod.GET, statusUri, String.class));
        
        int numRetries = 20;
        while ((status.getFiles().size() == 0 || status.getFiles().get(0).getState() != Status.FileState.RUNNING) && numRetries-- > 0) {
            Thread.sleep(250);
            status = toStatus(jwtRestTemplate.exchange(authUser, HttpMethod.GET, statusUri, String.class));
        }
        
        // @formatter:off
        assertStatus(
                Status.ReplayState.RUNNING,
                tempDir.toURI().toString(),
                0L,
                2,
                false,
                status);
        // @formatter:on
        
        Status.FileStatus fileStatus = status.getFiles().get(0);
        // @formatter:off
        assertFileStatus(
                Status.FileState.RUNNING,
                "_RUNNING.audit-20190227_000000.000.json",
                0,
                0,
                0,
                0,
                false,
                fileStatus);
        // @formatter:on
        
        fileStatus = status.getFiles().get(1);
        // @formatter:off
        assertFileStatus(
                Status.FileState.QUEUED,
                "_QUEUED.audit-20190228_000000.000.json",
                0,
                0,
                0,
                0,
                false,
                fileStatus);
        // @formatter:on
        
        // Attempt to delete the running replay
        UriComponents deleteUri = UriComponentsBuilder.newInstance().scheme("https").host("localhost").port(webServicePort)
                        .path("/audit/v1/replay/" + replayId + "/delete").build();
        
        Exception exception = null;
        try {
            jwtRestTemplate.exchange(authUser, HttpMethod.DELETE, deleteUri, String.class);
        } catch (Exception e) {
            exception = e;
        }
        
        Assert.assertNotNull(exception);
        Assert.assertTrue(exception instanceof HttpClientErrorException);
    }
    
    @Test
    public void actionsOnBogusReplayIdTest() {
        Collection<String> roles = Collections.singleton("Administrator");
        DatawaveUser uathDWUser = new DatawaveUser(DN, USER, null, roles, null, System.currentTimeMillis());
        ProxiedUserDetails authUser = new ProxiedUserDetails(Collections.singleton(uathDWUser), uathDWUser.getCreationTime());
        
        // status
        UriComponents statusUri = UriComponentsBuilder.newInstance().scheme("https").host("localhost").port(webServicePort)
                        .path("/audit/v1/replay/bogusId/status").build();
        
        Exception exception = null;
        try {
            jwtRestTemplate.exchange(authUser, HttpMethod.GET, statusUri, String.class);
        } catch (Exception e) {
            exception = e;
        }
        Assert.assertNotNull(exception);
        Assert.assertTrue(exception instanceof HttpClientErrorException);
        Assert.assertTrue(((HttpClientErrorException) exception).getResponseBodyAsString().contains("No audit replay found with id bogusId"));
        
        // update
        UriComponents updateUri = UriComponentsBuilder.newInstance().scheme("https").host("localhost").port(webServicePort)
                        .path("/audit/v1/replay/bogusId/update").build();
        
        MultiValueMap<String,String> map = new LinkedMultiValueMap<>();
        map.add("sendRate", "200");
        
        // Create and start the audit replay request
        RequestEntity requestEntity = jwtRestTemplate.createRequestEntity(authUser, map, null, HttpMethod.PUT, updateUri);
        
        exception = null;
        try {
            jwtRestTemplate.exchange(requestEntity, String.class);
        } catch (Exception e) {
            exception = e;
        }
        Assert.assertNotNull(exception);
        Assert.assertTrue(exception instanceof HttpClientErrorException);
        Assert.assertTrue(((HttpClientErrorException) exception).getResponseBodyAsString().contains("No audit replay found with id bogusId"));
        
        // start
        UriComponents startUri = UriComponentsBuilder.newInstance().scheme("https").host("localhost").port(webServicePort)
                        .path("/audit/v1/replay/bogusId/start").build();
        exception = null;
        try {
            jwtRestTemplate.exchange(authUser, HttpMethod.PUT, startUri, String.class);
        } catch (Exception e) {
            exception = e;
        }
        Assert.assertNotNull(exception);
        Assert.assertTrue(exception instanceof HttpClientErrorException);
        Assert.assertTrue(((HttpClientErrorException) exception).getResponseBodyAsString().contains("No audit replay found with id bogusId"));
        
        // stop
        UriComponents stopUri = UriComponentsBuilder.newInstance().scheme("https").host("localhost").port(webServicePort).path("/audit/v1/replay/bogusId/stop")
                        .build();
        exception = null;
        try {
            jwtRestTemplate.exchange(authUser, HttpMethod.PUT, startUri, String.class);
        } catch (Exception e) {
            exception = e;
        }
        Assert.assertNotNull(exception);
        Assert.assertTrue(exception instanceof HttpClientErrorException);
        Assert.assertTrue(((HttpClientErrorException) exception).getResponseBodyAsString().contains("No audit replay found with id bogusId"));
        
        // resume
        UriComponents resumeUri = UriComponentsBuilder.newInstance().scheme("https").host("localhost").port(webServicePort)
                        .path("/audit/v1/replay/bogusId/resume").build();
        exception = null;
        try {
            jwtRestTemplate.exchange(authUser, HttpMethod.PUT, resumeUri, String.class);
        } catch (Exception e) {
            exception = e;
        }
        Assert.assertNotNull(exception);
        Assert.assertTrue(exception instanceof HttpClientErrorException);
        Assert.assertTrue(((HttpClientErrorException) exception).getResponseBodyAsString().contains("No audit replay found with id bogusId"));
        
        // delete
        UriComponents deleteUri = UriComponentsBuilder.newInstance().scheme("https").host("localhost").port(webServicePort)
                        .path("/audit/v1/replay/bogusId/delete").build();
        exception = null;
        try {
            jwtRestTemplate.exchange(authUser, HttpMethod.DELETE, deleteUri, String.class);
        } catch (Exception e) {
            exception = e;
        }
        Assert.assertNotNull(exception);
        Assert.assertTrue(exception instanceof HttpClientErrorException);
        Assert.assertTrue(((HttpClientErrorException) exception).getResponseBodyAsString().contains("No audit replay found with id bogusId"));
        
    }
    
    @Test
    @DirtiesContext
    public void updateRunningReplayTest() throws Exception {
        replayProperties.setIdleTimeoutMillis(TimeUnit.SECONDS.toMillis(30));
        
        Collection<String> roles = Collections.singleton("Administrator");
        DatawaveUser uathDWUser = new DatawaveUser(DN, USER, null, roles, null, System.currentTimeMillis());
        ProxiedUserDetails authUser = new ProxiedUserDetails(Collections.singleton(uathDWUser), uathDWUser.getCreationTime());
        
        UriComponents createUri = UriComponentsBuilder.newInstance().scheme("https").host("localhost").port(webServicePort).path("/audit/v1/replay/create")
                        .build();
        
        MultiValueMap<String,String> map = new LinkedMultiValueMap<>();
        map.add("pathUri", tempDir.toURI().toString());
        map.add("sendRate", "0");
        
        // Create the audit replay request
        RequestEntity requestEntity = jwtRestTemplate.createRequestEntity(authUser, map, null, HttpMethod.POST, createUri);
        ResponseEntity<String> response = jwtRestTemplate.exchange(requestEntity, String.class);
        String replayId = response.getBody();
        
        // Get the status of the audit replay request
        UriComponents statusUri = UriComponentsBuilder.newInstance().scheme("https").host("localhost").port(webServicePort)
                        .path("/audit/v1/replay/" + replayId + "/status").build();
        Status status = toStatus(jwtRestTemplate.exchange(authUser, HttpMethod.GET, statusUri, String.class));
        
        // @formatter:off
        assertStatus(
                Status.ReplayState.CREATED,
                tempDir.toURI().toString(),
                0L,
                0,
                false,
                status);
        // @formatter:on
        
        // Start the audit replay request
        UriComponents startUri = UriComponentsBuilder.newInstance().scheme("https").host("localhost").port(webServicePort)
                        .path("/audit/v1/replay/" + replayId + "/start").build();
        ResponseEntity<String> startResp = jwtRestTemplate.exchange(authUser, HttpMethod.PUT, startUri, String.class);
        
        Assert.assertEquals(200, startResp.getStatusCode().value());
        
        // Get the status of the audit replay request
        status = toStatus(jwtRestTemplate.exchange(authUser, HttpMethod.GET, statusUri, String.class));
        
        int numRetries = 20;
        while ((status.getFiles().size() == 0 || status.getFiles().get(0).getState() != Status.FileState.RUNNING) && numRetries-- > 0) {
            Thread.sleep(250);
            status = toStatus(jwtRestTemplate.exchange(authUser, HttpMethod.GET, statusUri, String.class));
        }
        
        // @formatter:off
        assertStatus(
                Status.ReplayState.RUNNING,
                tempDir.toURI().toString(),
                0L,
                2,
                false,
                status);
        // @formatter:on
        
        Status.FileStatus fileStatus = status.getFiles().get(0);
        // @formatter:off
        assertFileStatus(
                Status.FileState.RUNNING,
                "_RUNNING.audit-20190227_000000.000.json",
                0,
                0,
                0,
                0,
                false,
                fileStatus);
        // @formatter:on
        
        fileStatus = status.getFiles().get(1);
        // @formatter:off
        assertFileStatus(
                Status.FileState.QUEUED,
                "_QUEUED.audit-20190228_000000.000.json",
                0,
                0,
                0,
                0,
                false,
                fileStatus);
        // @formatter:on
        
        // Update the audit replay request
        UriComponents updateUri = UriComponentsBuilder.newInstance().scheme("https").host("localhost").port(webServicePort).path("/audit/v1/replay/updateAll")
                        .build();
        
        map = new LinkedMultiValueMap<>();
        map.add("sendRate", "200");
        
        // Create the audit replay request
        requestEntity = jwtRestTemplate.createRequestEntity(authUser, map, null, HttpMethod.PUT, updateUri);
        ResponseEntity<String> updateResp = jwtRestTemplate.exchange(requestEntity, String.class);
        
        Assert.assertEquals(200, updateResp.getStatusCode().value());
        
        // Check the status until it is not running
        status = toStatus(jwtRestTemplate.exchange(authUser, HttpMethod.GET, statusUri, String.class));
        numRetries = 20;
        while (status.getState() == Status.ReplayState.RUNNING && numRetries-- != 0) {
            Thread.sleep(250);
            status = toStatus(jwtRestTemplate.exchange(authUser, HttpMethod.GET, statusUri, String.class));
        }
        
        // @formatter:off
        assertStatus(
                Status.ReplayState.FINISHED,
                tempDir.toURI().toString(),
                200L,
                2,
                false,
                status);
        // @formatter:on
        
        fileStatus = status.getFiles().get(0);
        // @formatter:off
        assertFileStatus(
                Status.FileState.FINISHED,
                "_FINISHED.audit-20190227_000000.000.json",
                1,
                1,
                0,
                0,
                false,
                fileStatus);
        // @formatter:on
        
        fileStatus = status.getFiles().get(1);
        // @formatter:off
        assertFileStatus(
                Status.FileState.FINISHED,
                "_FINISHED.audit-20190228_000000.000.json",
                1,
                1,
                0,
                0,
                false,
                fileStatus);
        // @formatter:on
        
        // Delete the audit replay request
        UriComponents deleteUri = UriComponentsBuilder.newInstance().scheme("https").host("localhost").port(webServicePort)
                        .path("/audit/v1/replay/" + replayId + "/delete").build();
        ResponseEntity<String> deleteResp = jwtRestTemplate.exchange(authUser, HttpMethod.DELETE, deleteUri, String.class);
        
        Assert.assertEquals(200, deleteResp.getStatusCode().value());
        
        Exception exception = null;
        try {
            jwtRestTemplate.exchange(authUser, HttpMethod.GET, statusUri, String.class);
        } catch (Exception e) {
            exception = e;
        }
        
        Assert.assertNotNull(exception);
        Assert.assertTrue(exception instanceof HttpClientErrorException);
        
        // Verify the message collector has our audit messages
        @SuppressWarnings("unchecked")
        Message<AuditMessage> msg = (Message<AuditMessage>) messageCollector.forChannel(auditSourceBinding.auditSource()).poll();
        assertNotNull(msg);
        Map<String,String> received = msg.getPayload().getAuditParameters();
        
        // @formatter:off
        assertAuditMessage(
                "readyAuditId1",
                "readyUser1",
                "20190227000000",
                "READY",
                "ready query",
                "PASSIVE",
                "EventQuery",
                "READY",
                received);
        // @formatter:on
        
        @SuppressWarnings("unchecked")
        Message<AuditMessage> msg2 = (Message<AuditMessage>) messageCollector.forChannel(auditSourceBinding.auditSource()).poll();
        assertNotNull(msg2);
        received = msg2.getPayload().getAuditParameters();
        
        // @formatter:off
        assertAuditMessage(
                "readyAuditId2",
                "readyUser2",
                "20190228000000",
                "READY",
                "ready query",
                "PASSIVE",
                "EventQuery",
                "READY",
                received);
        // @formatter:on
        
        Assert.assertEquals(0, messageCollector.forChannel(auditSourceBinding.auditSource()).size());
    }
    
    @Test
    public void bogusSendRateTest() {
        Collection<String> roles = Collections.singleton("Administrator");
        DatawaveUser uathDWUser = new DatawaveUser(DN, USER, null, roles, null, System.currentTimeMillis());
        ProxiedUserDetails authUser = new ProxiedUserDetails(Collections.singleton(uathDWUser), uathDWUser.getCreationTime());
        
        // create
        UriComponents createUri = UriComponentsBuilder.newInstance().scheme("https").host("localhost").port(webServicePort).path("/audit/v1/replay/create")
                        .build();
        
        MultiValueMap<String,String> map = new LinkedMultiValueMap<>();
        map.add("pathUri", tempDir.toURI().toString());
        map.add("sendRate", "-1");
        
        // Create the audit replay request
        RequestEntity requestEntity = jwtRestTemplate.createRequestEntity(authUser, map, null, HttpMethod.POST, createUri);
        
        Exception exception = null;
        try {
            jwtRestTemplate.exchange(requestEntity, String.class);
        } catch (Exception e) {
            exception = e;
        }
        
        Assert.assertNotNull(exception);
        Assert.assertTrue(exception instanceof HttpClientErrorException);
        Assert.assertTrue(((HttpClientErrorException) exception).getResponseBodyAsString().contains("Send rate must be >= 0"));
        
        // createAndStart
        UriComponents createAndStartUri = UriComponentsBuilder.newInstance().scheme("https").host("localhost").port(webServicePort)
                        .path("/audit/v1/replay/createAndStart").build();
        
        map = new LinkedMultiValueMap<>();
        map.add("pathUri", tempDir.toURI().toString());
        map.add("sendRate", "-1");
        
        // Create the audit replay request
        requestEntity = jwtRestTemplate.createRequestEntity(authUser, map, null, HttpMethod.POST, createAndStartUri);
        
        exception = null;
        try {
            jwtRestTemplate.exchange(requestEntity, String.class);
        } catch (Exception e) {
            exception = e;
        }
        
        Assert.assertNotNull(exception);
        Assert.assertTrue(exception instanceof HttpClientErrorException);
        Assert.assertTrue(((HttpClientErrorException) exception).getResponseBodyAsString().contains("Send rate must be >= 0"));
        
        // update
        UriComponents updateUri = UriComponentsBuilder.newInstance().scheme("https").host("localhost").port(webServicePort)
                        .path("/audit/v1/replay/bogusId/update").build();
        
        map = new LinkedMultiValueMap<>();
        map.add("sendRate", "-1");
        
        // Create the audit replay request
        requestEntity = jwtRestTemplate.createRequestEntity(authUser, map, null, HttpMethod.PUT, updateUri);
        
        exception = null;
        try {
            jwtRestTemplate.exchange(requestEntity, String.class);
        } catch (Exception e) {
            exception = e;
        }
        
        Assert.assertNotNull(exception);
        Assert.assertTrue(exception instanceof HttpClientErrorException);
        Assert.assertTrue(((HttpClientErrorException) exception).getResponseBodyAsString().contains("Send rate must be >= 0"));
        
        // updateAll
        UriComponents updateAllUri = UriComponentsBuilder.newInstance().scheme("https").host("localhost").port(webServicePort)
                        .path("/audit/v1/replay/updateAll").build();
        
        map = new LinkedMultiValueMap<>();
        map.add("sendRate", "-1");
        
        // Create the audit replay request
        requestEntity = jwtRestTemplate.createRequestEntity(authUser, map, null, HttpMethod.PUT, updateAllUri);
        
        exception = null;
        try {
            jwtRestTemplate.exchange(requestEntity, String.class);
        } catch (Exception e) {
            exception = e;
        }
        
        Assert.assertNotNull(exception);
        Assert.assertTrue(exception instanceof HttpClientErrorException);
        Assert.assertTrue(((HttpClientErrorException) exception).getResponseBodyAsString().contains("Send rate must be >= 0"));
        
    }
    
    @Test
    public void parseFailureTest() throws Exception {
        replayProperties.setIdleTimeoutMillis(TimeUnit.SECONDS.toMillis(30));
        
        FileUtils.moveFile(new File(tempDir, "_FAILED.audit-20080601_000000.000.json"), new File(tempDir, "audit-20080601_000000.000.json"));
        
        Collection<String> roles = Collections.singleton("Administrator");
        DatawaveUser uathDWUser = new DatawaveUser(DN, USER, null, roles, null, System.currentTimeMillis());
        ProxiedUserDetails authUser = new ProxiedUserDetails(Collections.singleton(uathDWUser), uathDWUser.getCreationTime());
        
        UriComponents createAndStartUri = UriComponentsBuilder.newInstance().scheme("https").host("localhost").port(webServicePort)
                        .path("/audit/v1/replay/createAndStart").build();
        
        MultiValueMap<String,String> map = new LinkedMultiValueMap<>();
        map.add("pathUri", tempDir.toURI().toString() + "/audit-20080601_000000.000.json");
        map.add("sendRate", "200");
        
        // Create the audit replay request
        RequestEntity requestEntity = jwtRestTemplate.createRequestEntity(authUser, map, null, HttpMethod.POST, createAndStartUri);
        ResponseEntity<String> response = jwtRestTemplate.exchange(requestEntity, String.class);
        String replayId = response.getBody();
        
        // Get the status of the audit replay request
        UriComponents statusUri = UriComponentsBuilder.newInstance().scheme("https").host("localhost").port(webServicePort)
                        .path("/audit/v1/replay/" + replayId + "/status").build();
        Status status = toStatus(jwtRestTemplate.exchange(authUser, HttpMethod.GET, statusUri, String.class));
        
        int numRetries = 20;
        while (status.getState() == Status.ReplayState.RUNNING && numRetries-- > 0) {
            Thread.sleep(250);
            status = toStatus(jwtRestTemplate.exchange(authUser, HttpMethod.GET, statusUri, String.class));
        }
        
        // @formatter:off
        assertStatus(
                Status.ReplayState.FINISHED,
                tempDir.toURI().toString() + "/audit-20080601_000000.000.json",
                200L,
                1,
                false,
                status);
        // @formatter:on
        
        Status.FileStatus fileStatus = status.getFiles().get(0);
        // @formatter:off
        assertFileStatus(
                Status.FileState.FAILED,
                "_FAILED.audit-20080601_000000.000.json",
                1,
                0,
                0,
                1,
                false,
                fileStatus);
        // @formatter:on
        
        // Delete the audit replay request
        UriComponents deleteUri = UriComponentsBuilder.newInstance().scheme("https").host("localhost").port(webServicePort)
                        .path("/audit/v1/replay/" + replayId + "/delete").build();
        ResponseEntity<String> deleteResp = jwtRestTemplate.exchange(authUser, HttpMethod.DELETE, deleteUri, String.class);
        
        Assert.assertEquals(200, deleteResp.getStatusCode().value());
        
        Exception exception = null;
        try {
            jwtRestTemplate.exchange(authUser, HttpMethod.GET, statusUri, String.class);
        } catch (Exception e) {
            exception = e;
        }
        
        Assert.assertNotNull(exception);
        Assert.assertTrue(exception instanceof HttpClientErrorException);
        
        // Verify the message collector has no audit messages
        Assert.assertEquals(0, messageCollector.forChannel(auditSourceBinding.auditSource()).size());
    }
    
    @Test
    public void auditFailureTest() throws Exception {
        replayProperties.setIdleTimeoutMillis(TimeUnit.SECONDS.toMillis(30));
        isHealthy = false;
        
        Collection<String> roles = Collections.singleton("Administrator");
        DatawaveUser uathDWUser = new DatawaveUser(DN, USER, null, roles, null, System.currentTimeMillis());
        ProxiedUserDetails authUser = new ProxiedUserDetails(Collections.singleton(uathDWUser), uathDWUser.getCreationTime());
        
        UriComponents createAndStartUri = UriComponentsBuilder.newInstance().scheme("https").host("localhost").port(webServicePort)
                        .path("/audit/v1/replay/createAndStart").build();
        
        MultiValueMap<String,String> map = new LinkedMultiValueMap<>();
        map.add("pathUri", tempDir.toURI().toString() + "/audit-20190227_000000.000.json");
        map.add("sendRate", "200");
        
        // Create the audit replay request
        RequestEntity requestEntity = jwtRestTemplate.createRequestEntity(authUser, map, null, HttpMethod.POST, createAndStartUri);
        ResponseEntity<String> response = jwtRestTemplate.exchange(requestEntity, String.class);
        String replayId = response.getBody();
        
        // Get the status of the audit replay request
        UriComponents statusUri = UriComponentsBuilder.newInstance().scheme("https").host("localhost").port(webServicePort)
                        .path("/audit/v1/replay/" + replayId + "/status").build();
        Status status = toStatus(jwtRestTemplate.exchange(authUser, HttpMethod.GET, statusUri, String.class));
        
        int numRetries = 20;
        while (status.getState() == Status.ReplayState.RUNNING && numRetries-- > 0) {
            Thread.sleep(250);
            status = toStatus(jwtRestTemplate.exchange(authUser, HttpMethod.GET, statusUri, String.class));
        }
        
        // @formatter:off
        assertStatus(
                Status.ReplayState.FINISHED,
                tempDir.toURI().toString() + "/audit-20190227_000000.000.json",
                200L,
                1,
                false,
                status);
        // @formatter:on
        
        Status.FileStatus fileStatus = status.getFiles().get(0);
        // @formatter:off
        assertFileStatus(
                Status.FileState.FAILED,
                "_FAILED.audit-20190227_000000.000.json",
                1,
                1,
                1,
                0,
                false,
                fileStatus);
        // @formatter:on
        
        // Delete the audit replay request
        UriComponents deleteUri = UriComponentsBuilder.newInstance().scheme("https").host("localhost").port(webServicePort)
                        .path("/audit/v1/replay/" + replayId + "/delete").build();
        ResponseEntity<String> deleteResp = jwtRestTemplate.exchange(authUser, HttpMethod.DELETE, deleteUri, String.class);
        
        Assert.assertEquals(200, deleteResp.getStatusCode().value());
        
        Exception exception = null;
        try {
            jwtRestTemplate.exchange(authUser, HttpMethod.GET, statusUri, String.class);
        } catch (Exception e) {
            exception = e;
        }
        
        Assert.assertNotNull(exception);
        Assert.assertTrue(exception instanceof HttpClientErrorException);
        
        // Verify the message collector has no audit messages
        Assert.assertEquals(0, messageCollector.forChannel(auditSourceBinding.auditSource()).size());
    }
    
    @Test
    @DirtiesContext
    public void remoteUpdateStopTest() throws Exception {
        Collection<String> roles = Collections.singleton("Administrator");
        DatawaveUser uathDWUser = new DatawaveUser(DN, USER, null, roles, null, System.currentTimeMillis());
        ProxiedUserDetails authUser = new ProxiedUserDetails(Collections.singleton(uathDWUser), uathDWUser.getCreationTime());
        
        UriComponents createUri = UriComponentsBuilder.newInstance().scheme("https").host("localhost").port(webServicePort).path("/audit/v1/replay/create")
                        .build();
        
        MultiValueMap<String,String> map = new LinkedMultiValueMap<>();
        map.add("pathUri", tempDir.toURI().toString());
        map.add("sendRate", "200");
        
        // Create the audit replay request
        RequestEntity requestEntity = jwtRestTemplate.createRequestEntity(authUser, map, null, HttpMethod.POST, createUri);
        ResponseEntity<String> response = jwtRestTemplate.exchange(requestEntity, String.class);
        String replayId = response.getBody();
        
        // Get the status of the audit replay request
        UriComponents statusUri = UriComponentsBuilder.newInstance().scheme("https").host("localhost").port(webServicePort)
                        .path("/audit/v1/replay/" + replayId + "/status").build();
        Status status = toStatus(jwtRestTemplate.exchange(authUser, HttpMethod.GET, statusUri, String.class));
        
        // @formatter:off
        assertStatus(
                Status.ReplayState.CREATED,
                tempDir.toURI().toString(),
                200L,
                0,
                false,
                status);
        // @formatter:on
        
        // send remote update request
        replayController.handleRemoteRequest(Request.update(replayId, 0));
        
        // Get the status of the audit replay request
        statusUri = UriComponentsBuilder.newInstance().scheme("https").host("localhost").port(webServicePort).path("/audit/v1/replay/" + replayId + "/status")
                        .build();
        status = toStatus(jwtRestTemplate.exchange(authUser, HttpMethod.GET, statusUri, String.class));
        
        // @formatter:off
        assertStatus(
                Status.ReplayState.CREATED,
                tempDir.toURI().toString(),
                0L,
                0,
                false,
                status);
        // @formatter:on
        
        // Start the audit replay request
        UriComponents startUri = UriComponentsBuilder.newInstance().scheme("https").host("localhost").port(webServicePort)
                        .path("/audit/v1/replay/" + replayId + "/start").build();
        ResponseEntity<String> startResp = jwtRestTemplate.exchange(authUser, HttpMethod.PUT, startUri, String.class);
        
        Assert.assertEquals(200, startResp.getStatusCode().value());
        
        // Get the status of the audit replay request
        status = toStatus(jwtRestTemplate.exchange(authUser, HttpMethod.GET, statusUri, String.class));
        
        int numRetries = 20;
        while ((status.getFiles().size() == 0 || status.getFiles().get(0).getState() != Status.FileState.RUNNING) && numRetries-- > 0) {
            Thread.sleep(250);
            status = toStatus(jwtRestTemplate.exchange(authUser, HttpMethod.GET, statusUri, String.class));
        }
        
        // @formatter:off
        assertStatus(
                Status.ReplayState.RUNNING,
                tempDir.toURI().toString(),
                0L,
                2,
                false,
                status);
        // @formatter:on
        
        Status.FileStatus fileStatus = status.getFiles().get(0);
        // @formatter:off
        assertFileStatus(
                Status.FileState.RUNNING,
                "_RUNNING.audit-20190227_000000.000.json",
                0,
                0,
                0,
                0,
                false,
                fileStatus);
        // @formatter:on
        
        fileStatus = status.getFiles().get(1);
        // @formatter:off
        assertFileStatus(
                Status.FileState.QUEUED,
                "_QUEUED.audit-20190228_000000.000.json",
                0,
                0,
                0,
                0,
                false,
                fileStatus);
        // @formatter:on
        
        // send remote stop request
        replayController.handleRemoteRequest(Request.stop(replayId));
        
        // Get the status of the audit replay request
        status = toStatus(jwtRestTemplate.exchange(authUser, HttpMethod.GET, statusUri, String.class));
        
        // @formatter:off
        assertStatus(
                Status.ReplayState.STOPPED,
                tempDir.toURI().toString(),
                0L,
                2,
                false,
                status);
        // @formatter:on
    }
    
    @Test
    @DirtiesContext
    public void remoteUpdateAllTest() throws Exception {
        replayProperties.setIdleTimeoutMillis(TimeUnit.SECONDS.toMillis(30));
        
        Collection<String> roles = Collections.singleton("Administrator");
        DatawaveUser uathDWUser = new DatawaveUser(DN, USER, null, roles, null, System.currentTimeMillis());
        ProxiedUserDetails authUser = new ProxiedUserDetails(Collections.singleton(uathDWUser), uathDWUser.getCreationTime());
        
        // Create the audit replay requests
        UriComponents createUri1 = UriComponentsBuilder.newInstance().scheme("https").host("localhost").port(webServicePort).path("/audit/v1/replay/create")
                        .build();
        
        MultiValueMap<String,String> map = new LinkedMultiValueMap<>();
        map.add("pathUri", tempDir.toURI().toString() + "/audit-20190227_000000.000.json");
        map.add("sendRate", "0");
        
        // Create the audit replay request
        RequestEntity requestEntity = jwtRestTemplate.createRequestEntity(authUser, map, null, HttpMethod.POST, createUri1);
        ResponseEntity<String> response = jwtRestTemplate.exchange(requestEntity, String.class);
        String replayId1 = response.getBody();
        
        UriComponents createUri2 = UriComponentsBuilder.newInstance().scheme("https").host("localhost").port(webServicePort).path("/audit/v1/replay/create")
                        .build();
        
        map = new LinkedMultiValueMap<>();
        map.add("pathUri", tempDir.toURI().toString() + "/audit-20190228_000000.000.json");
        map.add("sendRate", "0");
        
        // Create the audit replay request
        requestEntity = jwtRestTemplate.createRequestEntity(authUser, map, null, HttpMethod.POST, createUri2);
        response = jwtRestTemplate.exchange(requestEntity, String.class);
        String replayId2 = response.getBody();
        
        // Get the status of the audit replay requests
        UriComponents statusAllUri = UriComponentsBuilder.newInstance().scheme("https").host("localhost").port(webServicePort)
                        .path("/audit/v1/replay/statusAll").build();
        List<Status> statuses = toStatuses(jwtRestTemplate.exchange(authUser, HttpMethod.GET, statusAllUri, String.class));
        
        Status status = statuses.stream().filter(s -> s.getId().equals(replayId1)).findAny().orElse(null);
        Assert.assertNotNull(status);
        // @formatter:off
        assertStatus(
                Status.ReplayState.CREATED,
                tempDir.toURI().toString()+ "/audit-20190227_000000.000.json",
                0L,
                0,
                false,
                status);
        // @formatter:on
        
        status = statuses.stream().filter(s -> s.getId().equals(replayId2)).findAny().orElse(null);
        Assert.assertNotNull(status);
        // @formatter:off
        assertStatus(
                Status.ReplayState.CREATED,
                tempDir.toURI().toString()+ "/audit-20190228_000000.000.json",
                0L,
                0,
                false,
                status);
        // @formatter:on
        
        // Start all of the audit replay requests
        UriComponents startUri = UriComponentsBuilder.newInstance().scheme("https").host("localhost").port(webServicePort).path("/audit/v1/replay/startAll")
                        .build();
        ResponseEntity<String> startResp = jwtRestTemplate.exchange(authUser, HttpMethod.PUT, startUri, String.class);
        
        Assert.assertEquals(200, startResp.getStatusCode().value());
        
        // Get the status of the audit replay requests
        statuses = toStatuses(jwtRestTemplate.exchange(authUser, HttpMethod.GET, statusAllUri, String.class));
        
        int numRetries = 20;
        while (numRetries-- > 0) {
            Thread.sleep(250);
            
            boolean shouldBreak = true;
            for (Status theStatus : statuses)
                shouldBreak &= theStatus.getFiles().size() != 0 && theStatus.getFiles().get(0).getState() == Status.FileState.RUNNING;
            
            if (shouldBreak)
                break;
            
            statuses = toStatuses(jwtRestTemplate.exchange(authUser, HttpMethod.GET, statusAllUri, String.class));
        }
        
        status = statuses.stream().filter(s -> s.getId().equals(replayId1)).findAny().orElse(null);
        Assert.assertNotNull(status);
        // @formatter:off
        assertStatus(
                Status.ReplayState.RUNNING,
                tempDir.toURI().toString() + "/audit-20190227_000000.000.json",
                0L,
                1,
                false,
                status);
        // @formatter:on
        
        Status.FileStatus fileStatus = status.getFiles().get(0);
        // @formatter:off
        assertFileStatus(
                Status.FileState.RUNNING,
                "_RUNNING.audit-20190227_000000.000.json",
                0,
                0,
                0,
                0,
                false,
                fileStatus);
        // @formatter:on
        
        status = statuses.stream().filter(s -> s.getId().equals(replayId2)).findAny().orElse(null);
        Assert.assertNotNull(status);
        // @formatter:off
        assertStatus(
                Status.ReplayState.RUNNING,
                tempDir.toURI().toString() + "/audit-20190228_000000.000.json",
                0L,
                1,
                false,
                status);
        // @formatter:on
        
        fileStatus = status.getFiles().get(0);
        // @formatter:off
        assertFileStatus(
                Status.FileState.RUNNING,
                "_RUNNING.audit-20190228_000000.000.json",
                0,
                0,
                0,
                0,
                false,
                fileStatus);
        // @formatter:on
        
        // send remote updateAll request
        replayController.handleRemoteRequest(Request.updateAll(200));
        
        // Check the statuses until they are all finished running
        statuses = toStatuses(jwtRestTemplate.exchange(authUser, HttpMethod.GET, statusAllUri, String.class));
        numRetries = 20;
        while (numRetries-- > 0) {
            Thread.sleep(250);
            
            boolean shouldBreak = true;
            for (Status theStatus : statuses)
                shouldBreak &= theStatus.getState() == Status.ReplayState.FINISHED;
            
            if (shouldBreak)
                break;
            
            statuses = toStatuses(jwtRestTemplate.exchange(authUser, HttpMethod.GET, statusAllUri, String.class));
        }
        
        status = statuses.stream().filter(s -> s.getId().equals(replayId1)).findAny().orElse(null);
        Assert.assertNotNull(status);
        // @formatter:off
        assertStatus(
                Status.ReplayState.FINISHED,
                tempDir.toURI().toString() + "/audit-20190227_000000.000.json",
                200L,
                1,
                false,
                status);
        // @formatter:on
        
        fileStatus = status.getFiles().get(0);
        // @formatter:off
        assertFileStatus(
                Status.FileState.FINISHED,
                "_FINISHED.audit-20190227_000000.000.json",
                1,
                1,
                0,
                0,
                false,
                fileStatus);
        // @formatter:on
        
        status = statuses.stream().filter(s -> s.getId().equals(replayId2)).findAny().orElse(null);
        Assert.assertNotNull(status);
        // @formatter:off
        assertStatus(
                Status.ReplayState.FINISHED,
                tempDir.toURI().toString() + "/audit-20190228_000000.000.json",
                200L,
                1,
                false,
                status);
        // @formatter:on
        
        fileStatus = status.getFiles().get(0);
        // @formatter:off
        assertFileStatus(
                Status.FileState.FINISHED,
                "_FINISHED.audit-20190228_000000.000.json",
                1,
                1,
                0,
                0,
                false,
                fileStatus);
        // @formatter:on
    }
    
    @Test
    @DirtiesContext
    public void remoteStopAllTest() throws Exception {
        replayProperties.setIdleTimeoutMillis(TimeUnit.SECONDS.toMillis(30));
        
        Collection<String> roles = Collections.singleton("Administrator");
        DatawaveUser uathDWUser = new DatawaveUser(DN, USER, null, roles, null, System.currentTimeMillis());
        ProxiedUserDetails authUser = new ProxiedUserDetails(Collections.singleton(uathDWUser), uathDWUser.getCreationTime());
        
        // Create the audit replay requests
        UriComponents createAndStartUri1 = UriComponentsBuilder.newInstance().scheme("https").host("localhost").port(webServicePort)
                        .path("/audit/v1/replay/createAndStart").build();
        
        MultiValueMap<String,String> map = new LinkedMultiValueMap<>();
        map.add("pathUri", tempDir.toURI().toString() + "/audit-20190227_000000.000.json");
        map.add("sendRate", "0");
        
        // Create the audit replay request
        RequestEntity requestEntity = jwtRestTemplate.createRequestEntity(authUser, map, null, HttpMethod.POST, createAndStartUri1);
        ResponseEntity<String> response = jwtRestTemplate.exchange(requestEntity, String.class);
        String replayId1 = response.getBody();
        
        UriComponents createAndStartUri2 = UriComponentsBuilder.newInstance().scheme("https").host("localhost").port(webServicePort)
                        .path("/audit/v1/replay/createAndStart").build();
        
        map = new LinkedMultiValueMap<>();
        map.add("pathUri", tempDir.toURI().toString() + "/audit-20190228_000000.000.json");
        map.add("sendRate", "0");
        
        // Create the audit replay request
        requestEntity = jwtRestTemplate.createRequestEntity(authUser, map, null, HttpMethod.POST, createAndStartUri2);
        response = jwtRestTemplate.exchange(requestEntity, String.class);
        String replayId2 = response.getBody();
        
        // Get the status of the audit replay requests
        UriComponents statusAllUri = UriComponentsBuilder.newInstance().scheme("https").host("localhost").port(webServicePort)
                        .path("/audit/v1/replay/statusAll").build();
        List<Status> statuses = toStatuses(jwtRestTemplate.exchange(authUser, HttpMethod.GET, statusAllUri, String.class));
        
        int numRetries = 20;
        while (numRetries-- > 0) {
            Thread.sleep(250);
            
            boolean shouldBreak = true;
            for (Status theStatus : statuses)
                shouldBreak &= theStatus.getFiles().size() != 0 && theStatus.getFiles().get(0).getState() == Status.FileState.RUNNING;
            
            if (shouldBreak)
                break;
            
            statuses = toStatuses(jwtRestTemplate.exchange(authUser, HttpMethod.GET, statusAllUri, String.class));
        }
        
        Status status = statuses.stream().filter(s -> s.getId().equals(replayId1)).findAny().orElse(null);
        Assert.assertNotNull(status);
        // @formatter:off
        assertStatus(
                Status.ReplayState.RUNNING,
                tempDir.toURI().toString()+ "/audit-20190227_000000.000.json",
                0L,
                1,
                false,
                status);
        // @formatter:on
        
        Status.FileStatus fileStatus = status.getFiles().get(0);
        // @formatter:off
        assertFileStatus(
                Status.FileState.RUNNING,
                "_RUNNING.audit-20190227_000000.000.json",
                0,
                0,
                0,
                0,
                false,
                fileStatus);
        // @formatter:on
        
        status = statuses.stream().filter(s -> s.getId().equals(replayId2)).findAny().orElse(null);
        Assert.assertNotNull(status);
        // @formatter:off
        assertStatus(
                Status.ReplayState.RUNNING,
                tempDir.toURI().toString()+ "/audit-20190228_000000.000.json",
                0L,
                1,
                false,
                status);
        // @formatter:on
        
        fileStatus = status.getFiles().get(0);
        // @formatter:off
        assertFileStatus(
                Status.FileState.RUNNING,
                "_RUNNING.audit-20190228_000000.000.json",
                0,
                0,
                0,
                0,
                false,
                fileStatus);
        // @formatter:on
        
        // send remote stopAll request
        replayController.handleRemoteRequest(Request.stopAll());
        
        // Check the statuses until they are all finished running
        statuses = toStatuses(jwtRestTemplate.exchange(authUser, HttpMethod.GET, statusAllUri, String.class));
        
        status = statuses.stream().filter(s -> s.getId().equals(replayId1)).findAny().orElse(null);
        Assert.assertNotNull(status);
        // @formatter:off
        assertStatus(
                Status.ReplayState.STOPPED,
                tempDir.toURI().toString() + "/audit-20190227_000000.000.json",
                0,
                1,
                false,
                status);
        // @formatter:on
        
        fileStatus = status.getFiles().get(0);
        // @formatter:off
        assertFileStatus(
                Status.FileState.RUNNING,
                "_RUNNING.audit-20190227_000000.000.json",
                0,
                0,
                0,
                0,
                false,
                fileStatus);
        // @formatter:on
        
        status = statuses.stream().filter(s -> s.getId().equals(replayId2)).findAny().orElse(null);
        Assert.assertNotNull(status);
        // @formatter:off
        assertStatus(
                Status.ReplayState.STOPPED,
                tempDir.toURI().toString() + "/audit-20190228_000000.000.json",
                0,
                1,
                false,
                status);
        // @formatter:on
        
        fileStatus = status.getFiles().get(0);
        // @formatter:off
        assertFileStatus(
                Status.FileState.RUNNING,
                "_RUNNING.audit-20190228_000000.000.json",
                0,
                0,
                0,
                0,
                false,
                fileStatus);
        // @formatter:on
    }
    
    private static void assertStatus(Status.ReplayState expectedState, String expectedPath, long expectedSendRate, int expectedNumFiles,
                    boolean expectedReplayUnfinishedFiles, Status actual) {
        Assert.assertEquals(expectedState, actual.getState());
        Assert.assertEquals(expectedPath, actual.getPathUri());
        Assert.assertEquals(expectedSendRate, actual.getSendRate());
        Assert.assertEquals(expectedNumFiles, actual.getFiles().size());
        Assert.assertEquals(expectedReplayUnfinishedFiles, actual.isReplayUnfinishedFiles());
    }
    
    private static void assertFileStatus(Status.FileState expectedState, String expectedFilename, long expectedLinesRead, long expectedAuditsSent,
                    long expectedAuditsFailed, long expectedParseFailures, boolean expectedEncounteredError, Status.FileStatus actual) {
        Assert.assertEquals(expectedState, actual.getState());
        Assert.assertTrue(actual.getPathUri().endsWith(expectedFilename));
        Assert.assertEquals(expectedLinesRead, actual.getLinesRead());
        Assert.assertEquals(expectedAuditsSent, actual.getAuditsSent());
        Assert.assertEquals(expectedAuditsFailed, actual.getAuditsFailed());
        Assert.assertEquals(expectedParseFailures, actual.getParseFailures());
        Assert.assertEquals(expectedEncounteredError, actual.isEncounteredError());
    }
    
    private static void assertAuditMessage(String expectedAuditId, String expectedUserDN, String expectedQueryDate, String expectedAuths, String expectedQuery,
                    String expectedAuditType, String expectedQueryLogic, String expectedColViz, Map<String,String> actual) {
        Assert.assertEquals(expectedAuditId, actual.get(AUDIT_ID));
        Assert.assertEquals(expectedUserDN, actual.get(USER_DN));
        Assert.assertEquals(expectedQueryDate, actual.get(QUERY_DATE));
        Assert.assertEquals(expectedAuths, actual.get(QUERY_AUTHORIZATIONS));
        Assert.assertEquals(expectedQuery, actual.get(QUERY_STRING));
        Assert.assertEquals(expectedAuditType, actual.get(QUERY_AUDIT_TYPE));
        Assert.assertEquals(expectedQueryLogic, actual.get(QUERY_LOGIC_CLASS));
        Assert.assertEquals(expectedColViz, actual.get(QUERY_SECURITY_MARKING_COLVIZ));
    }
    
    private static List<Status> toStatuses(ResponseEntity<String> responseEntity) throws IOException {
        return toStatuses(responseEntity.getBody());
    }
    
    private static List<Status> toStatuses(String stringStatus) throws IOException {
        List<Status> statuses = new ArrayList<>();
        Map<String,Object>[] mapArray = objectMapper.readValue(stringStatus, new TypeReference<HashMap<String,Object>[]>() {});
        for (Map<String,Object> map : mapArray) {
            statuses.add(mapToStatus(map));
        }
        return statuses;
    }
    
    private static Status toStatus(ResponseEntity<String> responseEntity) throws IOException {
        return toStatus(responseEntity.getBody());
    }
    
    private static ObjectMapper objectMapper = new ObjectMapper();
    
    private static Status toStatus(String stringStatus) throws IOException {
        Map<String,Object> map = objectMapper.readValue(stringStatus, new TypeReference<HashMap<String,Object>>() {});
        return mapToStatus(map);
    }
    
    private static Status mapToStatus(Map<String,Object> map) {
        Status status = new Status();
        status.setId((String) map.get("id"));
        status.setState(Status.ReplayState.valueOf((String) map.get("state")));
        status.setPathUri((String) map.get("pathUri"));
        status.setSendRate(Integer.toUnsignedLong((int) map.get("sendRate")));
        status.setLastUpdated(ISODateTimeFormat.dateTime().parseDateTime((String) map.get("lastUpdated")).toDate());
        status.setReplayUnfinishedFiles((boolean) map.get("replayUnfinishedFiles"));
        
        if (map.get("files") != null) {
            List<Status.FileStatus> fileStatuses = new ArrayList<>();
            for (Map<String,Object> file : (List<Map<String,Object>>) map.get("files")) {
                Status.FileState state = Status.FileState.valueOf((String) file.get("state"));
                String path = (String) file.get("pathUri");
                
                Status.FileStatus fileStatus = new Status.FileStatus(path, state);
                fileStatus.setLinesRead((int) file.get("linesRead"));
                fileStatus.setAuditsSent((int) file.get("auditsSent"));
                fileStatus.setAuditsFailed((int) file.get("auditsFailed"));
                fileStatus.setParseFailures((int) file.get("parseFailures"));
                fileStatus.setEncounteredError((boolean) file.get("encounteredError"));
                
                fileStatuses.add(fileStatus);
            }
            status.setFiles(fileStatuses);
        }
        
        status.getFiles().sort((o1, o2) -> {
            if (o1.getState() == o2.getState())
                return o1.getPathUri().compareTo(o2.getPathUri());
            else
                return o2.getState().ordinal() - o1.getState().ordinal();
        });
        
        return status;
    }
    
    @Configuration
    @Profile("AuditReplayTest")
    @ComponentScan(basePackages = "datawave.microservice")
    public static class AuditReplayTestConfiguration {
        @Bean
        public HealthChecker healthChecker() {
            return new AuditServiceTest.TestHealthChecker() {
                @Override
                public boolean isHealthy() {
                    return isHealthy;
                }
            };
        }
    }
}
