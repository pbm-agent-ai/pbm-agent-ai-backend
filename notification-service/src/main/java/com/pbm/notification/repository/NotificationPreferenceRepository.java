package com.pbm.notification.repository;

import com.pbm.notification.domain.NotificationPreference;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 알림 설정 JPA 레포지토리.
 *
 * 역할: notification_preferences 테이블에 대한 CRUD 및 조회를 제공한다.
 * 연관: NotificationPreference, NotificationService.
 */
public interface NotificationPreferenceRepository extends JpaRepository<NotificationPreference, Long> {

    /**
     * userId로 알림 설정을 조회한다.
     *
     * @param userId 사용자 ID
     * @return 알림 설정 (없으면 empty)
     */
    Optional<NotificationPreference> findByUserId(Long userId);

    /**
     * 텔레그램 chatId로 알림 설정을 조회한다.
     * 텔레그램 Webhook에서 chatId를 받아 사용자를 식별할 때 사용한다.
     *
     * @param telegramChatId 텔레그램 chat ID
     * @return 알림 설정 (없으면 empty)
     */
    Optional<NotificationPreference> findByTelegramChatId(String telegramChatId);

    /**
     * 해당 userId의 알림 설정 존재 여부를 확인한다.
     *
     * @param userId 사용자 ID
     * @return 존재하면 true
     */
    boolean existsByUserId(Long userId);
}
