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
            AgentRunStatus.AWAITING_OPTION_SELECTION,
            AgentRunStatus.AWAITING_LOGIN_CREDENTIALS,
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

    /** 모니터링 구독 ID. 수수료와 결제 이력을 상품 단위로 연결하는 데 사용한다. */
    @Column(name = "subscription_id")
    private Long subscriptionId;

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

    /**
     * 모니터링 트리거 시점의 실제 KRW 가격.
     * <p>
     * 즉시 결제 시 null (lprice가 이미 갱신된 현재가이므로 불필요).
     * 모니터링 후 결제 시 이 값을 CATALOG_NAVIGATOR의 기준가로 사용해야
     * 선택 당시 가격(lprice)이 아닌 실제 조건 충족 가격으로 판매처를 탐색한다.
     */
    @Column(name = "trigger_price")
    private Integer triggerPrice;

    /**
     * 자동결제용 AI 에이전트 개인키.
     * <p>
     * 모니터링 조건 충족 시 price-service에서 전달받아 저장한다.
     * 결제 페이지 도달 시 payment-topic 이벤트 발행에 사용된다.
     */
    @Column(name = "ai_agent_private_key", length = 128)
    private String aiAgentPrivateKey;

    /** 텔레그램으로 전송한 옵션 목록 JSON (AWAITING_OPTION_SELECTION 상태에서 사용) */
    @Column(name = "pending_option_groups_json", columnDefinition = "TEXT")
    private String pendingOptionGroupsJson;

    /** 텔레그램에서 사용자가 선택한 옵션 값 (예: "블랙") */
    @Column(name = "selected_option_value", length = 200)
    private String selectedOptionValue;

    /** 텔레그램에서 수신한 로그인 아이디 */
    @Column(name = "pending_login_username", length = 300)
    private String pendingLoginUsername;

    /** 텔레그램에서 수신한 로그인 비밀번호 */
    @Column(name = "pending_login_password", length = 500)
    private String pendingLoginPassword;

    /** 외부 스토어 상세페이지에서 현재 진행 중인 스크린샷 기반 vision 단계 */
    @Enumerated(EnumType.STRING)
    @Column(name = "external_store_vision_stage", length = 30)
    private ExternalStoreVisionStage externalStoreVisionStage;

    /**
     * OPTION_PRESENCE 단계에서 옵션이 안 보일 때 스크롤 시도 횟수.
     * 상품 상세페이지에서 옵션/구매 버튼이 뷰포트 밖에 있을 수 있으므로
     * 최대 2회까지 스크롤 후 재캡처하여 옵션 존재 여부를 재확인한다.
     */
    @Column(name = "option_presence_scroll_count", nullable = false)
    private int optionPresenceScrollCount = 0;

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
            Long subscriptionId,
            String commandId,
            String assignedDeviceId,
            LocalDateTime assignedAt,
            AgentRunStatus status,
            Integer currentStepIndex,
            LocalDateTime approvalRequestedAt,
            String abortReason,
            Integer triggerPrice,
            String aiAgentPrivateKey,
            ExternalStoreVisionStage externalStoreVisionStage
    ) {
        this.runId = runId;
        this.userId = userId;
        this.subscriptionId = subscriptionId;
        this.commandId = commandId;
        this.assignedDeviceId = assignedDeviceId;
        this.assignedAt = assignedAt;
        this.status = status;
        this.currentStepIndex = currentStepIndex;
        this.approvalRequestedAt = approvalRequestedAt;
        this.abortReason = abortReason;
        this.triggerPrice = triggerPrice;
        this.aiAgentPrivateKey = aiAgentPrivateKey;
        this.externalStoreVisionStage = externalStoreVisionStage == null
                ? ExternalStoreVisionStage.NONE
                : externalStoreVisionStage;
    }

    /** 즉시 결제용 AgentRun 생성 (triggerPrice 불필요). */
    public static AgentRun createQueued(Long userId, String commandId) {
        return createQueued(userId, null, commandId);
    }

    /** subscriptionId를 함께 저장하는 큐 대기 Run 생성 */
    public static AgentRun createQueued(Long userId, Long subscriptionId, String commandId) {
        return AgentRun.builder()
                .runId(UUID.randomUUID().toString())
                .userId(userId)
                .subscriptionId(subscriptionId)
                .commandId(commandId)
                .status(AgentRunStatus.QUEUED)
                .currentStepIndex(0)
                .externalStoreVisionStage(ExternalStoreVisionStage.NONE)
                .build();
    }

    /**
     * 모니터링 트리거 후 결제용 AgentRun 생성.
     * <p>
     * triggerPrice를 저장해 CATALOG_NAVIGATOR가 올바른 기준가로 판매처를 탐색한다.
     *
     * @param userId       사용자 ID
     * @param commandId    연결된 CommandSession ID
     * @param triggerPrice 모니터링 조건 충족 시점의 실제 KRW 가격
     */
    public static AgentRun createQueuedWithTriggerPrice(Long userId, String commandId, Integer triggerPrice) {
        return createQueuedWithTriggerPrice(userId, null, commandId, triggerPrice, null);
    }

    public static AgentRun createQueuedWithTriggerPrice(Long userId, Long subscriptionId, String commandId, Integer triggerPrice) {
        return createQueuedWithTriggerPrice(userId, subscriptionId, commandId, triggerPrice, null);
    }

    /**
     * 모니터링 트리거 후 결제용 AgentRun 생성 (AI 에이전트 개인키 포함).
     * <p>
     * triggerPrice를 저장해 CATALOG_NAVIGATOR가 올바른 기준가로 판매처를 탐색한다.
     * aiAgentPrivateKey는 결제 페이지 도달 시 payment-topic 이벤트 발행에 사용된다.
     *
     * @param userId              사용자 ID
     * @param commandId           연결된 CommandSession ID
     * @param triggerPrice        모니터링 조건 충족 시점의 실제 KRW 가격
     * @param aiAgentPrivateKey   자동결제용 AI 에이전트 개인키
     */
    public static AgentRun createQueuedWithTriggerPrice(Long userId, String commandId, Integer triggerPrice, String aiAgentPrivateKey) {
        return createQueuedWithTriggerPrice(userId, null, commandId, triggerPrice, aiAgentPrivateKey);
    }

    public static AgentRun createQueuedWithTriggerPrice(Long userId, Long subscriptionId, String commandId, Integer triggerPrice, String aiAgentPrivateKey) {
        return AgentRun.builder()
                .runId(UUID.randomUUID().toString())
                .userId(userId)
                .subscriptionId(subscriptionId)
                .commandId(commandId)
                .status(AgentRunStatus.QUEUED)
                .currentStepIndex(0)
                .triggerPrice(triggerPrice)
                .aiAgentPrivateKey(aiAgentPrivateKey)
                .externalStoreVisionStage(ExternalStoreVisionStage.NONE)
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

    /**
     * 상품 옵션 선택 대기 상태로 전환한다.
     * 텔레그램으로 옵션 목록을 보낸 뒤 사용자 응답을 기다린다 (3분 타임아웃).
     *
     * @param now              현재 시각 (타임아웃 계산 기준)
     * @param optionGroupsJson 텔레그램으로 전송한 옵션 목록 JSON
     */
    public void awaitOptionSelection(LocalDateTime now, String optionGroupsJson) {
        requireStatus(AgentRunStatus.RUNNING, "옵션 선택 대기 전환");
        this.status = AgentRunStatus.AWAITING_OPTION_SELECTION;
        this.approvalRequestedAt = now;
        this.pendingOptionGroupsJson = optionGroupsJson;
        this.selectedOptionValue = null;
    }

    /**
     * 로그인 아이디/비밀번호 입력 대기 상태로 전환한다.
     * 텔레그램으로 자격증명을 요청한 뒤 사용자 응답을 기다린다.
     */
    public void awaitLoginCredentials(LocalDateTime now) {
        requireStatus(AgentRunStatus.RUNNING, "로그인 자격증명 대기 전환");
        this.status = AgentRunStatus.AWAITING_LOGIN_CREDENTIALS;
        this.approvalRequestedAt = now;
        this.pendingLoginUsername = null;
        this.pendingLoginPassword = null;
    }

    /**
     * 텔레그램에서 사용자가 옵션을 선택한 후 실행을 재개한다.
     *
     * @param selectedValue 사용자가 선택한 옵션 값 (예: "블랙")
     */
    public void resolveOptionSelection(String selectedValue) {
        requireStatus(AgentRunStatus.AWAITING_OPTION_SELECTION, "옵션 선택 완료");
        this.status = AgentRunStatus.RUNNING;
        this.selectedOptionValue = selectedValue;
        this.approvalRequestedAt = null;
    }

    /**
     * 텔레그램에서 사용자가 로그인 자격증명을 입력한 후 실행을 재개한다.
     */
    public void resolveLoginCredentials(String username, String password) {
        requireStatus(AgentRunStatus.AWAITING_LOGIN_CREDENTIALS, "로그인 자격증명 입력 완료");
        this.status = AgentRunStatus.RUNNING;
        this.pendingLoginUsername = username;
        this.pendingLoginPassword = password;
        this.approvalRequestedAt = null;
    }

    /** 텔레그램으로 받은 옵션 값이 실제 화면에서 소비되었을 때만 초기화한다. */
    public void clearSelectedOptionValue() {
        this.selectedOptionValue = null;
    }

    public boolean hasPendingLoginCredentials() {
        return pendingLoginUsername != null && !pendingLoginUsername.isBlank()
                && pendingLoginPassword != null && !pendingLoginPassword.isBlank();
    }

    public void clearPendingLoginCredentials() {
        this.pendingLoginUsername = null;
        this.pendingLoginPassword = null;
    }

    /** 외부 스토어 vision 단계를 갱신한다. */
    public void updateExternalStoreVisionStage(ExternalStoreVisionStage stage) {
        this.externalStoreVisionStage = stage == null ? ExternalStoreVisionStage.NONE : stage;
    }

    /** OPTION_PRESENCE 스크롤 횟수를 1 증가시키고 현재 값을 반환한다. */
    public int incrementOptionPresenceScrollCount() {
        return ++this.optionPresenceScrollCount;
    }

    public int getOptionPresenceScrollCount() {
        return this.optionPresenceScrollCount;
    }

    public void resetOptionPresenceScrollCount() {
        this.optionPresenceScrollCount = 0;
    }

    /** 옵션 선택 타임아웃 시 호출한다 (3분 초과). */
    public void expireOptionSelection() {
        requireStatus(AgentRunStatus.AWAITING_OPTION_SELECTION, "옵션 선택 만료 처리");
        this.status = AgentRunStatus.ABORTED;
        this.abortReason = "옵션 선택 시간 초과 (3분)";
    }

    /** 로그인 자격증명 입력 타임아웃 시 호출한다 (3분 초과). */
    public void expireLoginCredentials() {
        requireStatus(AgentRunStatus.AWAITING_LOGIN_CREDENTIALS, "로그인 자격증명 만료 처리");
        this.status = AgentRunStatus.ABORTED;
        this.abortReason = "로그인 정보 입력 시간 초과 (3분)";
    }

    public void interrupt() {
        if (!Set.of(AgentRunStatus.ASSIGNED, AgentRunStatus.RUNNING, AgentRunStatus.AWAITING_APPROVAL, AgentRunStatus.AWAITING_OPTION_SELECTION, AgentRunStatus.AWAITING_LOGIN_CREDENTIALS).contains(this.status)) {
            throw new IllegalStateException("중단 감지는 ASSIGNED/RUNNING/AWAITING_APPROVAL/AWAITING_OPTION_SELECTION/AWAITING_LOGIN_CREDENTIALS 상태에서만 가능합니다. 현재: " + this.status);
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
