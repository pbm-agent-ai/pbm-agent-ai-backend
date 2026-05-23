package com.pbm.command.service;

import com.pbm.command.config.BrowserAgentTokenUtil;
import com.pbm.command.domain.AgentRun;
import com.pbm.command.domain.AgentRunStatus;
import com.pbm.command.domain.BrowserActionType;
import com.pbm.command.domain.BrowserDevice;
import com.pbm.command.domain.CommandSession;
import com.pbm.command.dto.request.AgentRunActionResultRequest;
import com.pbm.command.dto.request.AgentRunStepRequest;
import com.pbm.command.dto.request.PageSnapshotRequest;
import com.pbm.command.dto.response.AgentRunCreatedResponse;
import com.pbm.command.dto.response.ActionInstructionResponse;
import com.pbm.command.dto.response.AssignedRunResponse;
import com.pbm.command.dto.response.AgentRunStepResponse;
import com.pbm.command.exception.AgentRunAccessDeniedException;
import com.pbm.command.exception.AgentRunConflictException;
import com.pbm.command.exception.BrowserDeviceNotFoundException;
import com.pbm.command.exception.CommandSessionNotFoundException;
import com.pbm.command.repository.AgentRunRepository;
import com.pbm.command.repository.BrowserDeviceRepository;
import com.pbm.command.repository.CommandSessionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/**
 * AgentRunService 단위 테스트.
 */
@ExtendWith(MockitoExtension.class)
class AgentRunServiceTest {

    @Mock
    private AgentRunRepository agentRunRepository;

    @Mock
    private CommandSessionRepository commandSessionRepository;

    @Mock
    private BrowserDeviceRepository browserDeviceRepository;

    @Mock
    private BrowserAgentTokenUtil browserAgentTokenUtil;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private AgentStepPlannerService agentStepPlannerService;

    @InjectMocks
    private AgentRunService agentRunService;

    @Test
    @DisplayName("웹 앱 요청으로 AgentRun을 생성한다")
    void createRun_createsQueuedRun() {
        CommandSession session = CommandSession.createSearching(1L, "테스트 명령");
        given(commandSessionRepository.findByCommandId("cmd-1")).willReturn(Optional.of(session));
        given(agentRunRepository.findAllByUserIdAndStatusIn(any(), any())).willReturn(List.of());
        given(browserDeviceRepository.findFirstByUserIdAndLastSeenAtAfterOrderByLastSeenAtDesc(any(), any()))
                .willReturn(Optional.empty());
        given(agentRunRepository.save(any(AgentRun.class))).willAnswer(invocation -> invocation.getArgument(0));

        AgentRunCreatedResponse response = agentRunService.createRun(1L, "cmd-1");

        assertThat(response.commandId()).isEqualTo("cmd-1");
        assertThat(response.status()).isEqualTo("QUEUED");
        assertThat(response.runId()).isNotBlank();
    }

    @Test
    @DisplayName("활성 run이 이미 있으면 기존 run을 ABORTED 처리하고 새로 생성한다")
    void createRun_withActiveRun_abortsOldAndCreatesNew() {
        CommandSession session = CommandSession.createSearching(1L, "테스트 명령");
        AgentRun existingRun = AgentRun.createQueued(1L, "cmd-0");
        given(commandSessionRepository.findByCommandId("cmd-1")).willReturn(Optional.of(session));
        given(agentRunRepository.findAllByUserIdAndStatusIn(any(), any()))
                .willReturn(List.of())              // 첫 번째 호출: INTERRUPTED run 없음
                .willReturn(List.of(existingRun));  // 두 번째 호출: 활성 run 존재
        given(browserDeviceRepository.findFirstByUserIdAndLastSeenAtAfterOrderByLastSeenAtDesc(any(), any()))
                .willReturn(Optional.empty());
        given(agentRunRepository.save(any(AgentRun.class))).willAnswer(invocation -> invocation.getArgument(0));

        AgentRunCreatedResponse response = agentRunService.createRun(1L, "cmd-1");

        assertThat(response).isNotNull();
        assertThat(response.runId()).isNotBlank();
        assertThat(response.status()).isEqualTo("QUEUED");
        assertThat(existingRun.getStatus()).isEqualTo(AgentRunStatus.ABORTED);
    }

    @Test
    @DisplayName("존재하지 않는 commandId면 예외를 던진다")
    void createRun_withUnknownCommand_throwsNotFound() {
        given(commandSessionRepository.findByCommandId("missing-cmd")).willReturn(Optional.empty());

        assertThatThrownBy(() -> agentRunService.createRun(1L, "missing-cmd"))
                .isInstanceOf(CommandSessionNotFoundException.class);
    }

    @Test
    @DisplayName("온라인 디바이스가 있으면 run 생성 시 즉시 ASSIGNED 상태로 저장한다")
    void createRun_assignsLatestOnlineDeviceWhenAvailable() {
        CommandSession session = CommandSession.createSearching(1L, "테스트 명령");
        BrowserDevice browserDevice = BrowserDevice.create(
                1L,
                "device-1",
                "CHROME",
                "1.0.0",
                "macOS Chrome",
                LocalDateTime.now()
        );
        given(commandSessionRepository.findByCommandId("cmd-1")).willReturn(Optional.of(session));
        given(agentRunRepository.findAllByUserIdAndStatusIn(any(), any())).willReturn(List.of());
        given(browserDeviceRepository.findFirstByUserIdAndLastSeenAtAfterOrderByLastSeenAtDesc(any(), any()))
                .willReturn(Optional.of(browserDevice));
        given(stringRedisTemplate.opsForValue()).willReturn(valueOperations);
        given(browserAgentTokenUtil.generateAgentToken(any(), any(), any())).willReturn("agent-token");
        given(agentRunRepository.save(any(AgentRun.class))).willAnswer(invocation -> invocation.getArgument(0));

        AgentRunCreatedResponse response = agentRunService.createRun(1L, "cmd-1");

        assertThat(response.status()).isEqualTo("ASSIGNED");
    }

    @Test
    @DisplayName("디바이스에 ASSIGNED 된 pending run 1건이 있으면 agentToken과 함께 반환한다")
    void getPendingRunForDevice_returnsAssignedRun() {
        CommandSession session = CommandSession.createSearching(1L, "테스트 명령");
        AgentRun run = AgentRun.createQueued(1L, "cmd-1");
        run.assignTo("device-1", LocalDateTime.now());
        given(agentRunRepository.findFirstByAssignedDeviceIdAndStatusOrderByAssignedAtAsc("device-1", AgentRunStatus.ASSIGNED))
                .willReturn(Optional.of(run));
        given(commandSessionRepository.findByCommandId("cmd-1")).willReturn(Optional.of(session));
        given(stringRedisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.get("agent-run:assigned-token:" + run.getRunId())).willReturn("cached-agent-token");

        AssignedRunResponse response = agentRunService.getPendingRunForDevice("device-1");

        assertThat(response).isNotNull();
        assertThat(response.runId()).isEqualTo(run.getRunId());
        assertThat(response.agentToken()).isEqualTo("cached-agent-token");
        assertThat(response.commandId()).isEqualTo("cmd-1");
    }

    @Test
    @DisplayName("ASSIGNED run이 없으면 같은 사용자의 QUEUED run을 현재 디바이스에 할당한다")
    void getPendingRunForDevice_assignsQueuedRunWhenNeeded() {
        CommandSession session = CommandSession.createSearching(1L, "테스트 명령");
        BrowserDevice browserDevice = BrowserDevice.create(
                1L,
                "device-1",
                "CHROME",
                "1.0.0",
                "macOS Chrome",
                LocalDateTime.now()
        );
        AgentRun queuedRun = AgentRun.createQueued(1L, "cmd-1");

        given(agentRunRepository.findFirstByAssignedDeviceIdAndStatusOrderByAssignedAtAsc("device-1", AgentRunStatus.ASSIGNED))
                .willReturn(Optional.empty());
        given(browserDeviceRepository.findByDeviceId("device-1")).willReturn(Optional.of(browserDevice));
        given(agentRunRepository.findFirstByUserIdAndStatusOrderByCreatedAtAsc(1L, AgentRunStatus.QUEUED))
                .willReturn(Optional.of(queuedRun));
        given(commandSessionRepository.findByCommandId("cmd-1")).willReturn(Optional.of(session));
        given(stringRedisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.get("agent-run:assigned-token:" + queuedRun.getRunId())).willReturn(null);
        given(browserAgentTokenUtil.generateAgentToken(1L, queuedRun.getRunId(), "device-1"))
                .willReturn("agent-token");

        AssignedRunResponse response = agentRunService.getPendingRunForDevice("device-1");

        assertThat(response).isNotNull();
        assertThat(response.runId()).isEqualTo(queuedRun.getRunId());
        assertThat(queuedRun.getAssignedDeviceId()).isEqualTo("device-1");
        assertThat(queuedRun.getStatus()).isEqualTo(AgentRunStatus.ASSIGNED);
    }

    @Test
    @DisplayName("존재하지 않는 디바이스가 pending run 조회를 시도하면 예외를 던진다")
    void getPendingRunForDevice_withUnknownDevice_throwsException() {
        given(agentRunRepository.findFirstByAssignedDeviceIdAndStatusOrderByAssignedAtAsc("missing-device", AgentRunStatus.ASSIGNED))
                .willReturn(Optional.empty());
        given(browserDeviceRepository.findByDeviceId("missing-device")).willReturn(Optional.empty());

        assertThatThrownBy(() -> agentRunService.getPendingRunForDevice("missing-device"))
                .isInstanceOf(BrowserDeviceNotFoundException.class)
                .hasMessageContaining("브라우저 디바이스를 찾을 수 없습니다");
    }

    @Test
    @DisplayName("다른 디바이스가 recover를 시도하면 접근 거부 예외를 던진다")
    void recover_withDifferentDevice_throwsAccessDenied() {
        AgentRun run = AgentRun.createQueued(1L, "cmd-1");
        run.assignTo("device-1", LocalDateTime.now());
        run.start();
        run.interrupt();
        given(agentRunRepository.findByRunId(run.getRunId())).willReturn(Optional.of(run));

        assertThatThrownBy(() -> agentRunService.recover(run.getRunId(), "device-2"))
                .isInstanceOf(AgentRunAccessDeniedException.class)
                .hasMessageContaining("다른 디바이스");
    }

    @Test
    @DisplayName("인증 정보 없이 중단하면 접근 거부 예외를 던진다")
    void abort_withoutIdentity_throwsAccessDenied() {
        AgentRun run = AgentRun.createQueued(1L, "cmd-1");
        given(agentRunRepository.findByRunId(run.getRunId())).willReturn(Optional.of(run));

        assertThatThrownBy(() -> agentRunService.abort(run.getRunId(), "USER_CANCELLED", null, null))
                .isInstanceOf(AgentRunAccessDeniedException.class)
                .hasMessageContaining("인증 정보가 없습니다");
    }

    @Test
    @DisplayName("승인 대기 만료 조건에 맞는 run 개수를 반환한다")
    void expireApprovalBefore_expiresTargetRuns() {
        AgentRun run = AgentRun.createQueued(1L, "cmd-1");
        run.assignTo("device-1", LocalDateTime.now());
        run.start();
        run.awaitApproval(LocalDateTime.now().minusMinutes(20));
        given(agentRunRepository.findAllByStatusAndApprovalRequestedAtBefore(any(), any()))
                .willReturn(List.of(run));

        int expiredCount = agentRunService.expireApprovalBefore(LocalDateTime.now().minusMinutes(10));

        assertThat(expiredCount).isEqualTo(1);
        assertThat(run.getStatus()).isEqualTo(AgentRunStatus.APPROVAL_EXPIRED);
    }

    @Test
    @DisplayName("오프라인 디바이스에 할당된 run을 INTERRUPTED로 전환한다")
    void interruptRunsAssignedToDevices_interruptsRuns() {
        AgentRun assignedRun = AgentRun.createQueued(1L, "cmd-1");
        assignedRun.assignTo("device-1", LocalDateTime.now());

        AgentRun runningRun = AgentRun.createQueued(1L, "cmd-2");
        runningRun.assignTo("device-2", LocalDateTime.now());
        runningRun.start();

        given(agentRunRepository.findAllByAssignedDeviceIdInAndStatusIn(any(), any()))
                .willReturn(List.of(assignedRun, runningRun));

        int interruptedCount = agentRunService.interruptRunsAssignedToDevices(Set.of("device-1", "device-2"));

        assertThat(interruptedCount).isEqualTo(2);
        assertThat(assignedRun.getStatus()).isEqualTo(AgentRunStatus.INTERRUPTED);
        assertThat(runningRun.getStatus()).isEqualTo(AgentRunStatus.INTERRUPTED);
    }

    @Test
    @DisplayName("첫 step 요청이면 planner가 반환한 다음 액션을 그대로 응답한다")
    void processStep_returnsPlannedInstruction() {
        AgentRun run = AgentRun.createQueued(1L, "cmd-1");
        run.assignTo("device-1", LocalDateTime.now());
        run.start();
        CommandSession session = CommandSession.createSearching(1L, "테스트 명령");
        AgentRunStepRequest request = new AgentRunStepRequest(
                0,
                null,
                new PageSnapshotRequest("https://www.aliexpress.com", "AliExpress", "", List.of(), List.of(), List.of(), List.of(), "", LocalDateTime.now())
        );
        ActionInstructionResponse instruction = ActionInstructionResponse.waitAction(0, "act-0", 1000, 15000);

        given(agentRunRepository.findByRunId(run.getRunId())).willReturn(Optional.of(run));
        given(commandSessionRepository.findByCommandId("cmd-1")).willReturn(Optional.of(session));
        given(agentStepPlannerService.planNextAction(run.getRunId(), 0, session, request.snapshot(), null)).willReturn(instruction);

        AgentRunStepResponse response = agentRunService.processStep(run.getRunId(), "device-1", request);

        assertThat(response.runId()).isEqualTo(run.getRunId());
        assertThat(response.status()).isEqualTo(AgentRunStatus.RUNNING);
        assertThat(response.currentStepIndex()).isEqualTo(0);
        assertThat(response.instruction().action()).isEqualTo(BrowserActionType.WAIT);
    }

    @Test
    @DisplayName("직전 액션이 성공이면 다음 step으로 증가한 뒤 planner를 호출한다")
    void processStep_incrementsStepIndexAfterSuccess() {
        AgentRun run = AgentRun.createQueued(1L, "cmd-1");
        run.assignTo("device-1", LocalDateTime.now());
        run.start();
        CommandSession session = CommandSession.createSearching(1L, "테스트 명령");
        AgentRunStepRequest request = new AgentRunStepRequest(
                1,
                new AgentRunActionResultRequest(
                        run.getRunId(),
                        0,
                        "act-0",
                        BrowserActionType.NAVIGATE,
                        com.pbm.command.domain.ActionExecutionStatus.SUCCESS,
                        null,
                        null,
                        null,
                        null,
                        LocalDateTime.now()
                ),
                new PageSnapshotRequest("https://www.aliexpress.com/item/1.html", "상품", "", List.of(), List.of(), List.of(), List.of(), "", LocalDateTime.now())
        );
        ActionInstructionResponse instruction = ActionInstructionResponse.complete(1, "act-1");

        given(agentRunRepository.findByRunId(run.getRunId())).willReturn(Optional.of(run));
        given(commandSessionRepository.findByCommandId("cmd-1")).willReturn(Optional.of(session));
        given(agentStepPlannerService.planNextAction(run.getRunId(), 1, session, request.snapshot(), request.previousActionResult())).willReturn(instruction);

        AgentRunStepResponse response = agentRunService.processStep(run.getRunId(), "device-1", request);

        assertThat(response.currentStepIndex()).isEqualTo(1);
        assertThat(response.status()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(response.instruction()).isNull();
    }

    @Test
    @DisplayName("요청 stepIndex가 현재 run 상태와 다르면 충돌 예외를 던진다")
    void processStep_withMismatchedStepIndex_throwsConflictException() {
        AgentRun run = AgentRun.createQueued(1L, "cmd-1");
        run.assignTo("device-1", LocalDateTime.now());
        run.start();
        AgentRunStepRequest request = new AgentRunStepRequest(
                3,
                null,
                new PageSnapshotRequest("https://www.aliexpress.com", "AliExpress", "", List.of(), List.of(), List.of(), List.of(), "", LocalDateTime.now())
        );

        given(agentRunRepository.findByRunId(run.getRunId())).willReturn(Optional.of(run));

        assertThatThrownBy(() -> agentRunService.processStep(run.getRunId(), "device-1", request))
                .isInstanceOf(AgentRunConflictException.class)
                .hasMessageContaining("요청 stepIndex가 현재 Run 상태와 일치하지 않습니다");
    }

    @Test
    @DisplayName("치명적 액션 실패 코드는 run을 FAILED로 전환한다")
    void processStep_withFatalFailure_marksRunFailed() {
        AgentRun run = AgentRun.createQueued(1L, "cmd-1");
        run.assignTo("device-1", LocalDateTime.now());
        run.start();
        AgentRunStepRequest request = new AgentRunStepRequest(
                0,
                new AgentRunActionResultRequest(
                        run.getRunId(),
                        0,
                        "act-0",
                        BrowserActionType.CLICK,
                        com.pbm.command.domain.ActionExecutionStatus.FAILURE,
                        com.pbm.command.domain.ActionErrorCode.UNSUPPORTED_PAGE_STATE,
                        "페이지 상태 불일치",
                        null,
                        null,
                        LocalDateTime.now()
                ),
                new PageSnapshotRequest("https://www.aliexpress.com/item/1.html", "상품", "", List.of(), List.of(), List.of(), List.of(), "", LocalDateTime.now())
        );
        given(agentRunRepository.findByRunId(run.getRunId())).willReturn(Optional.of(run));

        AgentRunStepResponse response = agentRunService.processStep(run.getRunId(), "device-1", request);

        assertThat(response.status()).isEqualTo(AgentRunStatus.FAILED);
        assertThat(response.instruction()).isNull();
    }
}
