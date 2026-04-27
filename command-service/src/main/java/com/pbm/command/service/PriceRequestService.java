package com.pbm.command.service;

import com.pbm.command.dto.event.PriceRequestEvent;
import com.pbm.command.dto.event.PriceRequestEventPayload;
import com.pbm.command.dto.request.PriceCheckRequest;
import com.pbm.command.dto.response.PriceCheckResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;


/**
 * 가격 확인 요청을 Kafka price-topic으로 발행하는 서비스.
 *
 * 역할: API 요청을 Kafka 이벤트 형식으로 변환하여 price-service로 전달한다.
 * 동작: 요청 DTO를 받아 PriceRequestEvent를 생성한 뒤 price-topic으로 발행하고,
 *       발행 결과를 응답 DTO로 반환한다.
 * 연관: PriceCheckRequest, PriceCheckResponse, PriceRequestEvent, KafkaTemplate.
 */
@Service
public class PriceRequestService {

    private final KafkaTemplate<String, PriceRequestEvent> kafkaTemplate;
    private final String priceTopic;

    public PriceRequestService(
            KafkaTemplate<String, PriceRequestEvent> kafkaTemplate,
            @Value("${app.kafka.topics.price-topic}") String priceTopic
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.priceTopic = priceTopic;
    }

    /**
     * 가격 확인 요청을 Kafka로 발행한다.
     *
     * @param request 사용자 가격 확인 요청
     * @return 발행 결과 응답 DTO
     */
    public PriceCheckResponse publishPriceCheckRequest(PriceCheckRequest request) {
        String eventId = UUID.randomUUID().toString();

        PriceRequestEvent event = new PriceRequestEvent(
                eventId,
                "PRICE_CHECK_REQUEST",
                Instant.now(),
                "command-service",
                new PriceRequestEventPayload(
                        request.userId(),
                        request.keyword(),
                        request.targetPrice()
                )
        );

        // Kafka 메시지 Key는 userId로 지정한다.
        // 같은 사용자의 요청을 추적하거나 파티션 분배 기준으로 활용하기 쉽다.
        kafkaTemplate.send(priceTopic, String.valueOf(request.userId()), event);

        return new PriceCheckResponse(
                eventId,
                priceTopic,
                "price-topic 발행 성공"
        );
    }
}
