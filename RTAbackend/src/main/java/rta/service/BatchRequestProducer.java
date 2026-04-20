package rta.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import rta.event.BatchRequestEvent;

import java.util.Base64;

/**
 * Publishes encrypted batch files to the {@code batch-request} Kafka topic for
 * authorization by the (mock) third-party service.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BatchRequestProducer {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Value("${rta.kafka.topic.batch-request}")
    private String batchRequestTopic;

    /**
     * Send an encrypted batch file to the authorization service via Kafka.
     *
     * @param batchId the batch primary key
     * @param merchantId merchant owning the batch
     * @param csvFilename original CSV filename
     * @param transactionCount number of transactions in the batch
     * @param encryptedFileBytes AES-encrypted CSV bytes
     * @param encryptedAesKeyBase64 RSA-encrypted AES key (Base64)
     * @param ivBase64 AES-GCM IV (Base64)
     */
    public void send(Long batchId, String merchantId, String csvFilename,
            int transactionCount, byte[] encryptedFileBytes,
            String encryptedAesKeyBase64, String ivBase64) {

        BatchRequestEvent event = BatchRequestEvent.builder()
                .batchId(batchId)
                .merchantId(merchantId)
                .csvFilename(csvFilename)
                .transactionCount(transactionCount)
                .encryptedFileBase64(Base64.getEncoder().encodeToString(encryptedFileBytes))
                .encryptedAesKeyBase64(encryptedAesKeyBase64)
                .ivBase64(ivBase64)
                .build();

        kafkaTemplate.send(batchRequestTopic, String.valueOf(batchId), event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("[BatchRequestProducer] Failed to send batch {} to Kafka: {}",
                                batchId, ex.getMessage());
                    } else {
                        log.info("[BatchRequestProducer] Batch {} sent to topic '{}', partition={}, offset={}",
                                batchId, batchRequestTopic,
                                result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset());
                    }
                });
    }
}
