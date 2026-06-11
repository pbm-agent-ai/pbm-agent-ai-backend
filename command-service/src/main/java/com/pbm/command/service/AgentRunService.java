package com.pbm.command.service;

import com.pbm.command.config.BrowserAgentTokenUtil;
import com.pbm.command.domain.AgentRun;
import com.pbm.command.domain.AgentRunStatus;
import com.pbm.command.domain.BrowserDevice;
import com.pbm.command.domain.CommandSessionStatus;
import com.pbm.command.domain.ExternalStoreVisionStage;
import com.pbm.command.dto.event.CheckoutPaymentEvent;
import com.pbm.command.dto.event.CheckoutPaymentEventPayload;
import com.pbm.command.dto.response.AgentRunCreatedResponse;
import com.pbm.command.dto.response.AssignedRunResponse;
import com.pbm.command.dto.response.AgentRunResponse;
import com.pbm.command.dto.request.AgentRunActionResultRequest;
import com.pbm.command.dto.request.AgentRunStepRequest;
import com.pbm.command.dto.request.PageSnapshotRequest;
import com.pbm.command.dto.response.AgentRunStepResponse;
import com.pbm.command.exception.AgentRunAccessDeniedException;
import com.pbm.command.exception.AgentRunConflictException;
import com.pbm.command.exception.AgentRunNotFoundException;
import com.pbm.command.exception.BrowserDeviceNotFoundException;
import com.pbm.command.exception.CommandSessionNotFoundException;
import com.pbm.command.domain.ActionExecutionStatus;
import com.pbm.command.domain.BrowserActionType;
import com.pbm.command.domain.BrowserDeviceStatus;
import com.pbm.command.domain.CommandSession;
import com.pbm.command.dto.event.OptionSelectionRequestEvent;
import com.pbm.command.dto.event.OptionSelectionRequestPayload;
import com.pbm.command.dto.event.OptionSelectionRequestPayload.OptionGroupPayload;
import com.pbm.command.dto.request.OptionGroupRequest;
import com.pbm.command.dto.response.ActionInstructionResponse;
import com.pbm.command.publisher.CheckoutPaymentEventPublisher;
import com.pbm.command.publisher.OptionSelectionEventPublisher;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pbm.command.repository.AgentRunRepository;
import com.pbm.command.repository.BrowserDeviceRepository;
import com.pbm.command.repository.CommandSessionRepository;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * AgentRun 관리 서비스.
 *
 * 역할: 웹 앱이 생성한 자동화 실행 요청을 AgentRun 엔티티로 관리하고,
 *       디바이스 할당/복구/승인/중단 같은 상태 전이를 수행한다.
 * 연관: AgentRunRepository, CommandSessionRepository, BrowserAgentTokenUtil.
 */
@Slf4j
@Service
@Transactional(readOnly = true)
public class AgentRunService {

    private static final long ONLINE_DEVICE_THRESHOLD_SECONDS = 90L;
    private static final String ASSIGNED_AGENT_TOKEN_KEY_PREFIX = "agent-run:assigned-token:";

    private static final Set<AgentRunStatus> ACTIVE_STATUSES = Set.of(
            AgentRunStatus.QUEUED,
            AgentRunStatus.ASSIGNED,
            AgentRunStatus.RUNNING,
            AgentRunStatus.AWAITING_APPROVAL,
            AgentRunStatus.INTERRUPTED,
            AgentRunStatus.RECOVERING,
            AgentRunStatus.AWAITING_OPTION_SELECTION
    );

    private static final Set<AgentRunStatus> INTERRUPTIBLE_STATUSES = Set.of(
            AgentRunStatus.ASSIGNED,
            AgentRunStatus.RUNNING,
            AgentRunStatus.AWAITING_APPROVAL
    );

    private final AgentRunRepository agentRunRepository;
    private final BrowserDeviceRepository browserDeviceRepository;
    private final CommandSessionRepository commandSessionRepository;
    private final BrowserAgentTokenUtil browserAgentTokenUtil;
    private final StringRedisTemplate stringRedisTemplate;
    private final AgentStepPlannerService agentStepPlannerService;
    private final CheckoutPaymentEventPublisher checkoutPaymentEventPublisher;
    private final OptionSelectionEventPublisher optionSelectionEventPublisher;
    private final ObjectMapper objectMapper;

    public AgentRunService(
            AgentRunRepository agentRunRepository,
            BrowserDeviceRepository browserDeviceRepository,
            CommandSessionRepository commandSessionRepository,
            BrowserAgentTokenUtil browserAgentTokenUtil,
            StringRedisTemplate stringRedisTemplate,
            AgentStepPlannerService agentStepPlannerService,
            CheckoutPaymentEventPublisher checkoutPaymentEventPublisher,
            OptionSelectionEventPublisher optionSelectionEventPublisher,
            ObjectMapper objectMapper
    ) {
        this.agentRunRepository = agentRunRepository;
        this.browserDeviceRepository = browserDeviceRepository;
        this.commandSessionRepository = commandSessionRepository;
        this.browserAgentTokenUtil = browserAgentTokenUtil;
        this.stringRedisTemplate = stringRedisTemplate;
        this.agentStepPlannerService = agentStepPlannerService;
        this.checkoutPaymentEventPublisher = checkoutPaymentEventPublisher;
        this.optionSelectionEventPublisher = optionSelectionEventPublisher;
        this.objectMapper = objectMapper;
    }

    /**
     * 웹 앱 요청으로 새 AgentRun을 생성한다.
     * <p>
     * INTERRUPTED 상태의 기존 run은 새 run 생성 시 자동으로 ABORTED 처리한다.
     * 이는 디바이스가 오프라인 상태에서 새로운 명령이 들어온 경우를 처리한다.
     */
    @Transactional
    public AgentRunCreatedResponse createRun(Long userId, String commandId) {
        return createRun(userId, null, commandId, null, null);
    }

    /**
     * 모니터링 트리거 후 결제용 AgentRun을 생성한다.
     * <p>
     * triggerPrice를 저장해 CATALOG_NAVIGATOR가 lprice(선택 당시 가격) 대신
     * 실제 조건 충족 가격을 기준가로 사용할 수 있게 한다.
     *
     * @param userId       사용자 ID
     * @param commandId    연결된 CommandSession ID
     * @param triggerPrice 모니터링 조건 충족 시점의 실제 KRW 가격 (즉시 결제 시 null)
     */
    @Transactional
    public AgentRunCreatedResponse createRun(Long userId, String commandId, Integer triggerPrice) {
        return createRun(userId, null, commandId, triggerPrice, null);
    }

    @Transactional
    public AgentRunCreatedResponse createRun(Long userId, Long subscriptionId, String commandId, Integer triggerPrice) {
        return createRun(userId, subscriptionId, commandId, triggerPrice, null);
    }

    /**
     * 모니터링 트리거 후 결제용 AgentRun을 생성한다 (AI 에이전트 개인키 포함).
     * <p>
     * triggerPrice를 저장해 CATALOG_NAVIGATOR가 lprice(선택 당시 가격) 대신
     * 실제 조건 충족 가격을 기준가로 사용할 수 있게 한다.
     * aiAgentPrivateKey는 결제 페이지 도달 시 payment-topic 이벤트 발행에 사용된다.
     *
     * @param userId              사용자 ID
     * @param commandId           연결된 CommandSession ID
     * @param triggerPrice        모니터링 조건 충족 시점의 실제 KRW 가격 (즉시 결제 시 null)
     * @param aiAgentPrivateKey   자동결제용 AI 에이전트 개인키
     */
    @Transactional
    public AgentRunCreatedResponse createRun(Long userId, String commandId, Integer triggerPrice, String aiAgentPrivateKey) {
        return createRun(userId, null, commandId, triggerPrice, aiAgentPrivateKey);
    }

    @Transactional
    public AgentRunCreatedResponse createRun(Long userId, Long subscriptionId, String commandId, Integer triggerPrice, String aiAgentPrivateKey) {
        commandSessionRepository.findByCommandId(commandId)
                .orElseThrow(() -> new CommandSessionNotFoundException("세션을 찾을 수 없습니다. commandId: " + commandId));

        // INTERRUPTED 상태의 기존 run을 ABORTED로 전환 (새 run으로 대체됨)
        List<AgentRun> interruptedRuns = agentRunRepository.findAllByUserIdAndStatusIn(
                userId, Set.of(AgentRunStatus.INTERRUPTED)
        );
        if (!interruptedRuns.isEmpty()) {
            log.info("INTERRUPTED 상태의 기존 AgentRun {}건을 ABORTED로 전환합니다. userId={}", interruptedRuns.size(), userId);
            interruptedRuns.forEach(run -> run.abort("SUPERSEDED_BY_NEW_RUN"));
        }

        // INTERRUPTED 외의 활성 run이 있으면 모두 ABORTED 처리 후 새 run 허용
        // 새 명령을 내렸다는 것은 이전 run을 포기한다는 의미이므로 충돌 예외 대신 강제 종료
        Set<AgentRunStatus> activeExcludingInterrupted = Set.of(
                AgentRunStatus.QUEUED,
                AgentRunStatus.ASSIGNED,
                AgentRunStatus.RUNNING,
                AgentRunStatus.AWAITING_APPROVAL,
                AgentRunStatus.RECOVERING
        );
        List<AgentRun> activeRuns = agentRunRepository.findAllByUserIdAndStatusIn(userId, activeExcludingInterrupted);
        if (!activeRuns.isEmpty()) {
            log.warn("기존 활성 AgentRun {}건을 ABORTED 처리합니다 (새 run으로 대체). userId={}",
                    activeRuns.size(), userId);
            activeRuns.forEach(run -> run.abort("SUPERSEDED_BY_NEW_RUN"));
        }

        // triggerPrice가 있으면 모니터링 트리거 후 결제용 AgentRun 생성 (CATALOG_NAVIGATOR에 triggerPrice 전달됨)
        AgentRun agentRun = triggerPrice != null
                ? AgentRun.createQueuedWithTriggerPrice(userId, subscriptionId, commandId, triggerPrice, aiAgentPrivateKey)
                : AgentRun.createQueued(userId, subscriptionId, commandId);
        assignLatestOnlineDeviceIfPossible(agentRun);   // 온라인 디바이스가 있으면 바로 할당, 없으면 그냥 패스함

        AgentRun saved = agentRunRepository.save(agentRun);
        log.info("AgentRun 생성 완료 - runId: {}, status: {}, assignedDevice: {}, subscriptionId: {}, triggerPrice: {}",
                saved.getRunId(), saved.getStatus(), saved.getAssignedDeviceId(), subscriptionId, triggerPrice);
        return new AgentRunCreatedResponse(
                saved.getRunId(),
                saved.getCommandId(),
                saved.getStatus().name(),
                saved.getCreatedAt()
        );
    }

    /**
     * 특정 디바이스에 대기 중으로 할당된 run 1건을 조회한다.
     * 크롬 익스텐션측이 할 일이 있는지 체크하고자 폴링할때 호출
     */
    @Transactional
    public AssignedRunResponse getPendingRunForDevice(String deviceId) {
        AgentRun assignedRun = findAssignedRun(deviceId)
                .orElseGet(() -> assignQueuedRunToDevice(deviceId).orElse(null));

        if (assignedRun == null) {
            return null;
        }

        CommandSession commandSession = commandSessionRepository.findByCommandId(assignedRun.getCommandId())
                .orElseThrow(() -> new CommandSessionNotFoundException(
                        "세션을 찾을 수 없습니다. commandId: " + assignedRun.getCommandId()));

        return new AssignedRunResponse(
                assignedRun.getRunId(),
                getOrCreateAssignedAgentToken(assignedRun, deviceId),
                assignedRun.getCommandId(),
                commandSession.getPlatform()
        );
    }

    /**
     * runId로 AgentRun 상세를 조회한다.
     */
    public AgentRunResponse getRun(String runId) {
        return AgentRunResponse.from(getRunEntity(runId));
    }

    /**
     * 특정 디바이스에 할당된 활성 run 목록을 반환한다.
     * 사이드패널의 "Run 현황" 화면에서 조회/중단 용도로 사용된다.
     */
    public List<AgentRunResponse> getActiveRunsForDevice(String deviceId) {
        return agentRunRepository
                .findAllByAssignedDeviceIdAndStatusInOrderByCreatedAtDesc(deviceId, ACTIVE_STATUSES)
                .stream()
                .map(AgentRunResponse::from)
                .toList();
    }

    /**
     * 특정 디바이스에 run을 할당하고 agent token을 발급한다.
     */
    @Transactional
    public String assignRun(String runId, String deviceId) {
        AgentRun run = getRunEntity(runId);
        return assignRunToDevice(run, deviceId, LocalDateTime.now());
    }

    /**
     * run을 시작 상태로 전환한다.
     */
    @Transactional
    public AgentRunResponse startRun(String runId, String deviceId) {
        AgentRun run = getRunEntity(runId);
        validateAssignedDevice(run, deviceId);
        run.start();
        return AgentRunResponse.from(run);
    }

    /**
     * 중단된 run을 복구 상태로 전환하고 새 agent token을 발급한다.
     */
    @Transactional
    public String recover(String runId, String deviceId) {
        AgentRun run = getRunEntity(runId);
        validateAssignedDevice(run, deviceId);
        run.recover();
        return browserAgentTokenUtil.generateAgentToken(run.getUserId(), run.getRunId(), deviceId);
    }

    /**
     * 복구 완료 후 다시 RUNNING으로 전환한다.
     */
    @Transactional
    public AgentRunResponse completeRecovery(String runId, String deviceId) {
        AgentRun run = getRunEntity(runId);
        validateAssignedDevice(run, deviceId);
        run.completeRecovery();
        return AgentRunResponse.from(run);
    }

    /**
     * 승인 결과를 반영한다.
     */
    @Transactional
    public AgentRunResponse approve(String runId, Long userId, boolean approved) {
        AgentRun run = getRunEntity(runId);
        validateOwner(run, userId);
        if (approved) {
            run.approveAndResume();
        } else {
            run.reject("APPROVAL_REJECTED");
        }
        return AgentRunResponse.from(run);
    }

    /**
     * run을 중단한다.
     */
    @Transactional
    public AgentRunResponse abort(String runId, String reason, String deviceId, Long userId) {
        AgentRun run = getRunEntity(runId);

        if (deviceId == null && userId == null) {
            throw new AgentRunAccessDeniedException("중단 요청에 인증 정보가 없습니다. runId=" + runId);
        }

        if (deviceId != null) {
            validateAssignedDevice(run, deviceId);
        }
        if (userId != null) {
            validateOwner(run, userId);
        }

        run.abort(reason == null || reason.isBlank() ? "USER_CANCELLED" : reason);
        return AgentRunResponse.from(run);
    }

    /**
     * 승인 대기 만료 처리를 수행한다.
     */
    @Transactional
    public int expireApprovalBefore(LocalDateTime threshold) {
        List<AgentRun> targets = agentRunRepository.findAllByStatusAndApprovalRequestedAtBefore(
                AgentRunStatus.AWAITING_APPROVAL,
                threshold
        );
        targets.forEach(AgentRun::expireApproval);
        return targets.size();
    }

    /**
     * 옵션 선택 대기 시간이 초과된 run을 ABORTED로 전환한다.
     *
     * @param threshold 이 시각 이전에 요청된 옵션 선택은 만료 처리
     * @return 만료 처리된 run 개수
     */
    @Transactional
    public int expireOptionSelectionBefore(LocalDateTime threshold) {
        List<AgentRun> targets = agentRunRepository.findAllByStatusAndApprovalRequestedAtBefore(
                AgentRunStatus.AWAITING_OPTION_SELECTION,
                threshold
        );
        targets.forEach(AgentRun::expireOptionSelection);
        return targets.size();
    }

    /**
     * 오프라인 디바이스에 할당된 run을 INTERRUPTED로 전환한다.
     *
     * @param deviceIds 오프라인으로 판정된 디바이스 ID 목록
     * @return INTERRUPTED로 전환된 run 개수
     */
    @Transactional
    public int interruptRunsAssignedToDevices(Set<String> deviceIds) {
        if (deviceIds == null || deviceIds.isEmpty()) {
            return 0;
        }

        List<AgentRun> targets = agentRunRepository.findAllByAssignedDeviceIdInAndStatusIn(deviceIds, INTERRUPTIBLE_STATUSES);
        targets.forEach(AgentRun::interrupt);
        return targets.size();
    }

    /**
     * Extension의 step 결과를 반영하고 다음 action instruction을 반환한다.
     * 익스텐션이 "이번 액션 했어. 다음엔 뭐해?"하고 호출
     */
    @Transactional
    public AgentRunStepResponse processStep(String runId, String deviceId, AgentRunStepRequest request) {
        AgentRun run = getRunEntity(runId);
        validateAssignedDevice(run, deviceId);

        if (request.stepIndex() == null) {
            throw new AgentRunConflictException("stepIndex는 필수입니다. runId=" + runId);
        }

        // applyPreviousActionResult를 통해 run.currentStepIndex + 1 (다음 스텝으로 전진)
        if (request.previousActionResult() != null) {
            applyPreviousActionResult(run, request.previousActionResult());
        }

        // index르 바꾼 뒤 서버와 익스텐션의 스텝이 일치하는지 확인
        if (!run.getCurrentStepIndex().equals(request.stepIndex())) {
            throw new AgentRunConflictException(
                    "요청 stepIndex가 현재 Run 상태와 일치하지 않습니다. current=" + run.getCurrentStepIndex()
                            + ", requested=" + request.stepIndex()
            );
        }

        // 이미 끝난 run이면 다음 액션 없이 상태만 반환
        if (Set.of(AgentRunStatus.ABORTED, AgentRunStatus.FAILED, AgentRunStatus.COMPLETED, AgentRunStatus.APPROVAL_EXPIRED)
                .contains(run.getStatus())) {
            return new AgentRunStepResponse(run.getRunId(), run.getStatus(), run.getCurrentStepIndex(), null);
        }

        // 사용자가 텔레그램으로 선택한 옵션이 현재 화면에서 실제로 선택된 것이 확인되면
        // selectedOptionValue를 초기화한다. 아직 보이지 않으면 유지해서 다음 step에서도
        // Smartstore 옵션 선택/vision fallback이 이어지도록 한다.
        clearSelectedOptionValueIfResolved(run, request.snapshot(), request.previousActionResult());

        // 옵션 선택 대기 상태: 텔레그램 응답이 아직 안 왔으면 WAIT 반환
        if (run.getStatus() == AgentRunStatus.AWAITING_OPTION_SELECTION) {
            if (run.getSelectedOptionValue() == null) {
                log.info("[AgentRunService] 옵션 선택 대기 중 - runId={}", run.getRunId());
                return new AgentRunStepResponse(run.getRunId(), run.getStatus(), run.getCurrentStepIndex(), null);
            }
            // 텔레그램 응답이 도착함 → RUNNING으로 이미 전환됨 (OptionSelectionResponseConsumer에서 처리)
            // 여기에 올 수 없지만 방어 코드
            log.info("[AgentRunService] 옵션 선택 완료, 실행 재개 - runId={}, selected={}", run.getRunId(), run.getSelectedOptionValue());
        }

        CommandSession commandSession = commandSessionRepository.findByCommandId(run.getCommandId())
                .orElseThrow(() -> new CommandSessionNotFoundException("세션을 찾을 수 없습니다. commandId: " + run.getCommandId()));

        // triggerPrice: 모니터링 트리거 후 결제 시 실제 조건 충족 가격 (즉시 결제 시 null)
        // CATALOG_NAVIGATOR가 lprice(선택 당시 가격) 대신 이 가격을 기준가로 사용한다.
        var instruction = agentStepPlannerService.planNextAction(
                run.getRunId(),
                run.getCurrentStepIndex(),
                commandSession,
                request.snapshot(),     // 현재 브라우저 화면 상태
                request.previousActionResult(),
                run.getTriggerPrice(),
                run.getSelectedOptionValue(),
                run.getExternalStoreVisionStage(),
                run.getOptionPresenceScrollCount()
        );

        instruction = handleExternalStoreVisionTransitions(run, commandSession, instruction);

        // 옵션 매칭 실패: 텔레그램 옵션 요청 Kafka 발행 + 상태 전환
        // planNextAction이 null을 반환하거나, 옵션이 존재하는데 매칭이 안 된 경우
        // (내부 스토어: hasUnmatchedOptions 체크, 외부 스토어: buildExternalStoreInstruction이 null 반환)
        if (run.getStatus() == AgentRunStatus.RUNNING
                && request.snapshot() != null
                && request.snapshot().optionGroups() != null
                && !request.snapshot().optionGroups().isEmpty()
                && run.getSelectedOptionValue() == null
                && (instruction == null || agentStepPlannerService.hasUnmatchedOptions(commandSession, request.snapshot(), run.getSelectedOptionValue()))) {

            log.info("[AgentRunService] 옵션 매칭 실패 → 텔레그램 옵션 요청 발행 - runId={}", run.getRunId());
            publishOptionSelectionRequest(run, commandSession, request.snapshot().optionGroups());
            return new AgentRunStepResponse(run.getRunId(), run.getStatus(), run.getCurrentStepIndex(), null);
        }

        // planNextAction이 null을 반환했지만 옵션 요청 조건에도 해당하지 않는 경우 (방어 코드)
        if (instruction == null) {
            log.warn("[AgentRunService] planNextAction이 null 반환, 옵션 요청 조건 미해당 → WAIT - runId={}", run.getRunId());
            return new AgentRunStepResponse(run.getRunId(), run.getStatus(), run.getCurrentStepIndex(), null);
        }

        // 승인이 필요한 액션일 경우
        if (instruction.action() == BrowserActionType.AWAIT_APPROVAL && run.getStatus() == AgentRunStatus.RUNNING) {
            run.awaitApproval(LocalDateTime.now());
        }

        // 모든 작업 완료일 경우
        if (instruction.action() == BrowserActionType.COMPLETE && run.getStatus() == AgentRunStatus.RUNNING) {
            // 결제 페이지에서 COMPLETE가 반환된 경우 → PBM 토큰 차감 이벤트 발행
            String currentUrl = request.snapshot() != null ? request.snapshot().currentUrl() : null;
            if (isCheckoutUrl(currentUrl)) {
                log.info("[AgentRunService] 결제 페이지 도달 감지 → 토큰 차감 이벤트 발행 - runId={}, url={}", run.getRunId(), currentUrl);
                commandSession.toCheckoutReached();
                commandSessionRepository.save(commandSession);
                publishCheckoutPaymentEvent(run, commandSession, currentUrl);
            }
            run.complete();
        }

        return new AgentRunStepResponse(
                run.getRunId(),
                run.getStatus(),
                run.getCurrentStepIndex(),
                Set.of(AgentRunStatus.COMPLETED, AgentRunStatus.ABORTED, AgentRunStatus.FAILED, AgentRunStatus.APPROVAL_EXPIRED).contains(run.getStatus())
                        ? null          // 종료 상태면 다음 액션 없음
                        : instruction   // 진행 중이면 다음 액션 전달
        );
    }

    AgentRun getRunEntity(String runId) {
        return agentRunRepository.findByRunId(runId)
                .orElseThrow(() -> new AgentRunNotFoundException("AgentRun을 찾을 수 없습니다. runId=" + runId));
    }

    private void validateAssignedDevice(AgentRun run, String deviceId) {
        if (run.getAssignedDeviceId() == null || !run.getAssignedDeviceId().equals(deviceId)) {
            throw new AgentRunAccessDeniedException("이 Run은 다른 디바이스에 할당되어 있습니다. runId=" + run.getRunId());
        }
    }

    private void validateOwner(AgentRun run, Long userId) {
        if (!run.getUserId().equals(userId)) {
            throw new AgentRunAccessDeniedException("이 Run에 대한 접근 권한이 없습니다. runId=" + run.getRunId());
        }
    }

    private void applyPreviousActionResult(AgentRun run, AgentRunActionResultRequest previousActionResult) {
        if (!run.getCurrentStepIndex().equals(previousActionResult.stepIndex())) {
            throw new AgentRunConflictException(
                    "stepIndex가 현재 Run 상태와 일치하지 않습니다. current=" + run.getCurrentStepIndex()
                            + ", requested=" + previousActionResult.stepIndex()
            );
        }

        if (previousActionResult.status() == ActionExecutionStatus.SUCCESS) {
            run.updateCurrentStepIndex(run.getCurrentStepIndex() + 1);
            return;
        }

        if (previousActionResult.status() == ActionExecutionStatus.SKIPPED) {
            return;
        }

        if (previousActionResult.status() == ActionExecutionStatus.FAILURE
                && previousActionResult.errorCode() != null
                && Set.of(
                com.pbm.command.domain.ActionErrorCode.APPROVAL_REJECTED,
                com.pbm.command.domain.ActionErrorCode.APPROVAL_EXPIRED,
                com.pbm.command.domain.ActionErrorCode.UNSUPPORTED_PAGE_STATE
        ).contains(previousActionResult.errorCode())) {
            run.fail(previousActionResult.errorCode().name());
        }
    }

    private void clearSelectedOptionValueIfResolved(
            AgentRun run,
            PageSnapshotRequest snapshot,
            AgentRunActionResultRequest previousActionResult
    ) {
        String selectedOptionValue = run.getSelectedOptionValue();
        if (selectedOptionValue == null || selectedOptionValue.isBlank()) {
            return;
        }

        boolean resolved = false;
        String normalizedSelected = normalize(selectedOptionValue);

        if (snapshot != null && snapshot.optionGroups() != null && !snapshot.optionGroups().isEmpty()) {
            resolved = snapshot.optionGroups().stream()
                    .filter(optionGroup -> optionGroup.selectedOption() != null && !optionGroup.selectedOption().isBlank())
                    .anyMatch(optionGroup -> normalize(optionGroup.selectedOption()).contains(normalizedSelected)
                            || normalizedSelected.contains(normalize(optionGroup.selectedOption())));
        } else if (previousActionResult != null
                && previousActionResult.status() == ActionExecutionStatus.SUCCESS
                && previousActionResult.action() == BrowserActionType.CLICK) {
            resolved = true;
        }

        if (resolved) {
            run.clearSelectedOptionValue();
            log.info("[AgentRunService] 텔레그램 옵션이 화면에서 확인되어 selectedOptionValue 초기화 - runId={}, selectedOptionValue={}",
                    run.getRunId(), selectedOptionValue);
        }
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        return value.replaceAll("\\s+", "").toLowerCase();
    }

    private void assignLatestOnlineDeviceIfPossible(AgentRun agentRun) {
        findLatestOnlineDevice(agentRun.getUserId())
                .ifPresent(browserDevice -> assignRunToDevice(agentRun, browserDevice.getDeviceId(), LocalDateTime.now()));
    }

    private Optional<BrowserDevice> findLatestOnlineDevice(Long userId) {
        return browserDeviceRepository.findFirstByUserIdAndLastSeenAtAfterOrderByLastSeenAtDesc(
                userId,
                LocalDateTime.now().minusSeconds(ONLINE_DEVICE_THRESHOLD_SECONDS)
        );
    }

    private Optional<AgentRun> findAssignedRun(String deviceId) {
        return agentRunRepository.findFirstByAssignedDeviceIdAndStatusOrderByAssignedAtAsc(
                deviceId,
                AgentRunStatus.ASSIGNED
        );
    }

    private Optional<AgentRun> assignQueuedRunToDevice(String deviceId) {
        BrowserDevice browserDevice = browserDeviceRepository.findByDeviceId(deviceId)
                .orElseThrow(() -> new BrowserDeviceNotFoundException(
                        "브라우저 디바이스를 찾을 수 없습니다. deviceId=" + deviceId
                ));

        if (browserDevice.getLastSeenAt().isBefore(LocalDateTime.now().minusSeconds(ONLINE_DEVICE_THRESHOLD_SECONDS))) {
            return Optional.empty();
        }

        return agentRunRepository.findFirstByUserIdAndStatusOrderByCreatedAtAsc(
                        browserDevice.getUserId(),
                        AgentRunStatus.QUEUED
                )
                .map(agentRun -> {
                    assignRunToDevice(agentRun, deviceId, LocalDateTime.now());
                    return agentRun;
                });
    }

    private String assignRunToDevice(AgentRun agentRun, String deviceId, LocalDateTime assignedAt) {
        agentRun.assignTo(deviceId, assignedAt);
        return issueAssignedAgentToken(agentRun, deviceId);
    }

    private String getOrCreateAssignedAgentToken(AgentRun agentRun, String deviceId) {
        ValueOperations<String, String> valueOperations = stringRedisTemplate.opsForValue();
        String cachedToken = valueOperations.get(buildAssignedAgentTokenKey(agentRun.getRunId()));

        if (cachedToken != null && !cachedToken.isBlank()) {
            return cachedToken;
        }

        return issueAssignedAgentToken(agentRun, deviceId);
    }

    private String issueAssignedAgentToken(AgentRun agentRun, String deviceId) {
        String agentToken = browserAgentTokenUtil.generateAgentToken(agentRun.getUserId(), agentRun.getRunId(), deviceId);
        stringRedisTemplate.opsForValue().set(
                buildAssignedAgentTokenKey(agentRun.getRunId()),
                agentToken,
                Duration.ofMillis(browserAgentTokenUtil.getAgentTokenExpiration())
        );
        return agentToken;
    }

    private String buildAssignedAgentTokenKey(String runId) {
        return ASSIGNED_AGENT_TOKEN_KEY_PREFIX + runId;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 결제 페이지 감지 + PBM 토큰 차감 이벤트 발행
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * URL이 최종 결제 페이지인지 확인한다.
     * 네이버: orders.pay.naver.com/ordersheet/
     * 알리익스프레스: aliexpress.com/p/trade/confirm
     */
    private boolean isCheckoutUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        String lower = url.toLowerCase();
        return lower.contains("orders.pay.naver.com/ordersheet")
                || lower.contains("aliexpress.com/p/trade/confirm")
                || lower.contains("checkout.coupang.com")
                || lower.contains("cart.coupang.com")
                || lower.contains("order.auction.co.kr")
                || lower.contains("order.gmarket.co.kr")
                || lower.contains("order.11st.co.kr");
    }

    /**
     * 결제 페이지 도달 시 payment-topic으로 PBM 토큰 차감 요청 이벤트를 발행한다.
     * triggerPrice가 있으면 해당 가격으로, 없으면 0으로 발행한다.
     * (금액이 0인 경우 payment-service에서 별도 처리 필요)
     * aiAgentPrivateKey는 AgentRun에 저장된 값을 사용한다.
     */
    private void publishCheckoutPaymentEvent(AgentRun run, CommandSession commandSession, String checkoutUrl) {
        // 결제 금액: triggerPrice(모니터링 조건 충족가) 우선, 없으면 0
        int amount = run.getTriggerPrice() != null ? run.getTriggerPrice() : 0;

        // 상품명: 세션의 원본 명령어를 사용 (상품명 추출 로직은 추후 개선 가능)
        String productName = commandSession.getOriginalCommand();

        // AI 에이전트 개인키: AgentRun에 저장된 값 사용 (모니터링 조건 충족 시 price-service에서 전달받음)
        String aiAgentPrivateKey = run.getAiAgentPrivateKey();
        Long subscriptionId = run.getSubscriptionId();

        CheckoutPaymentEventPayload payload = new CheckoutPaymentEventPayload(
                run.getUserId(),
                subscriptionId,
                productName,
                checkoutUrl,
                amount,
                "KRW",
                aiAgentPrivateKey,  // AgentRun에 저장된 AI 에이전트 개인키
                null                // recipientAddress: 기본값 사용
        );

        CheckoutPaymentEvent event = new CheckoutPaymentEvent(
                UUID.randomUUID().toString(),
                "CHECKOUT_PAYMENT_REQUESTED",
                Instant.now(),
                "command-service",
                payload
        );

        checkoutPaymentEventPublisher.publish(event);
        log.info("결제 이벤트 발행 완료 - runId={}, userId={}, subscriptionId={}, amount={}, aiAgentPrivateKey={}",
                run.getRunId(), run.getUserId(), subscriptionId, amount, aiAgentPrivateKey != null ? "있음" : "없음");
    }

    /**
     * 옵션 매칭 실패 시 텔레그램 옵션 선택 요청 Kafka 이벤트를 발행하고,
     * AgentRun 상태를 AWAITING_OPTION_SELECTION으로 전환한다.
     */
    private void publishOptionSelectionRequest(AgentRun run, CommandSession commandSession,
                                                List<OptionGroupRequest> optionGroups) {
        // optionGroups → JSON 저장 (AgentRun에 보관)
        String optionGroupsJson;
        try {
            optionGroupsJson = objectMapper.writeValueAsString(optionGroups);
        } catch (JsonProcessingException e) {
            log.error("옵션 그룹 JSON 직렬화 실패 - runId={}", run.getRunId(), e);
            return;
        }

        // AgentRun 상태 전환
        run.awaitOptionSelection(LocalDateTime.now(), optionGroupsJson);

        // Kafka 이벤트 구성
        List<OptionGroupPayload> payloadGroups = optionGroups.stream()
                .map(og -> new OptionGroupPayload(og.groupName(), og.options()))
                .toList();

        OptionSelectionRequestPayload payload = new OptionSelectionRequestPayload(
                run.getUserId(),
                run.getRunId(),
                commandSession.getOriginalCommand(),
                payloadGroups
        );

        OptionSelectionRequestEvent event = new OptionSelectionRequestEvent(
                UUID.randomUUID().toString(),
                "OPTION_SELECTION_REQUESTED",
                Instant.now(),
                "command-service",
                payload
        );

        optionSelectionEventPublisher.publish(event);
        log.info("옵션 선택 요청 이벤트 발행 완료 - runId={}, userId={}, groups={}",
                run.getRunId(), run.getUserId(), optionGroups.size());
    }

    /** 외부 스토어 스크린샷 기반 vision 단계 전이를 처리한다. */
    @SuppressWarnings("unchecked")
    private ActionInstructionResponse handleExternalStoreVisionTransitions(
            AgentRun run,
            CommandSession commandSession,
            ActionInstructionResponse instruction
    ) {
        if (instruction == null) {
            return null;
        }

        ExternalStoreVisionStage stage = run.getExternalStoreVisionStage() == null
                ? ExternalStoreVisionStage.NONE
                : run.getExternalStoreVisionStage();

        if (instruction.action() == BrowserActionType.USE_TOOL
                && instruction.toolRequest() != null
                && "CAPTURE_VISIBLE_TAB".equals(instruction.toolRequest().name())) {
            Object nextStage = instruction.toolRequest().input().get(AgentStepPlannerService.NEXT_EXTERNAL_VISION_STAGE_KEY);
            if (nextStage instanceof String nextStageName) {
                run.updateExternalStoreVisionStage(ExternalStoreVisionStage.valueOf(nextStageName));
            } else if (stage == ExternalStoreVisionStage.NONE) {
                run.updateExternalStoreVisionStage(ExternalStoreVisionStage.OPTION_PRESENCE);
            }
            return instruction;
        }

        // OPTION_PRESENCE에서 옵션 미발견 → SCROLL: 스크롤 횟수 증가 + stage를 NONE으로 리셋
        // → 다음 step에서 다시 OPTION_PRESENCE 캡처를 수행하여 옵션 존재 여부를 재확인한다.
        if (stage == ExternalStoreVisionStage.OPTION_PRESENCE && instruction.action() == BrowserActionType.SCROLL) {
            run.incrementOptionPresenceScrollCount();
            run.updateExternalStoreVisionStage(ExternalStoreVisionStage.NONE);
            log.info("[AgentRunService] OPTION_PRESENCE 스크롤 재확인 - runId={}, scrollCount={}",
                    run.getRunId(), run.getOptionPresenceScrollCount());
            return instruction;
        }

        if (stage == ExternalStoreVisionStage.OPTION_PRESENCE && instruction.action() == BrowserActionType.CLICK) {
            run.updateExternalStoreVisionStage(ExternalStoreVisionStage.OPTION_SELECTION);
            return instruction;
        }

        if (stage == ExternalStoreVisionStage.OPTION_SELECTION
                && instruction.action() == BrowserActionType.USE_TOOL
                && instruction.toolRequest() != null
                && AgentStepPlannerService.INTERNAL_REQUEST_OPTION_SELECTION_TOOL.equals(instruction.toolRequest().name())) {
            List<OptionGroupRequest> optionGroups = (List<OptionGroupRequest>) instruction.toolRequest().input().get("optionGroups");
            optionGroups = optionGroups == null ? List.of() : optionGroups;
            publishOptionSelectionRequest(run, commandSession, optionGroups);
            return null;
        }

        if (stage == ExternalStoreVisionStage.OPTION_SELECTION && instruction.action() == BrowserActionType.CLICK) {
            run.updateExternalStoreVisionStage(ExternalStoreVisionStage.PURCHASE);
            return instruction;
        }

        if (instruction.action() == BrowserActionType.COMPLETE) {
            run.updateExternalStoreVisionStage(ExternalStoreVisionStage.NONE);
        }

        return instruction;
    }
}
