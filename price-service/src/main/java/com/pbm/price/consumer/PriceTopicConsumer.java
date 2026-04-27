package com.pbm.price.consumer;

import com.pbm.price.dto.event.PriceAlertEvent;
import com.pbm.price.dto.event.PriceAlertEventPayload;
import com.pbm.price.dto.event.PriceRequestEvent;
import com.pbm.price.dto.response.SearchResponse;
import com.pbm.price.publisher.PriceAlertEventPublisher;
import com.pbm.price.service.NaverShoppingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * price-topic 메시지 소비 컴포넌트.
 *
 * 역할: command-service 등에서 발행한 가격 확인 요청(PriceRequestEvent)을 수신하여
 *       네이버 쇼핑 API로 상품을 검색하고, 목표 가격 충족 여부를 판단한다.
 * 동작:
 *   1. price-topic에서 PriceRequestEvent 수신
 *   2. payload.keyword로 네이버 쇼핑 상품 검색 (최대 1건)
 *   3. 첫 번째 상품의 최저가(lprice)와 payload.targetPrice 비교
 *   4. 현재가 ≤ 목표가이면 PriceAlertEvent를 price-alert 토픽으로 발행
 *   5. 조건 미충족 또는 검색 결과 없음 시 로그만 출력하고 발행하지 않음
 * 연관: PriceRequestEvent, PriceAlertEvent, NaverShoppingService, PriceAlertEventPublisher.
 */
@Slf4j
@Component
public class PriceTopicConsumer {

    private final NaverShoppingService naverShoppingService;
    private final PriceAlertEventPublisher priceAlertEventPublisher;

    public PriceTopicConsumer(NaverShoppingService naverShoppingService,
                              PriceAlertEventPublisher priceAlertEventPublisher) {
        this.naverShoppingService = naverShoppingService;
        this.priceAlertEventPublisher = priceAlertEventPublisher;
    }

    /**
     * price-topic 메시지 수신 및 가격 비교 처리.
     * 검색 결과의 첫 번째 상품 최저가와 목표 가격을 비교하여
     * 조건 충족 시 price-alert 이벤트를 발행한다.
     *
     * @param event 수신한 가격 확인 요청 이벤트
     */
    @KafkaListener(topics = "${app.kafka.topics.price-topic}", groupId = "${spring.kafka.consumer.group-id}")
    public void consume(PriceRequestEvent event) {
        log.info("price-topic 메시지 수신 - eventId: {}, keyword: {}, targetPrice: {}",
                event.eventId(), event.payload().keyword(), event.payload().targetPrice());

        String keyword = event.payload().keyword();
        Integer targetPrice = event.payload().targetPrice();

        // 네이버 쇼핑에서 키워드로 상품 검색 (최소 1건만 필요)
        List<SearchResponse> results = naverShoppingService.searchProducts(keyword, 1);

        // 검색 결과가 없으면 로그 출력 후 종료
        if (results.isEmpty()) {
            log.info("검색 결과 없음 - keyword: {}, price-alert 발행 생략", keyword);
            return;
        }

        SearchResponse firstResult = results.get(0);
        int currentPrice = parsePrice(firstResult.lprice());

        log.info("가격 비교 - keyword: {}, 상품: {}, 현재가: {}, 목표가: {}",
                keyword, firstResult.title(), currentPrice, targetPrice);

        // 현재가가 목표가 이하이면 price-alert 이벤트 발행
        if (currentPrice <= targetPrice) {
            PriceAlertEvent alertEvent = createAlertEvent(event, firstResult, currentPrice, targetPrice);
            priceAlertEventPublisher.publish(alertEvent);
            log.info("목표 가격 충족 - price-alert 이벤트 발행 완료 - productName: {}, currentPrice: {}",
                    firstResult.title(), currentPrice);
        } else {
            log.info("목표 가격 미충족 - keyword: {}, currentPrice: {} > targetPrice: {}, 발행 생략",
                    keyword, currentPrice, targetPrice);
        }
    }

    /**
     * 네이버 쇼핑 응답의 lprice 문자열을 정수로 변환한다.
     * lprice가 빈 문자열이거나 숫자가 아니면 최대 정수값을 반환하여 조건 미충족 처리한다.
     */
    private int parsePrice(String lprice) {
        try {
            return Integer.parseInt(lprice);
        } catch (NumberFormatException e) {
            log.warn("lprice 파싱 실패 - 값: '{}', 최대값으로 처리", lprice);
            return Integer.MAX_VALUE;
        }
    }

    /**
     * PriceAlertEvent 객체를 생성한다.
     * eventId는 새로 생성하고, eventType은 PRICE_ALERT로 설정한다.
     */
    private PriceAlertEvent createAlertEvent(PriceRequestEvent requestEvent,
                                             SearchResponse searchResult,
                                             int currentPrice,
                                             int targetPrice) {
        return new PriceAlertEvent(
                UUID.randomUUID().toString(),
                "PRICE_ALERT",
                Instant.now(),
                "price-service",
                new PriceAlertEventPayload(
                        requestEvent.payload().userId(),
                        searchResult.title(),
                        currentPrice,
                        targetPrice,
                        searchResult.link()
                )
        );
    }
}