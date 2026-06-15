package com.pbm.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 텔레그램 로그인 자격증명 대기 요청 영속 엔티티.
 */
@Entity
@Table(name = "pending_login_credentials")
public class PendingLoginCredentialEntity {

    @Id
    @Column(nullable = false, length = 100)
    private String chatId;

    @Column(nullable = false, length = 36)
    private String runId;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private Instant expiresAt;

    protected PendingLoginCredentialEntity() {
    }

    public PendingLoginCredentialEntity(String chatId, String runId, Long userId, Instant expiresAt) {
        this.chatId = chatId;
        this.runId = runId;
        this.userId = userId;
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

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
