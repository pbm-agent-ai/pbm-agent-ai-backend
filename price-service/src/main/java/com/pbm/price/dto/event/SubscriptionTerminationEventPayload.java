package com.pbm.price.dto.event;

/**
 * 구독 종료(취소/만료) 이벤트 페이로드.
 *
 * @param subscriptionId price-service 구독 ID
 * @param userId         사용자 식별자
 * @param reason         종료 사유 — "CANCELLED"(사용자 취소) | "EXPIRED"(기간 만료)
 */
public record SubscriptionTerminationEventPayload(
        Long subscriptionId,
        Long userId,
        String reason
) {
}
