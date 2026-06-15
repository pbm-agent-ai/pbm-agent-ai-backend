package com.pbm.command.dto.event;

import java.util.List;

/**
 * 선택 상품 실시간 검증 결과 페이로드 DTO.
 *
 * 역할: 즉시 조건 충족 상품(triggered)과 이후 모니터링 등록 상품(monitoring)을
 *       분리하여 command-service 세션에 기록할 수 있게 한다.
 * 동작: nextStatus는 command-session이 최종적으로 도달해야 할 상태를 문자열로 전달한다.
 * triggerPrice: 모니터링 조건 충족 시점의 실제 KRW 가격 (즉시 결제 시 null).
 *               CATALOG_NAVIGATOR가 올바른 기준가로 판매처를 탐색할 수 있게 전달한다.
 * aiAgentPrivateKey: 자동결제용 AI 에이전트 개인키 (모니터링 조건 충족 시 세션키에서 가져옴).
 *                    command-service가 결제 이벤트 발행 시 사용한다.
 * subscriptionId: 모니터링 구독 ID. 상품 단위로 수수료 이력을 묶는 데 사용한다.
 * 연관: PriceValidationResultEvent, ProductCandidateDto.
 */
public record PriceValidationResultEventPayload(
        Long subscriptionId,
        String commandId,
        String nextStatus,
        List<ProductCandidateDto> triggeredProducts,
        List<ProductCandidateDto> monitoringProducts,
        String purchasedProductId,
        String summaryMessage,
        boolean confirmationRequired,
        List<ProductCandidateDto> duplicateProducts,
        String confirmationMessage,
        /** 모니터링 트리거 시점의 실제 KRW 가격. 즉시 결제 시 null. */
        Integer triggerPrice,
        /** 자동결제용 AI 에이전트 개인키. 모니터링 조건 충족 시 세션키에서 가져옴. */
        String aiAgentPrivateKey
) {
}
