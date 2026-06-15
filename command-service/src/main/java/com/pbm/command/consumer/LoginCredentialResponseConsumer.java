package com.pbm.command.consumer;

import com.pbm.command.domain.AgentRun;
import com.pbm.command.domain.AgentRunStatus;
import com.pbm.command.dto.event.LoginCredentialResponseEvent;
import com.pbm.command.repository.AgentRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 텔레그램 로그인 자격증명 응답 이벤트를 소비하여 AgentRun을 재개하는 컨슈머.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoginCredentialResponseConsumer {

    private final AgentRunRepository agentRunRepository;

    @KafkaListener(
            topics = "${app.kafka.topics.login-credential-response-topic}",
            groupId = "command-service-login-credential",
            properties = {
                    "spring.json.value.default.type=com.pbm.command.dto.event.LoginCredentialResponseEvent",
                    "spring.json.use.type.headers=false"
            }
    )
    @Transactional
    public void consume(LoginCredentialResponseEvent event) {
        var payload = event.payload();
        log.info("로그인 자격증명 응답 수신 - runId: {}, username: {}, passwordLength: {}",
                payload.runId(), maskUsername(payload.username()), payload.password() == null ? 0 : payload.password().length());

        AgentRun run = agentRunRepository.findByRunId(payload.runId()).orElse(null);
        if (run == null) {
            log.warn("AgentRun을 찾을 수 없음 - runId: {}", payload.runId());
            return;
        }

        if (run.getStatus() != AgentRunStatus.AWAITING_LOGIN_CREDENTIALS) {
            log.warn("AgentRun이 로그인 자격증명 대기 상태가 아님 - runId: {}, status: {}",
                    payload.runId(), run.getStatus());
            return;
        }

        run.resolveLoginCredentials(payload.username(), payload.password());
        agentRunRepository.save(run);

        log.info("로그인 자격증명으로 AgentRun 재개 - runId: {}, username: {}",
                payload.runId(), maskUsername(payload.username()));
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
