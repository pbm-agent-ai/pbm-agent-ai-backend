package com.pbm.payment.dto.event;

/**
 * payment-result 토픽의 실제 결제 결과 데이터를 담는 Payload DTO.
 *
 * 역할: notification-service가 결제 완료/실패 알림을 만들 때 필요한 최소 결과 정보를 전달한다.
 * 동작: payment-service가 결제 처리 후 상태와 메시지를 공통 이벤트 Envelope 안에 담아 발행한다.
 * 연관: PaymentResultEvent, notification-service consumer.
 */
public record PaymentResultEventPayload(
        Long userId,
        String paymentId,
        String status,
        String transactionHash,
        String message
) {
}
