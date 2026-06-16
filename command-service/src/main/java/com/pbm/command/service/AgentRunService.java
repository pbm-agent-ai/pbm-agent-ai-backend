package com.pbm.command.service;

import com.pbm.command.config.BrowserAgentTokenUtil;
import com.pbm.command.domain.AgentRun;
import com.pbm.command.domain.AgentRunStatus;
import com.pbm.command.domain.BrowserDevice;
import com.pbm.command.domain.CommandSessionStatus;
import com.pbm.command.dto.event.CheckoutPaymentEvent;
import com.pbm.command.dto.event.CheckoutPaymentEventPayload;
import com.pbm.command.dto.response.AgentRunCreatedResponse;
import com.pbm.command.dto.response.AssignedRunResponse;
import com.pbm.command.dto.response.AgentRunResponse;
import com.pbm.command.dto.response.ProductCandidateResponse;
import com.pbm.command.dto.response.SelectionValidationResultResponse;
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
import com.pbm.command.domain.ExternalStoreVisionStage;
import com.pbm.command.dto.event.OptionSelectionRequestEvent;
import com.pbm.command.dto.event.OptionSelectionRequestPayload;
import com.pbm.command.dto.event.OptionSelectionRequestPayload.OptionGroupPayload;
import com.pbm.command.dto.event.LoginCredentialRequestEvent;
import com.pbm.command.dto.event.LoginCredentialRequestPayload;
import com.pbm.command.dto.request.InteractiveElementRequest;
import com.pbm.command.dto.request.LoginFormRequest;
import com.pbm.command.dto.request.OptionGroupRequest;
import com.pbm.command.dto.response.ActionInstructionResponse;
import com.pbm.command.publisher.CheckoutPaymentEventPublisher;
import com.pbm.command.publisher.LoginCredentialRequestEventPublisher;
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
            AgentRunStatus.AWAITING_OPTION_SELECTION,
            AgentRunStatus.AWAITING_LOGIN_CREDENTIALS
    );

    private static final Set<AgentRunStatus> INTERRUPTIBLE_STATUSES = Set.of(
            AgentRunStatus.ASSIGNED,
            AgentRunStatus.RUNNING,
            AgentRunStatus.AWAITING_APPROVAL,
            AgentRunStatus.AWAITING_LOGIN_CREDENTIALS
    );

    private final AgentRunRepository agentRunRepository;
    private final BrowserDeviceRepository browserDeviceRepository;
    private final CommandSessionRepository commandSessionRepository;
    private final BrowserAgentTokenUtil browserAgentTokenUtil;
    private final StringRedisTemplate stringRedisTemplate;
    private final AgentStepPlannerService agentStepPlannerService;
    private final CheckoutPaymentEventPublisher checkoutPaymentEventPublisher;
    private final OptionSelectionEventPublisher optionSelectionEventPublisher;
    private final LoginCredentialRequestEventPublisher loginCredentialRequestEventPublisher;
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
            LoginCredentialRequestEventPublisher loginCredentialRequestEventPublisher,
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
        this.loginCredentialRequestEventPublisher = loginCredentialRequestEventPublisher;
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
        return createRun(userId, subscriptionId, commandId, triggerPrice, aiAgentPrivateKey, null);
    }

    @Transactional
    public AgentRunCreatedResponse createRun(Long userId, Long subscriptionId, String commandId,
                                             Integer triggerPrice, String aiAgentPrivateKey, String productImageUrl) {
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
                ? AgentRun.createQueuedWithTriggerPrice(userId, subscriptionId, commandId, triggerPrice, aiAgentPrivateKey, productImageUrl)
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

    @Transactional
    public int expireLoginCredentialBefore(LocalDateTime threshold) {
        List<AgentRun> targets = agentRunRepository.findAllByStatusAndApprovalRequestedAtBefore(
                AgentRunStatus.AWAITING_LOGIN_CREDENTIALS,
                threshold
        );
        targets.forEach(AgentRun::expireLoginCredentials);
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

        // selectedOptionValue는 초기화하지 않는다 (무한루프 방지, 위 주석 참조)

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

        if (run.getStatus() == AgentRunStatus.AWAITING_LOGIN_CREDENTIALS) {
            if (!run.hasPendingLoginCredentials()) {
                log.info("[AgentRunService] 로그인 자격증명 대기 중 - runId={}", run.getRunId());
                return new AgentRunStepResponse(run.getRunId(), run.getStatus(), run.getCurrentStepIndex(), null);
            }
            log.info("[AgentRunService] 로그인 자격증명 수신, 실행 재개 - runId={}, username={}",
                    run.getRunId(), maskUsername(run.getPendingLoginUsername()));
        }

        CommandSession commandSession = commandSessionRepository.findByCommandId(run.getCommandId())
                .orElseThrow(() -> new CommandSessionNotFoundException("세션을 찾을 수 없습니다. commandId: " + run.getCommandId()));

        if (run.hasPendingLoginCredentials() && !isLoginPage(request.snapshot())) {
            log.info("[AgentRunService] 로그인 페이지 이탈 감지 → 저장된 로그인 자격증명 정리 - runId={}", run.getRunId());
            run.clearPendingLoginCredentials();
        }

        ActionInstructionResponse loginInstruction = handleLoginPageAutomation(run, commandSession, request.snapshot());
        if (loginInstruction != null) {
            return new AgentRunStepResponse(run.getRunId(), run.getStatus(), run.getCurrentStepIndex(), loginInstruction);
        }
        if (run.getStatus() == AgentRunStatus.AWAITING_LOGIN_CREDENTIALS) {
            return new AgentRunStepResponse(run.getRunId(), run.getStatus(), run.getCurrentStepIndex(), null);
        }

        syncSearchResultsVisionState(run, request.snapshot());

        // triggerPrice: 모니터링 트리거 후 결제 시 실제 조건 충족 가격 (즉시 결제 시 null)
        // CATALOG_NAVIGATOR가 lprice(선택 당시 가격) 대신 이 가격을 기준가로 사용한다.
        var instruction = agentStepPlannerService.planNextAction(
                run.getRunId(),
                run.getCurrentStepIndex(),
                commandSession,
                request.snapshot(),     // 현재 브라우저 화면 상태
                request.previousActionResult(),
                run.getSelectedOptionValue(),
                null,
                run.getExternalStoreVisionStage(),
                run.getOptionPresenceScrollCount(),
                run.getNaverPurchaseVisionAttemptCount()
        );

        boolean searchResultsPage = isSearchResultsPage(request.snapshot());
        boolean optionGroupsVisible = request.snapshot() != null
                && request.snapshot().optionGroups() != null
                && !request.snapshot().optionGroups().isEmpty();
        if (optionGroupsVisible
                && run.getExternalStoreVisionStage() != ExternalStoreVisionStage.SEARCH_RESULTS_PRODUCT
                && run.getOptionPresenceScrollCount() > 0) {
            run.resetOptionPresenceScrollCount();
        }

        if (searchResultsPage && run.getExternalStoreVisionStage() == ExternalStoreVisionStage.NONE
                && instruction != null
                && instruction.action() == BrowserActionType.WAIT
                && !optionGroupsVisible
                && run.getSelectedOptionValue() == null) {
            int updatedAttemptCount = run.incrementOptionPresenceScrollCount();
            log.info("[AgentRunService] 검색결과 DOM 미탐지 카운트 증가 - runId={}, attemptCount={}",
                    run.getRunId(), updatedAttemptCount);
            if (updatedAttemptCount >= 2) {
                run.updateExternalStoreVisionStage(ExternalStoreVisionStage.SEARCH_RESULTS_PRODUCT);
                log.info("[AgentRunService] 검색결과 Vision 모드 전환 - runId={}, attemptCount={}",
                        run.getRunId(), updatedAttemptCount);
            }
        } else if (searchResultsPage
                && run.getExternalStoreVisionStage() == ExternalStoreVisionStage.SEARCH_RESULTS_PRODUCT
                && instruction != null
                && instruction.action() == BrowserActionType.SCROLL) {
            int updatedAttemptCount = run.incrementOptionPresenceScrollCount();
            log.info("[AgentRunService] 검색결과 Vision 스크롤 카운트 증가 - runId={}, attemptCount={}",
                    run.getRunId(), updatedAttemptCount);
        } else if (instruction != null
                && instruction.action() == BrowserActionType.SCROLL
                && !searchResultsPage
                && !optionGroupsVisible
                && run.getSelectedOptionValue() == null) {
            int updatedScrollCount = run.incrementOptionPresenceScrollCount();
            log.info("[AgentRunService] 옵션 영역 탐색 스크롤 카운트 증가 - runId={}, scrollCount={}",
                    run.getRunId(), updatedScrollCount);
        }

        // 네이버 PRODUCT_DETAIL Vision 구매버튼 탐색: 스크린샷 기반 분석 후 SCROLL 반환 시 시도 카운터 증가
        boolean naverVisionScrolled = instruction != null
                && instruction.action() == BrowserActionType.SCROLL
                && request.previousActionResult() != null
                && request.previousActionResult().toolResult() != null
                && request.previousActionResult().toolResult().screenshot() != null
                && isNaverProductDetailUrl(request.snapshot());
        if (naverVisionScrolled) {
            int visionAttempt = run.incrementNaverPurchaseVisionAttemptCount();
            log.info("[AgentRunService] 네이버 구매버튼 Vision 스크롤 카운터 증가 - runId={}, attemptCount={}",
                    run.getRunId(), visionAttempt);
        }

        // 옵션 매칭 실패 또는 옵션 미지정: 텔레그램 옵션 요청 Kafka 발행 + 상태 전환
        // 1) planNextAction이 null을 반환한 경우
        // 2) 사용자가 옵션을 지정했지만 매칭되지 않은 경우 (hasUnmatchedOptions)
        // 3) 사용자가 옵션을 아예 지정하지 않았는데 옵션 그룹이 존재하는 경우 (hasAnyUnspecifiedOptions)
        //    → AI가 기본 선택값으로 구매 진행하는 것을 방지하고, 사용자에게 텔레그램으로 옵션 선택을 요구
        if (run.getStatus() == AgentRunStatus.RUNNING
                && request.snapshot() != null
                && request.snapshot().optionGroups() != null
                && !request.snapshot().optionGroups().isEmpty()
                && run.getSelectedOptionValue() == null
                && (instruction == null
                    || agentStepPlannerService.hasUnmatchedOptions(commandSession, request.snapshot(), run.getSelectedOptionValue())
                    || agentStepPlannerService.hasAnyUnspecifiedOptions(commandSession, request.snapshot(), run.getSelectedOptionValue()))) {

            log.info("[AgentRunService] 옵션 매칭 실패 또는 미지정 → 텔레그램 옵션 요청 발행 - runId={}", run.getRunId());
            publishOptionSelectionRequest(run, commandSession, request.snapshot().optionGroups());
            return new AgentRunStepResponse(run.getRunId(), run.getStatus(), run.getCurrentStepIndex(), null);
        }

        // 종속 옵션(cascading options) 처리:
        // 이전 옵션 선택이 완료(selectedOptionValue != null)되었지만,
        // 새로 활성화된 미선택 옵션 그룹이 존재하는 경우 텔레그램으로 다시 요청한다.
        // 예: 색상 선택 → 성별 드롭다운 활성화 → 사이즈 드롭다운 활성화
        if (run.getStatus() == AgentRunStatus.RUNNING
                && request.snapshot() != null
                && request.snapshot().optionGroups() != null
                && run.getSelectedOptionValue() != null
                && hasPendingCascadingOptions(request.snapshot().optionGroups())) {

            log.info("[AgentRunService] 종속 옵션 감지 → selectedOptionValue 초기화 후 텔레그램 재요청 - runId={}, previousSelection={}",
                    run.getRunId(), run.getSelectedOptionValue());
            run.clearSelectedOptionValue();
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

    /**
     * 종속 옵션(cascading options)에서 이전 선택이 반영된 후,
     * 새로 활성화된 미선택 옵션 그룹이 있는지 확인한다.
     *
     * 스마트스토어의 종속 옵션 구조: 색상 → 성별 → 사이즈 순서로 이전 옵션을 선택해야
     * 다음 옵션 드롭다운이 활성화(disabled=false)되고 옵션 목록이 채워진다.
     *
     * 핵심: 이전 선택이 페이지에 반영되었는지(= 하나 이상의 그룹에 selectedOption이 존재)를
     * 먼저 확인한다. 반영 전이면 false를 반환하여 클릭이 먼저 실행되도록 한다.
     * 반영 후에만 아직 미선택인 활성 그룹이 있는지 확인하여 다음 텔레그램 요청을 트리거한다.
     */
    private boolean hasPendingCascadingOptions(List<OptionGroupRequest> optionGroups) {
        if (optionGroups == null || optionGroups.isEmpty()) {
            return false;
        }

        // 1) 이전 선택이 페이지에 반영되었는지 확인:
        //    하나 이상의 활성 그룹에 selectedOption이 설정되어 있어야 함
        boolean anyGroupAlreadySelected = optionGroups.stream().anyMatch(og ->
                !Boolean.TRUE.equals(og.disabled())
                        && og.selectedOption() != null && !og.selectedOption().isBlank()
        );
        if (!anyGroupAlreadySelected) {
            // 아직 아무 옵션도 선택되지 않음 → 클릭이 먼저 실행되어야 함
            return false;
        }

        // 2) 선택 반영 후, 새로 활성화된 미선택 그룹이 있는지 확인
        return optionGroups.stream().anyMatch(og ->
                !Boolean.TRUE.equals(og.disabled())
                        && og.options() != null && !og.options().isEmpty()
                        && (og.selectedOption() == null || og.selectedOption().isBlank())
        );
    }

    /**
     * 텔레그램에서 선택된 옵션값(selectedOptionValue)은 초기화하지 않는다.
     * 값을 유지해야 hasAnyUnspecifiedOptions()가 false를 반환하여
     * 옵션 선택 후 "구매하기" 단계로 정상 진행된다.
     *
     * buildExplicitSmartstoreOptionInstruction()이 이미 페이지에서 선택 완료된 옵션은
     * continue로 건너뛰므로 (line 1086-1088), 값을 유지해도 중복 클릭이 발생하지 않는다.
     *
     * 이전에 초기화하면 hasAnyUnspecifiedOptions()가 다시 true를 반환하여
     * 텔레그램 옵션 요청이 무한루프로 발생하는 버그가 있었다.
     */

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

    private String resolveSelectedProductName(CommandSession commandSession) {
        List<ProductCandidateResponse> candidates = parseCandidates(commandSession.getCandidatesJson());
        List<String> selectedProductIds = parseSelectedProductIds(commandSession.getSelectedProductIdsJson());

        if (!selectedProductIds.isEmpty()) {
            return candidates.stream()
                    .filter(candidate -> selectedProductIds.contains(candidate.productId()))
                    .map(ProductCandidateResponse::title)
                    .filter(title -> title != null && !title.isBlank())
                    .findFirst()
                    .orElse(null);
        }

        return firstNonBlankTitle(candidates);
    }

    private String firstNonBlankTitle(List<ProductCandidateResponse> products) {
        if (products == null || products.isEmpty()) {
            return null;
        }

        return products.stream()
                .map(ProductCandidateResponse::title)
                .filter(title -> title != null && !title.isBlank())
                .findFirst()
                .orElse(null);
    }

    private List<ProductCandidateResponse> parseCandidates(String candidatesJson) {
        if (candidatesJson == null || candidatesJson.isBlank()) {
            return List.of();
        }

        try {
            return objectMapper.readValue(candidatesJson, new TypeReference<List<ProductCandidateResponse>>() {});
        } catch (JsonProcessingException e) {
            log.warn("candidatesJson 파싱 실패 - reason={}", e.getMessage());
            return List.of();
        }
    }

    private List<String> parseSelectedProductIds(String selectedProductIdsJson) {
        if (selectedProductIdsJson == null || selectedProductIdsJson.isBlank()) {
            return List.of();
        }

        try {
            return objectMapper.readValue(selectedProductIdsJson, new TypeReference<List<String>>() {});
        } catch (JsonProcessingException e) {
            log.warn("selectedProductIdsJson 파싱 실패 - reason={}", e.getMessage());
            return List.of();
        }
    }

    private SelectionValidationResultResponse parseValidationResult(String validationResultJson) {
        if (validationResultJson == null || validationResultJson.isBlank()) {
            return null;
        }

        try {
            return objectMapper.readValue(validationResultJson, SelectionValidationResultResponse.class);
        } catch (JsonProcessingException e) {
            log.warn("validationResultJson 파싱 실패 - reason={}", e.getMessage());
            return null;
        }
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

        // 상품명: 실제 선택된 상품명을 우선 사용하고, 없으면 원본 명령어로 fallback
        String productName = resolveCheckoutProductName(commandSession);

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
                null,               // recipientAddress: 기본값 사용
                run.getProductImageUrl()
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

    String resolveCheckoutProductName(CommandSession commandSession) {
        if (commandSession == null) {
            return null;
        }

        SelectionValidationResultResponse validationResult = parseValidationResult(commandSession.getValidationResultJson());
        String triggeredProductName = firstNonBlankTitle(
                validationResult == null ? List.of() : validationResult.triggeredProducts()
        );
        if (triggeredProductName != null) {
            return triggeredProductName;
        }

        String selectedProductName = resolveSelectedProductName(commandSession);
        if (selectedProductName != null) {
            return selectedProductName;
        }

        return commandSession.getOriginalCommand();
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

        // Kafka 이벤트 구성 — 비활성(disabled) 그룹은 제외
        // 종속 옵션(cascading) 구조에서 아직 활성화되지 않은 그룹을 텔레그램에 보내면
        // 빈 옵션 목록이 표시되어 사용자가 혼란스러워함
        //
        // 주의: selectedOption 필터는 적용하지 않는다.
        // 페이지가 기본 선택값(예: 색상=블랙)을 가지고 있어도 사용자가 직접 선택한 것이 아니므로
        // 텔레그램에서 옵션 목록을 보여줘야 한다. selectedOption 필터를 적용하면
        // 기본 선택된 그룹이 제외되어 빈 옵션 목록이 전송되는 문제가 발생한다.
        List<OptionGroupPayload> payloadGroups = optionGroups.stream()
                .filter(og -> !Boolean.TRUE.equals(og.disabled()))
                .filter(og -> og.options() != null && !og.options().isEmpty())
                .map(og -> new OptionGroupPayload(og.groupName(), og.options()))
                .toList();

        log.info("[AgentRunService] 옵션 선택 요청 payload 디버그 - runId={}, originalGroups={}, filteredGroups={}, original={}, filtered={}",
                run.getRunId(),
                optionGroups.size(),
                payloadGroups.size(),
                summarizeOptionGroups(optionGroups),
                summarizePayloadGroups(payloadGroups));

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

    private List<String> summarizeOptionGroups(List<OptionGroupRequest> optionGroups) {
        if (optionGroups == null) {
            return List.of();
        }
        return optionGroups.stream()
                .map(og -> String.format(
                        "group=%s options=%d selected=%s disabled=%s values=%s",
                        og.groupName(),
                        og.options() == null ? 0 : og.options().size(),
                        og.selectedOption(),
                        og.disabled(),
                        og.options()
                ))
                .toList();
    }

    private List<String> summarizePayloadGroups(List<OptionGroupPayload> payloadGroups) {
        if (payloadGroups == null) {
            return List.of();
        }
        return payloadGroups.stream()
                .map(group -> String.format(
                        "group=%s options=%d values=%s",
                        group.groupName(),
                        group.options() == null ? 0 : group.options().size(),
                        group.options()
                ))
                .toList();
    }

    private ActionInstructionResponse handleLoginPageAutomation(
            AgentRun run,
            CommandSession commandSession,
            PageSnapshotRequest snapshot
    ) {
        if (!isLoginPage(snapshot)) {
            return null;
        }

        String actionId = "login-" + run.getRunId() + "-" + run.getCurrentStepIndex();
        LoginFormRequest loginForm = snapshot != null ? snapshot.loginForm() : null;

        if (loginForm != null && loginForm.detected()) {
            boolean alreadyFilled = loginForm.usernameFilled() && loginForm.passwordFilled();
            if (alreadyFilled) {
                InteractiveElementRequest loginButton = buildLoginButtonTarget(loginForm, snapshot);
                if (loginButton != null) {
                    log.info("[AgentRunService] 로그인 페이지 - 아이디/비밀번호 입력 확인 → 로그인 버튼 클릭 - runId={}", run.getRunId());
                    return ActionInstructionResponse.click(run.getCurrentStepIndex(), actionId, loginButton);
                }
            }

            if (run.hasPendingLoginCredentials()) {
                if (!loginForm.usernameFilled() && loginForm.usernameSelector() != null && !loginForm.usernameSelector().isBlank()) {
                    log.info("[AgentRunService] 로그인 페이지 - 아이디 자동 입력 - runId={}, username={}",
                            run.getRunId(), maskUsername(run.getPendingLoginUsername()));
                    return ActionInstructionResponse.input(
                            run.getCurrentStepIndex(),
                            actionId,
                            buildInputTarget(loginForm.usernameSelector(), loginForm.usernameLabel(), "username"),
                            run.getPendingLoginUsername()
                    );
                }

                if (!loginForm.passwordFilled() && loginForm.passwordSelector() != null && !loginForm.passwordSelector().isBlank()) {
                    log.info("[AgentRunService] 로그인 페이지 - 비밀번호 자동 입력 - runId={}, passwordLength={}",
                            run.getRunId(), run.getPendingLoginPassword() == null ? 0 : run.getPendingLoginPassword().length());
                    return ActionInstructionResponse.input(
                            run.getCurrentStepIndex(),
                            actionId,
                            buildInputTarget(loginForm.passwordSelector(), loginForm.passwordLabel(), "password"),
                            run.getPendingLoginPassword()
                    );
                }

                InteractiveElementRequest loginButton = buildLoginButtonTarget(loginForm, snapshot);
                if (loginButton != null) {
                    log.info("[AgentRunService] 로그인 페이지 - 자격증명 입력 완료 → 로그인 버튼 클릭 - runId={}", run.getRunId());
                    return ActionInstructionResponse.click(run.getCurrentStepIndex(), actionId, loginButton);
                }
            } else {
                publishLoginCredentialRequest(run, commandSession);
                return null;
            }
        }

        InteractiveElementRequest loginButton = findLoginButtonInSnapshot(snapshot).orElse(null);
        if (loginButton != null) {
            log.info("[AgentRunService] 로그인 페이지 fallback - 로그인 버튼 클릭 - runId={}", run.getRunId());
            return ActionInstructionResponse.click(run.getCurrentStepIndex(), actionId, loginButton);
        }

        return ActionInstructionResponse.waitAction(run.getCurrentStepIndex(), actionId, 1000, 10000);
    }

    private boolean isLoginPage(PageSnapshotRequest snapshot) {
        if (snapshot == null || snapshot.currentUrl() == null) {
            return false;
        }
        String lower = snapshot.currentUrl().toLowerCase();
        return lower.contains("nid.naver.com/nidlogin")
                || lower.contains("/login")
                || lower.contains("signin")
                || lower.contains("sign-in");
    }

    private InteractiveElementRequest buildInputTarget(String selector, String label, String fieldName) {
        return new InteractiveElementRequest(
                "login-" + fieldName,
                "textbox",
                label == null || label.isBlank() ? fieldName : label,
                selector,
                null,
                true,
                false
        );
    }

    private InteractiveElementRequest buildLoginButtonTarget(LoginFormRequest loginForm, PageSnapshotRequest snapshot) {
        if (loginForm.loginButtonSelector() != null && !loginForm.loginButtonSelector().isBlank()) {
            return new InteractiveElementRequest(
                    "login-button",
                    "button",
                    loginForm.loginButtonLabel() == null || loginForm.loginButtonLabel().isBlank() ? "로그인" : loginForm.loginButtonLabel(),
                    loginForm.loginButtonSelector(),
                    null,
                    true,
                    false
            );
        }
        return findLoginButtonInSnapshot(snapshot).orElse(null);
    }

    private Optional<InteractiveElementRequest> findLoginButtonInSnapshot(PageSnapshotRequest snapshot) {
        if (snapshot == null || snapshot.interactiveElements() == null) {
            return Optional.empty();
        }
        return snapshot.interactiveElements().stream()
                .filter(InteractiveElementRequest::isEnabled)
                .filter(InteractiveElementRequest::isVisible)
                .filter(el -> {
                    String label = normalize(el.labelText());
                    return label != null
                            && (label.contains("로그인") || label.contains("login") || label.contains("sign in") || label.contains("signin"));
                })
                .findFirst();
    }

    private void publishLoginCredentialRequest(AgentRun run, CommandSession commandSession) {
        run.awaitLoginCredentials(LocalDateTime.now());

        LoginCredentialRequestPayload payload = new LoginCredentialRequestPayload(
                run.getUserId(),
                run.getRunId(),
                commandSession.getOriginalCommand()
        );
        LoginCredentialRequestEvent event = new LoginCredentialRequestEvent(
                UUID.randomUUID().toString(),
                "LOGIN_CREDENTIAL_REQUESTED",
                Instant.now(),
                "command-service",
                payload
        );

        loginCredentialRequestEventPublisher.publish(event);
        log.info("[AgentRunService] 로그인 자격증명 요청 이벤트 발행 완료 - runId={}, userId={}",
                run.getRunId(), run.getUserId());
    }

    private void syncSearchResultsVisionState(AgentRun run, PageSnapshotRequest snapshot) {
        if (run.getExternalStoreVisionStage() != ExternalStoreVisionStage.SEARCH_RESULTS_PRODUCT) {
            return;
        }

        if (isSearchResultsPage(snapshot)) {
            return;
        }

        log.info("[AgentRunService] 검색결과 Vision 상태 초기화 - runId={}, previousAttemptCount={}",
                run.getRunId(), run.getOptionPresenceScrollCount());
        run.updateExternalStoreVisionStage(ExternalStoreVisionStage.NONE);
        run.resetOptionPresenceScrollCount();
    }

    private boolean isNaverProductDetailUrl(PageSnapshotRequest snapshot) {
        if (snapshot == null || snapshot.currentUrl() == null) {
            return false;
        }
        String url = snapshot.currentUrl().toLowerCase();
        return url.contains("smartstore.naver.com") && url.contains("/products/");
    }

    private boolean isSearchResultsPage(PageSnapshotRequest snapshot) {
        if (snapshot == null || snapshot.currentUrl() == null) {
            return false;
        }
        String currentUrl = snapshot.currentUrl().toLowerCase();
        return currentUrl.contains("search.shopping.naver.com")
                && currentUrl.contains("/search/");
    }

    private String maskUsername(String username) {
        if (username == null || username.isBlank()) {
            return "(empty)";
        }
        if (username.length() <= 2) {
            return username.charAt(0) + "*";
        }
        return username.substring(0, 2) + "***";
    }

}
