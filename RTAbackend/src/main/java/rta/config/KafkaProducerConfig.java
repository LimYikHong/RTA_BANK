package rta.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaProducerConfig {

    @Value("${rta.kafka.topic.merchant-created}")
    private String merchantCreatedTopic;

    /**
     * Auto-create the topic if it doesn't exist on the broker.
     */
    @Bean
    public NewTopic merchantCreatedTopic() {
        return TopicBuilder.name(merchantCreatedTopic)
                .partitions(3)
                .replicas(1)
                .build();
    }
}
