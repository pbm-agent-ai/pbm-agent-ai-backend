package com.pbm.price.dto.event;

/**
 * payment-topic 토픽의 실제 자동결제 요청 데이터를 담는 Payload DTO.
 * <p>
 * 역할: payment-service가 자동결제를 실행하는 데 필요한 상품/금액 + 블록체인 정보를 전달한다.
 * 동작: price-service가 자동결제 조건 충족을 판단하면 공통 이벤트 Envelope 안에 이 payload를 담아 발행한다.
 * 연관: PaymentRequestEvent, payment-service consumer.
 *
 * @param userId            사용자 식별자
 * @param productName       상품명
 * @param productUrl        상품 상세 페이지 URL
 * @param amount            결제 금액 (KRW 기준 정수)
 * @param currency          통화 구분 ("KRW" 등)
 * @param aiAgentPrivateKey AI 에이전트 개인키 (executeAIPayment 서명용, null이면 스텁 처리)
 * @param recipientAddress  PBM 토큰 수신 주소 (null이면 payment-service가 기본값 사용)
 */
public record PaymentRequestEventPayload(
        Long userId,
        String productName,
        String productUrl,
        Integer amount,
        String currency,
        String aiAgentPrivateKey,
        String recipientAddress
) {
}
