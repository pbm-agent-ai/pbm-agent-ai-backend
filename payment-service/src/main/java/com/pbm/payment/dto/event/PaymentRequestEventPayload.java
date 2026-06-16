package com.pbm.payment.dto.event;

/**
 * payment-topic 토픽의 실제 자동결제 요청 데이터를 담는 Payload DTO.
 * <p>
 * 역할: payment-service가 결제 시도 준비를 하는 데 필요한 상품/금액 + 블록체인 정보를 전달받는다.
 * 동작: price-service가 자동결제 조건 충족을 판단하면 공통 이벤트 Envelope 안에 이 payload를 담아 발행한다.
 * 연관: PaymentRequestEvent, PaymentService.
 *
 * @param userId            사용자 식별자
 * @param subscriptionId    모니터링 구독 ID (상품별 수수료/결제 이력 연결용, null 가능)
 * @param productName       결제 상품명
 * @param productUrl        상품 상세 페이지 URL
 * @param amount            결제 금액 (KRW 기준 정수)
 * @param currency          통화 구분 ("KRW" 등)
 * @param aiAgentPrivateKey AI 에이전트 개인키 (executeAIPayment 서명용, 조건별 고유 키)
 * @param recipientAddress  PBM 토큰 수신 주소 (판매자 or 서비스 결제 주소)
 * @param productImageUrl   상품 이미지 URL (결제 내역 표시용, null 가능)
 */
public record PaymentRequestEventPayload(
        Long userId,
        Long subscriptionId,
        String productName,
        String productUrl,
        Integer amount,
        String currency,
        String aiAgentPrivateKey,
        String recipientAddress,
        String productImageUrl
) {
}
