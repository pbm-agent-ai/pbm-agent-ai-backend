package com.pbm.command.dto.event;

/**
 * 결제 페이지 도달 시 결제 요청에 필요한 데이터를 담는 Payload DTO.
 *
 * 역할: payment-service가 PBM 토큰 차감을 실행하는 데 필요한 정보를 전달한다.
 * 동작: payment-service의 PaymentRequestEventPayload와 동일한 필드 구조를 사용하여
 *       기존 PaymentRequestConsumer가 그대로 처리할 수 있도록 한다.
 * 연관: CheckoutPaymentEvent, payment-service consumer.
 *
 * @param userId            사용자 식별자
 * @param subscriptionId    모니터링 구독 ID (상품별 수수료/결제 이력 연결용, null 가능)
 * @param productName       상품명
 * @param productUrl        결제 페이지 URL (결제 직전 URL)
 * @param amount            결제 금액 (KRW 기준 정수)
 * @param currency          통화 구분 ("KRW" 등)
 * @param aiAgentPrivateKey AI 에이전트 개인키 (null이면 스텁 처리)
 * @param recipientAddress  PBM 토큰 수신 주소 (null이면 기본값 사용)
 */
public record CheckoutPaymentEventPayload(
        Long userId,
        Long subscriptionId,
        String productName,
        String productUrl,
        Integer amount,
        String currency,
        String aiAgentPrivateKey,
        String recipientAddress
) {
}
