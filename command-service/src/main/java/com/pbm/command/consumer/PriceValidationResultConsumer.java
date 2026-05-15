package com.pbm.command.consumer;

import com.pbm.command.domain.CommandSessionStatus;
import com.pbm.command.dto.event.PriceValidationResultEvent;
import com.pbm.command.dto.response.ProductCandidateResponse;
import com.pbm.command.dto.response.SelectionValidationResultResponse;
import com.pbm.command.service.CommandSessionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 선택 상품 검증 결과 토픽 메시지 소비 컴포넌트.
 *
 * 역할: price-service가 다중 선택 상품을 단건 재조회한 결과를 받아
 *       command-session의 최종 상태와 검증 결과를 저장한다.
 * 동작: triggered 상품과 monitoring 상품 목록을 세션 응답용 DTO로 변환한 뒤,
 *       CommandSessionService.completeValidation()을 호출한다.
 * 연관: PriceValidationResultEvent, CommandSessionService.
 */
@Slf4j
@Component
public class PriceValidationResultConsumer {

    private final CommandSessionService commandSessionService;

    public PriceValidationResultConsumer(CommandSessionService commandSessionService) {
        this.commandSessionService = commandSessionService;
    }

    /**
     * price-validation-result 토픽 메시지를 처리한다.
     *
     * @param event 수신한 검증 결과 이벤트
     */
    @KafkaListener(
            topics = "${app.kafka.topics.price-validation-result-topic}",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "priceValidationResultContainerFactory"
    )
    public void consume(PriceValidationResultEvent event) {
        log.info("선택 상품 검증 결과 수신 - eventId: {}, commandId: {}, nextStatus: {}",
                event.eventId(), event.payload().commandId(), event.payload().nextStatus());

        SelectionValidationResultResponse validationResult = new SelectionValidationResultResponse(
                toResponses(event.payload().triggeredProducts()),
                toResponses(event.payload().monitoringProducts()),
                event.payload().purchasedProductId(),
                event.payload().summaryMessage(),
                event.payload().confirmationRequired(),
                toResponses(event.payload().duplicateProducts()),
                event.payload().confirmationMessage()
        );

        if (event.payload().confirmationRequired()) {
            commandSessionService.updateToResubscribeConfirmationRequired(
                    event.payload().commandId(),
                    validationResult
            );
            return;
        }

        commandSessionService.completeValidation(
                event.payload().commandId(),
                CommandSessionStatus.valueOf(event.payload().nextStatus()),
                validationResult
        );
    }

    private List<ProductCandidateResponse> toResponses(List<com.pbm.command.dto.event.ProductCandidateDto> products) {
        if (products == null) {
            return List.of();
        }
        return products.stream()
                .map(product -> new ProductCandidateResponse(
                        product.productId(),
                        product.title(),
                        product.lprice(),
                        product.mallName(),
                        product.productUrl(),
                        product.currency(),
                        product.platform(),
                        product.searchKeyword()
                ))
                .toList();
    }
}
