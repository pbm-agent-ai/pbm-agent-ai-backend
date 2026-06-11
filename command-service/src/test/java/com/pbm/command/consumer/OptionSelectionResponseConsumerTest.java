package com.pbm.command.consumer;

import com.pbm.command.domain.AgentRun;
import com.pbm.command.domain.AgentRunStatus;
import com.pbm.command.dto.event.OptionSelectionResponseEvent;
import com.pbm.command.dto.event.OptionSelectionResponsePayload;
import com.pbm.command.repository.AgentRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.mockito.Mockito.*;

/**
 * OptionSelectionResponseConsumer 단위 테스트.
 * 텔레그램 옵션 선택 응답 Kafka 이벤트 수신 시
 * AgentRun 상태 전환을 올바르게 수행하는지 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class OptionSelectionResponseConsumerTest {

    @Mock
    private AgentRunRepository agentRunRepository;

    @Mock
    private AgentRun agentRun;

    private OptionSelectionResponseConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new OptionSelectionResponseConsumer(agentRunRepository);
    }

    private OptionSelectionResponseEvent createEvent(String runId, String selectedValue) {
        return new OptionSelectionResponseEvent(
                "evt-001",
                "OPTION_SELECTION_RESPONDED",
                Instant.now(),
                "notification-service",
                new OptionSelectionResponsePayload(1L, runId, selectedValue)
        );
    }

    @Test
    @DisplayName("옵션 응답 수신 → AWAITING_OPTION_SELECTION 상태 run이 resolveOptionSelection 호출")
    void consume_resolvesOptionSelection() {
        // given
        String runId = "run-123";
        when(agentRunRepository.findByRunId(runId)).thenReturn(Optional.of(agentRun));
        when(agentRun.getStatus()).thenReturn(AgentRunStatus.AWAITING_OPTION_SELECTION);

        // when
        consumer.consume(createEvent(runId, "블랙"));

        // then
        verify(agentRun).resolveOptionSelection("블랙");
        verify(agentRunRepository).save(agentRun);
    }

    @Test
    @DisplayName("존재하지 않는 runId → 무시")
    void consume_runNotFound_ignores() {
        // given
        when(agentRunRepository.findByRunId("unknown")).thenReturn(Optional.empty());

        // when
        consumer.consume(createEvent("unknown", "블랙"));

        // then
        verify(agentRunRepository, never()).save(any());
    }

    @Test
    @DisplayName("AWAITING_OPTION_SELECTION이 아닌 상태 → 무시")
    void consume_wrongStatus_ignores() {
        // given
        String runId = "run-456";
        when(agentRunRepository.findByRunId(runId)).thenReturn(Optional.of(agentRun));
        when(agentRun.getStatus()).thenReturn(AgentRunStatus.RUNNING);

        // when
        consumer.consume(createEvent(runId, "화이트"));

        // then
        verify(agentRun, never()).resolveOptionSelection(any());
        verify(agentRunRepository, never()).save(any());
    }
}
