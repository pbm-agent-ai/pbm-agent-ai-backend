package com.pbm.notification.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

/**
 * 사용자별 알림 채널 설정 엔티티.
 *
 * 역할: 사용자가 가격 알림을 어떤 채널(이메일, 텔레그램)로 받을지 설정을 저장한다.
 * 동작: 알림 발송 시 userId로 이 엔티티를 조회하여 활성화된 채널로만 발송한다.
 * 연관: NotificationPreferenceRepository, NotificationService.
 */
@Entity
@Table(name = "notification_preferences")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class NotificationPreference {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** auth-service의 users.id와 매핑 */
    @Column(nullable = false, unique = true)
    private Long userId;

    /** 알림 수신용 이메일 주소 (auth-service의 이메일과 다를 수 있음) */
    @Column
    private String email;

    /** 이메일 알림 활성화 여부 */
    @Column(nullable = false)
    private boolean emailEnabled;

    /** 텔레그램 봇 연동 시 저장되는 chat ID */
    @Column
    private String telegramChatId;

    /** 텔레그램 알림 활성화 여부 */
    @Column(nullable = false)
    private boolean telegramEnabled;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private Instant updatedAt;

    // ──────────────────────────────────────────────────────────────────────────
    // 생성 팩토리 메서드
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * 신규 알림 설정을 생성한다. 기본적으로 모든 채널이 비활성화 상태로 생성된다.
     *
     * @param userId 사용자 ID
     * @return 새 NotificationPreference 인스턴스
     */
    public static NotificationPreference createDefault(Long userId) {
        NotificationPreference preference = new NotificationPreference();
        preference.userId = userId;
        preference.emailEnabled = false;
        preference.telegramEnabled = false;
        return preference;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 이메일 설정 변경
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * 이메일 알림을 활성화하고 이메일 주소를 설정한다.
     *
     * @param email 알림 수신용 이메일 주소
     */
    public void enableEmail(String email) {
        this.email = email;
        this.emailEnabled = true;
    }

    /**
     * 이메일 알림을 비활성화한다. 이메일 주소는 유지한다.
     */
    public void disableEmail() {
        this.emailEnabled = false;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 텔레그램 설정 변경
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * 텔레그램 연동을 완료하고 활성화한다.
     *
     * @param chatId 텔레그램 봇과 대화 중인 chat ID
     */
    public void linkTelegram(String chatId) {
        this.telegramChatId = chatId;
        this.telegramEnabled = true;
    }

    /**
     * 텔레그램 알림을 비활성화한다. chatId는 유지한다 (재활성화 시 재연동 불필요).
     */
    public void disableTelegram() {
        this.telegramEnabled = false;
    }

    public void unlinkTelegram() {
        this.telegramChatId = null;
        this.telegramEnabled = false;
    }

    /**
     * 텔레그램이 연동(chatId 존재)되어 있는지 확인한다.
     */
    public boolean isTelegramLinked() {
        return this.telegramChatId != null && !this.telegramChatId.isBlank();
    }
}
