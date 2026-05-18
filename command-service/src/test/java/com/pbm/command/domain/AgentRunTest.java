package com.pbm.command.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AgentRun 도메인 상태 전이 테스트.
 */
class AgentRunTest {

    @Test
    @DisplayName("QUEUED 상태의 run을 디바이스에 할당할 수 있다")
    void assignTo_changesStatusToAssigned() {
        AgentRun run = AgentRun.createQueued(1L, "cmd-1");

        run.assignTo("device-1", LocalDateTime.of(2026, 5, 16, 15, 0));

        assertThat(run.getStatus()).isEqualTo(AgentRunStatus.ASSIGNED);
        assertThat(run.getAssignedDeviceId()).isEqualTo("device-1");
    }

    @Test
    @DisplayName("잘못된 상태에서 start를 호출하면 예외를 던진다")
    void start_withInvalidStatus_throwsException() {
        AgentRun run = AgentRun.createQueued(1L, "cmd-1");

        assertThatThrownBy(run::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Run 시작");
    }

    @Test
    @DisplayName("INTERRUPTED 상태는 recover 후 RECOVERING으로 전환된다")
    void recover_changesStatusToRecovering() {
        AgentRun run = AgentRun.createQueued(1L, "cmd-1");
        run.assignTo("device-1", LocalDateTime.now());
        run.start();
        run.interrupt();

        run.recover();

        assertThat(run.getStatus()).isEqualTo(AgentRunStatus.RECOVERING);
    }

    @Test
    @DisplayName("AWAITING_APPROVAL 상태에서 complete를 호출하면 예외를 던진다")
    void complete_withAwaitingApproval_throwsException() {
        AgentRun run = AgentRun.createQueued(1L, "cmd-1");
        run.assignTo("device-1", LocalDateTime.now());
        run.start();
        run.awaitApproval(LocalDateTime.now());

        assertThatThrownBy(run::complete)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("완료는 RUNNING 상태에서만 가능합니다");
    }
}
