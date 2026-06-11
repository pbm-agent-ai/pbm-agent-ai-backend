package com.pbm.notification.controller;

import com.pbm.notification.common.ApiResponse;
import com.pbm.notification.service.NotificationPreferenceService;
import com.pbm.notification.service.TelegramOptionSelectionHandler;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 텔레그램 봇 Webhook 수신 컨트롤러.
 *
 * 역할: 텔레그램 봇이 사용자로부터 메시지를 수신하면 이 엔드포인트로 전달된다.
 * 동작:
 *   1. 사용자가 딥링크 t.me/Bot?start={userId} 를 클릭하면 봇이 /start {userId} 메시지를 받는다.
 *   2. Telegram은 해당 메시지를 이 Webhook으로 POST 한다.
 *   3. payload에서 chatId와 userId를 추출하여 연동을 완료한다.
 * 연관: NotificationPreferenceService, TelegramBotClient.
 *
 * 보안: 이 엔드포인트는 Gateway JWT 필터를 거치지 않는 공개 API다.
 *       Telegram 서버에서만 호출해야 하므로, 운영 시 IP 제한을 권장한다.
 */
@Tag(name = "Telegram Webhook", description = "텔레그램 봇 Webhook API")
@Slf4j
@RestController
@RequestMapping("/api/notifications/telegram")
@RequiredArgsConstructor
public class TelegramWebhookController {

    private final NotificationPreferenceService preferenceService;
    private final TelegramOptionSelectionHandler optionSelectionHandler;

    /**
     * 텔레그램 봇 Webhook으로 들어오는 Update를 처리한다.
     * /start {userId} 명령을 인식하여 chatId-userId 연동을 수행한다.
     *
     * @param update Telegram Update JSON (simplified map으로 수신)
     * @return 200 OK (Telegram 서버에게 수신 확인)
     */
    @Operation(summary = "텔레그램 Webhook", description = "텔레그램 봇이 수신한 메시지를 처리합니다. (Telegram 서버 전용)")
    @PostMapping("/webhook")
    public ResponseEntity<String> handleWebhook(@RequestBody Map<String, Object> update) {
        try {
            log.info("텔레그램 Webhook 수신 - updateKeys={}", update.keySet());

            // callback_query 우선 지원 (향후 인라인 버튼 대응)
            @SuppressWarnings("unchecked")
            Map<String, Object> callbackQuery = (Map<String, Object>) update.get("callback_query");
            if (callbackQuery != null) {
                String chatId = extractChatIdFromContainer((Map<String, Object>) callbackQuery.get("message"));
                String data = (String) callbackQuery.get("data");

                log.info("텔레그램 callback_query 수신 - chatId: {}, data: {}", chatId, data);

                if (chatId != null && data != null && !data.isBlank()) {
                    boolean handled = optionSelectionHandler.handleReply(chatId, data.trim());
                    if (!handled) {
                        log.info("텔레그램 callback_query 수신했으나 대기 중 옵션 요청 없음 - chatId: {}, data: {}", chatId, data);
                    }
                }
                return ResponseEntity.ok("ok");
            }

            // Telegram Update 구조에서 message 추출
            @SuppressWarnings("unchecked")
            Map<String, Object> message = (Map<String, Object>) update.get("message");
            if (message == null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> editedMessage = (Map<String, Object>) update.get("edited_message");
                message = editedMessage;
            }

            if (message == null) {
                log.info("텔레그램 Webhook - message/callback_query 없음, 무시");
                return ResponseEntity.ok("ok");
            }

            String chatId = extractChatIdFromContainer(message);
            if (chatId == null) {
                log.info("텔레그램 Webhook - chatId 추출 실패");
                return ResponseEntity.ok("ok");
            }

            // text 추출
            String text = (String) message.get("text");
            if (text == null) {
                log.info("텔레그램 Webhook - text 없음, 무시. chatId={}", chatId);
                return ResponseEntity.ok("ok");
            }

            log.info("텔레그램 message 수신 - chatId: {}, text: {}", chatId, text);

            // /start 명령이 아닌 일반 메시지 → 옵션 선택 응답인지 확인
            if (!text.startsWith("/start ")) {
                boolean handled = optionSelectionHandler.handleReply(chatId, text.trim());
                if (!handled) {
                    log.info("텔레그램 Webhook - 대기 중인 옵션 요청 없음, 메시지 무시. chatId: {}, text: {}", chatId, text);
                }
                return ResponseEntity.ok("ok");
            }

            // /start 뒤의 userId 파라미터 추출
            String userIdStr = text.substring("/start ".length()).trim();
            Long userId;
            try {
                userId = Long.parseLong(userIdStr);
            } catch (NumberFormatException e) {
                log.warn("텔레그램 Webhook - userId 파싱 실패: {}", userIdStr);
                return ResponseEntity.ok("ok");
            }

            // 텔레그램 연동 처리
            preferenceService.linkTelegram(userId, chatId);
            log.info("텔레그램 Webhook 연동 성공 - userId: {}, chatId: {}", userId, chatId);

        } catch (Exception e) {
            log.error("텔레그램 Webhook 처리 중 오류", e);
        }

        // Telegram 서버에게는 항상 200 OK 반환 (실패해도 재시도 방지)
        return ResponseEntity.ok("ok");
    }

    @SuppressWarnings("unchecked")
    private String extractChatIdFromContainer(Map<String, Object> messageContainer) {
        if (messageContainer == null) {
            return null;
        }

        Map<String, Object> chat = (Map<String, Object>) messageContainer.get("chat");
        if (chat == null || chat.get("id") == null) {
            return null;
        }

        return String.valueOf(chat.get("id"));
    }

    /**
     * 텔레그램 연동용 딥링크 URL을 생성하여 반환한다.
     * 프론트엔드에서 "텔레그램 연동" 버튼을 누르면 이 API를 호출한다.
     *
     * @param userId Gateway가 주입한 사용자 ID (X-User-Id)
     * @return 딥링크 URL 응답
     */
    @Operation(summary = "텔레그램 연동 딥링크 생성", description = "사용자가 텔레그램 봇을 연동할 수 있는 딥링크 URL을 반환합니다.")
    @GetMapping("/link")
    public ResponseEntity<ApiResponse<Map<String, String>>> getTelegramLink(
            @RequestHeader("X-User-Id") Long userId,
            @RequestParam String botUsername
    ) {
        // 딥링크 형식: https://t.me/{botUsername}?start={userId}
        String deepLink = String.format("https://t.me/%s?start=%d", botUsername, userId);

        Map<String, String> data = Map.of("deepLink", deepLink);
        return ResponseEntity.ok(ApiResponse.success(data, "텔레그램 연동 링크 생성 성공"));
    }
}
