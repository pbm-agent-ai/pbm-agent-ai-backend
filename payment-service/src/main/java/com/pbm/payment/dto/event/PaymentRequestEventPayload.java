package com.pbm.payment.dto.event;

/**
 * payment-topic 토픽의 실제 자동결제 요청 데이터를 담는 Payload DTO.
 *
 * 역할: payment-service가 결제 시도 준비를 하는 데 필요한 최소 상품/금액 정보를 전달받는다.
 * 동작: price-service가 자동결제 조건 충족을 판단하면 공통 이벤트 Envelope 안에 이 payload를 담아 발행한다.
 * 연관: PaymentRequestEvent, PaymentService.
 */
public record PaymentRequestEventPayload(
        Long userId,
        String productName,
        String productUrl,
        Integer amount,
        String currency
) {
}
