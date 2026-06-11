package com.pbm.notification.dto.response;

import com.pbm.notification.domain.NotificationPreference;

/**
 * 알림 설정 조회 응답 DTO.
 *
 * 역할: 프론트엔드에 현재 사용자의 알림 채널 설정 상태를 전달한다.
 * 연관: NotificationPreferenceController, NotificationPreference.
 *
 * @param email            알림 수신용 이메일 주소
 * @param emailEnabled     이메일 알림 활성화 여부
 * @param telegramLinked   텔레그램 봇 연동 완료 여부 (chatId 존재 여부)
 * @param telegramEnabled  텔레그램 알림 활성화 여부
 */
public record NotificationPreferenceResponse(
        String email,
        boolean emailEnabled,
        boolean telegramLinked,
        boolean telegramEnabled
) {

    /**
     * 엔티티를 응답 DTO로 변환한다.
     *
     * @param preference 알림 설정 엔티티
     * @return 응답 DTO
     */
    public static NotificationPreferenceResponse from(NotificationPreference preference) {
        return new NotificationPreferenceResponse(
                preference.getEmail(),
                preference.isEmailEnabled(),
                preference.isTelegramLinked(),
                preference.isTelegramEnabled()
        );
    }
}
