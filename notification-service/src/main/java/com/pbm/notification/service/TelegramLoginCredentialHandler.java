package com.pbm.notification.service;

import com.pbm.notification.client.TelegramBotClient;
import com.pbm.notification.dto.PendingLoginCredentialRequest;
import com.pbm.notification.dto.event.LoginCredentialResponseEvent;
import com.pbm.notification.publisher.LoginCredentialResponsePublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 텔레그램 웹훅으로 수신한 로그인 아이디/비밀번호 응답을 처리하는 핸들러.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TelegramLoginCredentialHandler {

    private static final Pattern ID_PATTERN = Pattern.compile("(?im)^(?:id|아이디|username)\\s*[:=]\\s*(.+)$");
    private static final Pattern PW_PATTERN = Pattern.compile("(?im)^(?:pw|password|비밀번호)\\s*[:=]\\s*(.+)$");

    private final PendingLoginCredentialStore pendingLoginCredentialStore;
    private final LoginCredentialResponsePublisher responsePublisher;
    private final TelegramBotClient telegramBotClient;

    public boolean handleReply(String chatId, String text) {
        PendingLoginCredentialRequest pending = pendingLoginCredentialStore.findByChatId(chatId);
        if (pending == null) {
            return false;
        }

        CredentialPair credentials = parseCredentials(text);
        if (credentials == null) {
            telegramBotClient.sendMessage(chatId,
                    "⚠️ 로그인 정보를 다시 보내주세요.\n아래 형식을 꼭 지켜주세요.\n\nID: your-id\nPW: your-password");
            log.warn("로그인 자격증명 파싱 실패 - chatId: {}, textLength: {}", chatId, text == null ? 0 : text.length());
            return true;
        }

        LoginCredentialResponseEvent event = new LoginCredentialResponseEvent(
                UUID.randomUUID().toString(),
                "LOGIN_CREDENTIALS_PROVIDED",
                Instant.now(),
                "notification-service",
                new LoginCredentialResponseEvent.Payload(
                        pending.userId(),
                        pending.runId(),
                        credentials.username(),
                        credentials.password()
                )
        );
        responsePublisher.publish(event);

        pendingLoginCredentialStore.remove(chatId);
        telegramBotClient.sendMessage(chatId,
                "✅ 로그인 정보를 받았습니다. 자동 입력 후 로그인 절차를 이어서 진행합니다.");

        log.info("로그인 자격증명 수신 완료 - chatId: {}, runId: {}, username: {}, passwordLength: {}",
                chatId, pending.runId(), maskUsername(credentials.username()), credentials.password().length());
        return true;
    }

    private CredentialPair parseCredentials(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }

        Matcher idMatcher = ID_PATTERN.matcher(text);
        Matcher pwMatcher = PW_PATTERN.matcher(text);

        String username = idMatcher.find() ? idMatcher.group(1).trim() : null;
        String password = pwMatcher.find() ? pwMatcher.group(1).trim() : null;

        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            return null;
        }

        return new CredentialPair(username, password);
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

    private record CredentialPair(String username, String password) {
    }
}
