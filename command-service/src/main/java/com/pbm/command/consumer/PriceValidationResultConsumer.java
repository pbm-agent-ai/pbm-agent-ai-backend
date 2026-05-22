package com.pbm.command.consumer;

import com.pbm.command.domain.CommandSessionStatus;
import com.pbm.command.dto.event.PriceValidationResultEvent;
import com.pbm.command.dto.response.CommandSessionResponse;
import com.pbm.command.dto.response.ProductCandidateResponse;
import com.pbm.command.dto.response.SelectionValidationResultResponse;
import com.pbm.command.service.AgentRunService;
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
 *       nextStatus가 BROWSER_PURCHASE_IN_PROGRESS이면 AgentRun을 자동 생성한다.
 * 연관: PriceValidationResultEvent, CommandSessionService, AgentRunService.
 */
@Slf4j
@Component
public class PriceValidationResultConsumer {

    private final CommandSessionService commandSessionService;
    private final AgentRunService agentRunService;

    public PriceValidationResultConsumer(
            CommandSessionService commandSessionService,
            AgentRunService agentRunService
    ) {
        this.commandSessionService = commandSessionService;
        this.agentRunService = agentRunService;
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

        // confirmationRequired = true이면 사용자에게 되물어볼 필요가 있음을 체크
        if (event.payload().confirmationRequired()) {
            commandSessionService.updateToResubscribeConfirmationRequired(
                    event.payload().commandId(),        // 주문 식별 번호
                    validationResult                    // 상품 중복중인 내용 메시지 띄움
            );
            // 여기서 return이 없다면 아래 코드를 계속 실행해버림
            return;
        }

        CommandSessionStatus nextStatus = CommandSessionStatus.valueOf(event.payload().nextStatus());
        CommandSessionResponse sessionResponse = commandSessionService.completeValidation(
                event.payload().commandId(),
                nextStatus,
                validationResult
        );

        // 브라우저 구매 실행이 필요한 경우 AgentRun을 자동 생성한다.
        if (nextStatus == CommandSessionStatus.BROWSER_PURCHASE_IN_PROGRESS) {
            log.info("BROWSER_PURCHASE_IN_PROGRESS - AgentRun 생성 시작 - commandId: {}, userId: {}",
                    event.payload().commandId(), sessionResponse.userId());
            try {
                var agentRunResponse = agentRunService.createRun(
                        sessionResponse.userId(),
                        event.payload().commandId()
                );
                log.info("AgentRun 생성 완료 - runId: {}, status: {}, commandId: {}",
                        agentRunResponse.runId(), agentRunResponse.status(), agentRunResponse.commandId());
            } catch (Exception e) {
                log.error("AgentRun 생성 실패 - commandId: {}, userId: {}, error: {}",
                        event.payload().commandId(), sessionResponse.userId(), e.getMessage(), e);
            }
        }
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
                        product.imageUrl(),
                        product.currency(),
                        product.platform(),
                        product.searchKeyword()
                ))
                .toList();
    }
}
