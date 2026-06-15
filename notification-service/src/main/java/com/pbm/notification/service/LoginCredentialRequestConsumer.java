package com.pbm.notification.service;

import com.pbm.notification.client.TelegramBotClient;
import com.pbm.notification.domain.NotificationPreference;
import com.pbm.notification.dto.PendingLoginCredentialRequest;
import com.pbm.notification.dto.event.LoginCredentialRequestEvent;
import com.pbm.notification.repository.NotificationPreferenceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * 로그인 자격증명 요청 이벤트를 소비하여 텔레그램으로 아이디/비밀번호 입력 메시지를 전송한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoginCredentialRequestConsumer {

    private static final long TIMEOUT_SECONDS = 180;

    private final TelegramBotClient telegramBotClient;
    private final NotificationPreferenceRepository preferenceRepository;
    private final PendingLoginCredentialStore pendingLoginCredentialStore;

    @KafkaListener(
            topics = "${app.kafka.topics.login-credential-request}",
            groupId = "notification-service-login-credential",
            properties = {
                    "spring.json.value.default.type=com.pbm.notification.dto.event.LoginCredentialRequestEvent"
            }
    )
    public void consume(LoginCredentialRequestEvent event) {
        var payload = event.payload();
        log.info("로그인 자격증명 요청 수신 - userId: {}, runId: {}",
                payload.userId(), payload.runId());

        NotificationPreference pref = preferenceRepository.findByUserId(payload.userId()).orElse(null);
        if (pref == null || !pref.isTelegramLinked()) {
            log.warn("텔레그램 미연동 사용자 - userId: {}, 로그인 자격증명 요청 무시", payload.userId());
            return;
        }

        String chatId = pref.getTelegramChatId();
        String message = buildCredentialMessage(payload.productName());
        telegramBotClient.sendMessage(chatId, message);

        pendingLoginCredentialStore.save(chatId, new PendingLoginCredentialRequest(
                payload.runId(),
                payload.userId(),
                Instant.now().plusSeconds(TIMEOUT_SECONDS)
        ));

        log.info("로그인 자격증명 텔레그램 전송 완료 - chatId: {}, runId: {}", chatId, payload.runId());
    }

    private String buildCredentialMessage(String productName) {
        StringBuilder sb = new StringBuilder();
        sb.append("🔐 <b>로그인 정보가 필요합니다</b>\n");
        if (productName != null && !productName.isBlank()) {
            sb.append("상품: ").append(productName).append("\n");
        }
        sb.append("\n");
        sb.append("아래 형식으로 아이디와 비밀번호를 보내주세요.\n\n");
        sb.append("ID: your-id\n");
        sb.append("PW: your-password\n\n");
        sb.append("3분 이내에 보내주시면 자동 입력 후 로그인 버튼까지 진행합니다.");
        return sb.toString();
    }
}
