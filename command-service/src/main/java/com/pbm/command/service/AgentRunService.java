package com.pbm.command.service;

import com.pbm.command.config.BrowserAgentTokenUtil;
import com.pbm.command.domain.AgentRun;
import com.pbm.command.domain.AgentRunStatus;
import com.pbm.command.domain.BrowserDevice;
import com.pbm.command.dto.response.AgentRunCreatedResponse;
import com.pbm.command.dto.response.AssignedRunResponse;
import com.pbm.command.dto.response.AgentRunResponse;
import com.pbm.command.dto.request.AgentRunActionResultRequest;
import com.pbm.command.dto.request.AgentRunStepRequest;
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
import com.pbm.command.repository.AgentRunRepository;
import com.pbm.command.repository.BrowserDeviceRepository;
import com.pbm.command.repository.CommandSessionRepository;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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
            AgentRunStatus.RECOVERING
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

    public AgentRunService(
            AgentRunRepository agentRunRepository,
            BrowserDeviceRepository browserDeviceRepository,
            CommandSessionRepository commandSessionRepository,
            BrowserAgentTokenUtil browserAgentTokenUtil,
            StringRedisTemplate stringRedisTemplate,
            AgentStepPlannerService agentStepPlannerService
    ) {
        this.agentRunRepository = agentRunRepository;
        this.browserDeviceRepository = browserDeviceRepository;
        this.commandSessionRepository = commandSessionRepository;
        this.browserAgentTokenUtil = browserAgentTokenUtil;
        this.stringRedisTemplate = stringRedisTemplate;
        this.agentStepPlannerService = agentStepPlannerService;
    }

    /**
     * 웹 앱 요청으로 새 AgentRun을 생성한다.
     * <p>
     * INTERRUPTED 상태의 기존 run은 새 run 생성 시 자동으로 ABORTED 처리한다.
     * 이는 디바이스가 오프라인 상태에서 새로운 명령이 들어온 경우를 처리한다.
     */
    @Transactional
    public AgentRunCreatedResponse createRun(Long userId, String commandId) {
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

        AgentRun agentRun = AgentRun.createQueued(userId, commandId);
        assignLatestOnlineDeviceIfPossible(agentRun);   // 온라인 디바이스가 있으면 바로 할당, 없으면 그냥 패스함

        AgentRun saved = agentRunRepository.save(agentRun);
        log.info("AgentRun 생성 완료 - runId: {}, status: {}, assignedDevice: {}",
                saved.getRunId(), saved.getStatus(), saved.getAssignedDeviceId());
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

        CommandSession commandSession = commandSessionRepository.findByCommandId(run.getCommandId())
                .orElseThrow(() -> new CommandSessionNotFoundException("세션을 찾을 수 없습니다. commandId: " + run.getCommandId()));

        var instruction = agentStepPlannerService.planNextAction(
                run.getRunId(),
                run.getCurrentStepIndex(),
                commandSession,
                request.snapshot(),     // 현재 브라우저 화면 상태
                request.previousActionResult()
        );

        // 승인이 필요한 액션일 경우
        if (instruction.action() == BrowserActionType.AWAIT_APPROVAL && run.getStatus() == AgentRunStatus.RUNNING) {
            run.awaitApproval(LocalDateTime.now());
        }

        // 모든 작업 완료일 경우
        if (instruction.action() == BrowserActionType.COMPLETE && run.getStatus() == AgentRunStatus.RUNNING) {
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
}
