package com.pbm.notification.dto.event;

/**
 * payment-result 토픽의 실제 결제 결과 데이터를 담는 Payload DTO.
 *
 * 역할: notification-service가 결제 완료/실패 알림을 생성할 때 필요한 정보를 전달한다.
 * 동작: payment-service가 결제를 완료하거나 실패하면 공통 이벤트 Envelope(PaymentResultEvent) 안에
 *       이 payload를 담아 발행하고, notification-service 컨슈머가 이 필드들을 읽어 알림을 구성한다.
 * 연관: PaymentResultEvent, PaymentResultConsumer.
 *
 * 필드 설명:
 * - userId:            결제를 요청한 사용자 ID
 * - paymentId:         결제 고유 ID (payment-service에서 생성)
 * - status:            결제 상태 (예: "COMPLETED", "FAILED")
 * - transactionHash:   블록체인 트랜잭션 해시 (성공 시에만 유효)
 * - message:           결제 결과 메시지 (성공 시 "결제 완료", 실패 시 실패 사유)
 * - amount:            결제 금액 (단위: 원, 정수)
 * - fee:               결제 수수료 (단위: 원, 정수, nullable)
 * - remainingBalance:  결제 후 남은 잔액 (단위: 원, 정수, nullable)
 */
public record PaymentResultEventPayload(
        Long userId,
        String paymentId,
        String status,
        String transactionHash,
        String message,
        Integer amount,
        Integer fee,
        Integer remainingBalance
) {
}
