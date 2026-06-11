package com.pbm.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 텔레그램 옵션 선택 대기 요청 영속 엔티티.
 *
 * 역할: notification-service 재시작 이후에도 chatId 기준 대기 상태를 복구할 수 있게 한다.
 */
@Entity
@Table(name = "pending_option_selections")
public class PendingOptionSelectionEntity {

    @Id
    @Column(nullable = false, length = 100)
    private String chatId;

    @Column(nullable = false, length = 36)
    private String runId;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String optionGroupsJson;

    @Column(nullable = false)
    private Instant expiresAt;

    protected PendingOptionSelectionEntity() {
    }

    public PendingOptionSelectionEntity(
            String chatId,
            String runId,
            Long userId,
            String optionGroupsJson,
            Instant expiresAt
    ) {
        this.chatId = chatId;
        this.runId = runId;
        this.userId = userId;
        this.optionGroupsJson = optionGroupsJson;
        this.expiresAt = expiresAt;
    }

    public String getChatId() {
        return chatId;
    }

    public String getRunId() {
        return runId;
    }

    public Long getUserId() {
        return userId;
    }

    public String getOptionGroupsJson() {
        return optionGroupsJson;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
