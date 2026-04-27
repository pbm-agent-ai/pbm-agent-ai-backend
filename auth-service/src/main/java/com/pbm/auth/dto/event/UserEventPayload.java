package com.pbm.auth.dto.event;

/**
 * user-event 토픽의 실제 사용자 데이터를 담는 Payload DTO.
 *
 * 역할: notification-service가 로그인/회원가입 알림을 만들 때 필요한 최소 사용자 정보를 전달한다.
 * 동작: auth-service가 회원가입 또는 로그인 성공 시 공통 Envelope(Message, 택배상자) 안에 이 payload(상자안의 내용물)를 담아 발행한다.
 * 연관: UserEvent, AuthService, notification-service consumer.
 */
// 카프카에 날아가는 전체 이벤트 (Envelope)
/**
{
  "eventId": "evt-12345",              // [메타데이터] 이벤트 고유 ID
  "eventType": "USER_SIGNUP",          // [메타데이터] 이벤트 종류 (회원가입)
  "timestamp": "2026-04-25T10:15:36",  // [메타데이터] 발생 시간
  "source": "auth-service",            // [메타데이터] 누가 보냈나?
  "payload": {                         // 🎁 [진짜 목적 데이터: UserEventPayload]
    "userId": 777,
    "email": "user@example.com",
    "nickname": "개발자지망생"
  }
}
 */
public record UserEventPayload(
        Long userId,
        String email,
        String nickname
) {
}
