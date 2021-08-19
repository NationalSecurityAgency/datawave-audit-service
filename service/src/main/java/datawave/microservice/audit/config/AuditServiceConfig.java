package datawave.microservice.audit.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.stream.annotation.Output;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.MessageChannel;

/**
 * Configuration for the audit service.
 * <p>
 * This configuration is used to specify the binding for the audit producer, and to establish a confirm ack channel which is used to confirm that audit messages
 * have been successfully received by our messaging infrastructure.
 */
@Configuration
@EnableConfigurationProperties(AuditProperties.class)
public class AuditServiceConfig {
    public interface AuditSourceBinding {
        String NAME = "auditSource";
        
        @Output(NAME)
        MessageChannel auditSource();
    }
}
