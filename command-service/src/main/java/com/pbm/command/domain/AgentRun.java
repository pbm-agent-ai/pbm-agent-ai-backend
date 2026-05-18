package com.pbm.command.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;

/**
 * 브라우저 자동화 실행 단위(Entity).
 *
 * 역할: 하나의 commandId에 대해 브라우저 extension이 실제로 수행할 실행 세션을 관리한다.
 * 동작: 상태 전이는 의미 있는 메서드로만 허용하며, 잘못된 전이는 예외로 차단한다.
 * 연관: AgentRunStatus, AgentRunService.
 */
@Entity
@Table(name = "agent_runs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class AgentRun {

    private static final Set<AgentRunStatus> ACTIVE_STATUSES = Set.of(
            AgentRunStatus.QUEUED,
            AgentRunStatus.ASSIGNED,
            AgentRunStatus.RUNNING,
            AgentRunStatus.AWAITING_APPROVAL,
            AgentRunStatus.INTERRUPTED,
            AgentRunStatus.RECOVERING
    );

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 36)
    private String runId;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, length = 36)
    private String commandId;

    @Column(length = 36)
    private String assignedDeviceId;

    private LocalDateTime assignedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private AgentRunStatus status;

    @Column(nullable = false)
    private Integer currentStepIndex;

    private LocalDateTime approvalRequestedAt;

    @Column(length = 50)
    private String abortReason;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @Builder
    private AgentRun(
            String runId,
            Long userId,
            String commandId,
            String assignedDeviceId,
            LocalDateTime assignedAt,
            AgentRunStatus status,
            Integer currentStepIndex,
            LocalDateTime approvalRequestedAt,
            String abortReason
    ) {
        this.runId = runId;
        this.userId = userId;
        this.commandId = commandId;
        this.assignedDeviceId = assignedDeviceId;
        this.assignedAt = assignedAt;
        this.status = status;
        this.currentStepIndex = currentStepIndex;
        this.approvalRequestedAt = approvalRequestedAt;
        this.abortReason = abortReason;
    }

    public static AgentRun createQueued(Long userId, String commandId) {
        return AgentRun.builder()
                .runId(UUID.randomUUID().toString())
                .userId(userId)
                .commandId(commandId)
                .status(AgentRunStatus.QUEUED)
                .currentStepIndex(0)
                .build();
    }

    public void assignTo(String deviceId, LocalDateTime now) {
        requireStatus(AgentRunStatus.QUEUED, "Run 할당");
        this.assignedDeviceId = deviceId;
        this.assignedAt = now;
        this.status = AgentRunStatus.ASSIGNED;
    }

    public void start() {
        requireStatus(AgentRunStatus.ASSIGNED, "Run 시작");
        this.status = AgentRunStatus.RUNNING;
    }

    public void awaitApproval(LocalDateTime now) {
        requireStatus(AgentRunStatus.RUNNING, "승인 대기 전환");
        this.status = AgentRunStatus.AWAITING_APPROVAL;
        this.approvalRequestedAt = now;
    }

    public void approveAndResume() {
        requireStatus(AgentRunStatus.AWAITING_APPROVAL, "승인 처리");
        this.status = AgentRunStatus.RUNNING;
        this.approvalRequestedAt = null;
    }

    public void reject(String reason) {
        requireStatus(AgentRunStatus.AWAITING_APPROVAL, "승인 거부");
        this.status = AgentRunStatus.ABORTED;
        this.abortReason = reason;
    }

    public void expireApproval() {
        requireStatus(AgentRunStatus.AWAITING_APPROVAL, "승인 만료 처리");
        this.status = AgentRunStatus.APPROVAL_EXPIRED;
    }

    public void interrupt() {
        if (!Set.of(AgentRunStatus.ASSIGNED, AgentRunStatus.RUNNING, AgentRunStatus.AWAITING_APPROVAL).contains(this.status)) {
            throw new IllegalStateException("중단 감지는 ASSIGNED/RUNNING/AWAITING_APPROVAL 상태에서만 가능합니다. 현재: " + this.status);
        }
        this.status = AgentRunStatus.INTERRUPTED;
    }

    public void recover() {
        if (!Set.of(AgentRunStatus.INTERRUPTED, AgentRunStatus.RECOVERING).contains(this.status)) {
            throw new IllegalStateException("복구는 INTERRUPTED/RECOVERING 상태에서만 가능합니다. 현재: " + this.status);
        }
        this.status = AgentRunStatus.RECOVERING;
    }

    public void completeRecovery() {
        requireStatus(AgentRunStatus.RECOVERING, "복구 완료");
        this.status = AgentRunStatus.RUNNING;
    }

    public void abort(String reason) {
        if (!ACTIVE_STATUSES.contains(this.status) && this.status != AgentRunStatus.APPROVAL_EXPIRED) {
            throw new IllegalStateException("중단은 활성 또는 승인 만료 상태에서만 가능합니다. 현재: " + this.status);
        }
        this.status = AgentRunStatus.ABORTED;
        this.abortReason = reason;
    }

    public void fail(String reason) {
        if (!ACTIVE_STATUSES.contains(this.status)) {
            throw new IllegalStateException("실패 처리는 활성 상태에서만 가능합니다. 현재: " + this.status);
        }
        this.status = AgentRunStatus.FAILED;
        this.abortReason = reason;
    }

    public void complete() {
        if (this.status != AgentRunStatus.RUNNING) {
            throw new IllegalStateException("완료는 RUNNING 상태에서만 가능합니다. 현재: " + this.status);
        }
        this.status = AgentRunStatus.COMPLETED;
    }

    public void updateCurrentStepIndex(int nextStepIndex) {
        this.currentStepIndex = nextStepIndex;
    }

    public boolean isActive() {
        return ACTIVE_STATUSES.contains(this.status);
    }

    private void requireStatus(AgentRunStatus expectedStatus, String actionName) {
        if (this.status != expectedStatus) {
            throw new IllegalStateException(actionName + "는 " + expectedStatus + " 상태에서만 가능합니다. 현재: " + this.status);
        }
    }
}
