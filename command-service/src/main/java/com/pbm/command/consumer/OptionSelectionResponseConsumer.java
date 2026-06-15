package com.pbm.command.consumer;

import com.pbm.command.domain.AgentRun;
import com.pbm.command.domain.AgentRunStatus;
import com.pbm.command.dto.event.OptionSelectionResponseEvent;
import com.pbm.command.repository.AgentRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 텔레그램 옵션 선택 응답 이벤트를 소비하여 AgentRun을 재개하는 컨슈머.
 *
 * 역할: notification-service가 텔레그램 webhook으로 사용자 응답을 수신한 뒤
 *       발행한 옵션 선택 결과를 처리하여 대기 중인 AgentRun을 RUNNING으로 전환한다.
 * 연관: OptionSelectionResponseEvent, AgentRun, TelegramOptionSelectionHandler.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OptionSelectionResponseConsumer {

    private final AgentRunRepository agentRunRepository;

    @KafkaListener(
            topics = "${app.kafka.topics.option-selection-response-topic}",
            groupId = "command-service-option",
            properties = {
                    "spring.json.value.default.type=com.pbm.command.dto.event.OptionSelectionResponseEvent",
                    "spring.json.use.type.headers=false"
            }
    )
    @Transactional
    public void consume(OptionSelectionResponseEvent event) {
        var payload = event.payload();
        log.info("옵션 선택 응답 수신 - runId: {}, selectedValue: {}", payload.runId(), payload.selectedValue());

        AgentRun run = agentRunRepository.findByRunId(payload.runId()).orElse(null);
        if (run == null) {
            log.warn("AgentRun을 찾을 수 없음 - runId: {}", payload.runId());
            return;
        }

        if (run.getStatus() != AgentRunStatus.AWAITING_OPTION_SELECTION) {
            log.warn("AgentRun이 옵션 대기 상태가 아님 - runId: {}, status: {}", payload.runId(), run.getStatus());
            return;
        }

        run.resolveOptionSelection(payload.selectedValue());
        agentRunRepository.save(run);

        log.info("옵션 선택으로 AgentRun 재개 - runId: {}, selected: {}", payload.runId(), payload.selectedValue());
    }
}
