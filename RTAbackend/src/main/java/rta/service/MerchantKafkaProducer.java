package rta.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import rta.event.MerchantCreatedEvent;

/**
 * Publishes merchant-related events to Kafka so that
 * sub-systems can consume and replicate the data.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MerchantKafkaProducer {

    private final KafkaTemplate<String, MerchantCreatedEvent> kafkaTemplate;

    @Value("${rta.kafka.topic.merchant-created}")
    private String topic;

    /**
     * Publishes a MerchantCreatedEvent keyed by merchantId.
     */
    public void sendMerchantCreatedEvent(MerchantCreatedEvent event) {
        log.info("Publishing merchant-created event: merchantId={}", event.getMerchantId());
        kafkaTemplate.send(topic, event.getMerchantId(), event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish merchant-created event for merchantId={}", event.getMerchantId(), ex);
                    } else {
                        log.info("Merchant-created event published successfully: merchantId={}, offset={}",
                                event.getMerchantId(),
                                result.getRecordMetadata().offset());
                    }
                });
    }
}
