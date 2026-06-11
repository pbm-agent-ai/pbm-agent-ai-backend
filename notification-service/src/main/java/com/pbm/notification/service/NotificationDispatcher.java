package com.pbm.notification.service;

import com.pbm.notification.client.TelegramBotClient;
import com.pbm.notification.domain.NotificationPreference;
import com.pbm.notification.repository.NotificationPreferenceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 멀티채널 알림 발송 디스패처.
 *
 * 역할: 사용자의 알림 설정을 조회하여 활성화된 채널(이메일, 텔레그램)로 메시지를 발송한다.
 * 동작:
 *   1. userId로 notification_preferences 조회
 *   2. emailEnabled이면 EmailNotificationSender로 이메일 발송
 *   3. telegramEnabled이면 TelegramBotClient로 텔레그램 메시지 발송
 *   4. 둘 다 비활성화이면 ConsoleNotificationSender(로그)로 fallback
 * 연관: NotificationService(호출자), EmailNotificationSender, TelegramBotClient,
 *       NotificationPreferenceRepository.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationDispatcher {

    private final NotificationPreferenceRepository preferenceRepository;
    private final EmailNotificationSender emailNotificationSender;
    private final TelegramBotClient telegramBotClient;
    private final ConsoleNotificationSender consoleNotificationSender;

    /**
     * 사용자에게 활성화된 모든 채널로 알림을 발송한다.
     *
     * @param userId  알림 대상 사용자 ID
     * @param subject 알림 제목 (이메일용)
     * @param message 알림 본문 (모든 채널 공통)
     */
    public void dispatch(Long userId, String subject, String message) {
        NotificationPreference preference = preferenceRepository.findByUserId(userId)
                .orElse(null);

        // 알림 설정이 없으면 콘솔에만 출력
        if (preference == null) {
            log.info("알림 설정 없음 - 콘솔 출력만 수행, userId: {}", userId);
            consoleNotificationSender.send(message);
            return;
        }

        boolean sent = false;

        // 이메일 채널 발송
        if (preference.isEmailEnabled() && preference.getEmail() != null) {
            try {
                emailNotificationSender.send(preference.getEmail(), subject, message);
                sent = true;
            } catch (Exception e) {
                log.error("이메일 발송 실패 - userId: {}, email: {}, error: {}",
                        userId, preference.getEmail(), e.getMessage());
            }
        }

        // 텔레그램 채널 발송
        if (preference.isTelegramEnabled() && preference.isTelegramLinked()) {
            try {
                // 텔레그램은 HTML 파싱 모드를 사용하므로 plain text를 그대로 전달
                telegramBotClient.sendMessage(preference.getTelegramChatId(), message);
                sent = true;
            } catch (Exception e) {
                log.error("텔레그램 발송 실패 - userId: {}, chatId: {}, error: {}",
                        userId, preference.getTelegramChatId(), e.getMessage());
            }
        }

        // 어떤 채널로도 발송되지 않았으면 콘솔 fallback
        if (!sent) {
            log.info("활성화된 알림 채널 없음 - 콘솔 출력 fallback, userId: {}", userId);
            consoleNotificationSender.send(message);
        }
    }
}
