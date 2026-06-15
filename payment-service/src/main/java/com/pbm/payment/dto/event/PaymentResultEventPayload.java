package com.pbm.payment.dto.event;

/**
 * payment-result 토픽의 실제 결제 결과 데이터를 담는 Payload DTO.
 *
 * 역할: notification-service가 결제 완료/실패 알림을 만들 때 필요한 최소 결과 정보를 전달한다.
 *       amount(결제 금액), fee(수수료), remainingBalance(결제 후 잔액)를 추가로 포함하여
 *       알림 메시지에 정확한 금액 정보를 표시할 수 있게 한다.
 * 동작: payment-service가 결제 처리 후 상태와 메시지를 공통 이벤트 Envelope 안에 담아 발행한다.
 *       결제 성공 시 PaymentResultEventPublisher에서 blockchainService와 walletService를 통해
 *       추가 정보(잔액 등)를 조회하여 이 record에 포함시킨다.
 * 연관: PaymentResultEvent, PaymentResultEventPublisher, notification-service consumer.
 */
public record PaymentResultEventPayload(
        Long userId,
        String paymentId,
        String status,
        String transactionHash,
        String message,

        /** 결제 금액 (KRW 단위 정수, 예: 15000 = 15,000원).
         *  Payment.amount에서 직접 가져오며, 성공/실패 여부와 관계없이 항상 포함된다. */
        Integer amount,

        /** 블록체인 트랜잭션 수수료 (가스비, KRW 단위 정수).
         *  현재는 복잡한 가스비 계산 로직으로 인해 null로 전달되며,
         *  추후 Payment 엔티티에 fee 필드가 추가되면 정확한 값이 포함된다. */
        Integer fee,

        /** 결제 완료 후 사용자 PBM 스마트 지갑의 잔액 (KRW 단위 정수).
         *  blockchainService.getPbmBalance()로 조회하며,
         *  조회 실패 시 null이 전달될 수 있다. */
        Integer remainingBalance
) {
}
