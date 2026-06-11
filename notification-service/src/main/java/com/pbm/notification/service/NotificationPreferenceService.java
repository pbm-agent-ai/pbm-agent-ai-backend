package com.pbm.notification.service;

import com.pbm.notification.domain.NotificationPreference;
import com.pbm.notification.dto.request.NotificationPreferenceRequest;
import com.pbm.notification.dto.response.NotificationPreferenceResponse;
import com.pbm.notification.repository.NotificationPreferenceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 알림 설정 관리 서비스.
 *
 * 역할: 사용자별 알림 채널 설정(이메일, 텔레그램)을 조회/수정한다.
 * 동작: userId를 기준으로 notification_preferences를 조회하거나 생성(upsert)한다.
 * 연관: NotificationPreferenceController, NotificationPreferenceRepository.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class NotificationPreferenceService {

    private final NotificationPreferenceRepository preferenceRepository;

    /**
     * 사용자의 알림 설정을 조회한다. 설정이 없으면 기본값으로 생성 후 반환한다.
     *
     * @param userId 사용자 ID (JWT에서 추출)
     * @return 알림 설정 응답 DTO
     */
    /**
     * 사용자의 알림 설정을 조회한다. 설정이 없으면 기본값으로 생성 후 반환한다.
     * 참고: 없을 때 INSERT가 필요하므로 readOnly = false (클래스 기본 트랜잭션) 사용.
     */
    public NotificationPreferenceResponse getPreference(Long userId) {
        NotificationPreference preference = preferenceRepository.findByUserId(userId)
                .orElseGet(() -> {
                    log.info("알림 설정 없음 - 기본값 생성, userId: {}", userId);
                    return preferenceRepository.save(NotificationPreference.createDefault(userId));
                });

        return NotificationPreferenceResponse.from(preference);
    }

    /**
     * 사용자의 알림 설정을 저장/수정한다 (Upsert).
     *
     * @param userId  사용자 ID (JWT에서 추출)
     * @param request 알림 설정 요청 DTO
     * @return 수정된 알림 설정 응답 DTO
     */
    public NotificationPreferenceResponse updatePreference(Long userId, NotificationPreferenceRequest request) {
        NotificationPreference preference = preferenceRepository.findByUserId(userId)
                .orElseGet(() -> {
                    log.info("알림 설정 없음 - 신규 생성, userId: {}", userId);
                    return NotificationPreference.createDefault(userId);
                });

        // 이메일 설정 반영
        if (request.emailEnabled() && request.email() != null && !request.email().isBlank()) {
            preference.enableEmail(request.email());
        } else {
            preference.disableEmail();
            // 이메일 주소가 제공되었으면 저장 (활성화하지는 않음)
            if (request.email() != null && !request.email().isBlank()) {
                preference.enableEmail(request.email());
                preference.disableEmail();
            }
        }

        // 텔레그램 설정 반영 (chatId가 연동된 경우에만 활성화 가능)
        if (request.telegramEnabled() && preference.isTelegramLinked()) {
            preference.linkTelegram(preference.getTelegramChatId());
        } else {
            preference.disableTelegram();
        }

        NotificationPreference saved = preferenceRepository.save(preference);
        log.info("알림 설정 업데이트 완료 - userId: {}, emailEnabled: {}, telegramEnabled: {}",
                userId, saved.isEmailEnabled(), saved.isTelegramEnabled());

        return NotificationPreferenceResponse.from(saved);
    }

    /**
     * 텔레그램 봇 연동 시 chatId를 저장한다.
     * 텔레그램 Webhook에서 /start 명령 수신 시 호출된다.
     *
     * @param userId 사용자 ID (딥링크 파라미터에서 추출)
     * @param chatId 텔레그램 chat ID
     */
    public void linkTelegram(Long userId, String chatId) {
        NotificationPreference preference = preferenceRepository.findByUserId(userId)
                .orElseGet(() -> NotificationPreference.createDefault(userId));

        preference.linkTelegram(chatId);
        preferenceRepository.save(preference);

        log.info("텔레그램 연동 완료 - userId: {}, chatId: {}", userId, chatId);
    }

    /**
     * 텔레그램 연동을 해제한다.
     *
     * @param userId 사용자 ID
     */
    public void unlinkTelegram(Long userId) {
        preferenceRepository.findByUserId(userId).ifPresent(preference -> {
            preference.unlinkTelegram();
            preferenceRepository.save(preference);
            log.info("텔레그램 연동 해제 - userId: {}", userId);
        });
    }

    /**
     * 현재 사용자의 텔레그램 chatId를 조회한다.
     *
     * @param userId 사용자 ID
     * @return 저장된 텔레그램 chatId
     */
    public String getTelegramChatId(Long userId) {
        NotificationPreference preference = preferenceRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalStateException("알림 설정이 존재하지 않습니다."));

        if (preference.getTelegramChatId() == null || preference.getTelegramChatId().isBlank()) {
            throw new IllegalStateException("연동된 텔레그램 chatId가 없습니다.");
        }

        return preference.getTelegramChatId();
    }
}
