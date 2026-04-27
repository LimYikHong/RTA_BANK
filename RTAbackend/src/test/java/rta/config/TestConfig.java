package rta.config;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import io.minio.MinioClient;

/**
 * Test configuration that provides mock/stub beans for external services
 * (MinIO, Kafka) so that integration tests can boot the Spring context without
 * real connections.
 */
@TestConfiguration
public class TestConfig {

    @Bean
    @Primary
    public MinioClient minioClient() {
        // Stub MinioClient – never actually called in controller/repo tests
        return MinioClient.builder()
                .endpoint("http://localhost:19090")
                .credentials("test", "test")
                .build();
    }
}
