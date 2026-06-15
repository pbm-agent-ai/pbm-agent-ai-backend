package com.pbm.notification.dto.request;

/**
 * 알림 설정 저장/수정 요청 DTO.
 *
 * 역할: 프론트엔드에서 사용자가 알림 채널을 설정할 때 전달하는 요청 데이터.
 * 연관: NotificationPreferenceController.
 *
 * @param email           알림 수신용 이메일 주소 (null이면 이메일 비활성화)
 * @param emailEnabled    이메일 알림 활성화 여부
 * @param telegramEnabled 텔레그램 알림 활성화 여부 (chatId가 연동된 경우에만 의미 있음)
 */
public record NotificationPreferenceRequest(
        String email,
        boolean emailEnabled,
        boolean telegramEnabled
) {
}
