package com.pbm.notification.controller;

import com.pbm.notification.common.ApiResponse;
import com.pbm.notification.dto.request.NotificationPreferenceRequest;
import com.pbm.notification.dto.response.NotificationPreferenceResponse;
import com.pbm.notification.service.EmailNotificationSender;
import com.pbm.notification.service.NotificationPreferenceService;
import com.pbm.notification.client.TelegramBotClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 알림 설정 REST API 컨트롤러.
 *
 * 역할: 프론트엔드에서 사용자의 알림 채널(이메일, 텔레그램)을 설정/조회할 수 있는 엔드포인트를 제공한다.
 * 동작: Gateway가 JWT 검증 후 주입한 X-User-Id 헤더를 받아 사용자를 식별한다.
 * 연관: NotificationPreferenceService.
 */
@Slf4j
@Tag(name = "Notification Preferences", description = "알림 설정 API")
@RestController
@RequestMapping("/api/notifications/preferences")
@RequiredArgsConstructor
public class NotificationPreferenceController {

    private final NotificationPreferenceService preferenceService;
    private final EmailNotificationSender emailSender;
    private final TelegramBotClient telegramBotClient;

    /**
     * 현재 사용자의 알림 설정을 조회한다.
     *
     * @param userId Gateway가 주입한 사용자 ID (X-User-Id)
     * @return 알림 설정 응답
     */
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "알림 설정 조회", description = "현재 사용자의 알림 채널 설정 상태를 조회합니다.")
    @GetMapping
    public ResponseEntity<ApiResponse<NotificationPreferenceResponse>> getPreference(
            @RequestHeader("X-User-Id") Long userId
    ) {
        NotificationPreferenceResponse response = preferenceService.getPreference(userId);
        return ResponseEntity.ok(ApiResponse.success(response, "알림 설정 조회 성공"));
    }

    /**
     * 현재 사용자의 알림 설정을 저장/수정한다.
     *
     * @param userId  Gateway가 주입한 사용자 ID (X-User-Id)
     * @param request 알림 설정 요청 DTO
     * @return 수정된 알림 설정 응답
     */
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "알림 설정 저장", description = "알림 채널(이메일, 텔레그램) 활성화/비활성화 및 이메일 주소를 설정합니다.")
    @PostMapping
    public ResponseEntity<ApiResponse<NotificationPreferenceResponse>> updatePreference(
            @RequestHeader("X-User-Id") Long userId,
            @RequestBody NotificationPreferenceRequest request
    ) {
        NotificationPreferenceResponse response = preferenceService.updatePreference(userId, request);
        return ResponseEntity.ok(ApiResponse.success(response, "알림 설정 저장 성공"));
    }

    /**
     * 텔레그램 연동을 해제한다.
     *
     * @param userId Gateway가 주입한 사용자 ID (X-User-Id)
     * @return 성공 응답
     */
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "텔레그램 연동 해제", description = "텔레그램 봇 연동을 해제하고 알림을 비활성화합니다.")
    @DeleteMapping("/telegram")
    public ResponseEntity<ApiResponse<Void>> unlinkTelegram(
            @RequestHeader("X-User-Id") Long userId
    ) {
        preferenceService.unlinkTelegram(userId);
        return ResponseEntity.ok(ApiResponse.success("텔레그램 연동 해제 성공"));
    }

    /**
     * 현재 사용자의 텔레그램으로 테스트 메시지를 발송한다.
     *
     * @param userId Gateway가 주입한 사용자 ID (X-User-Id)
     * @return 발송 결과 응답
     */
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "텔레그램 테스트 발송", description = "저장된 텔레그램 chatId로 테스트 메시지를 발송합니다.")
    @PostMapping("/telegram/test")
    public ResponseEntity<ApiResponse<Void>> sendTelegramTest(
            @RequestHeader("X-User-Id") Long userId
    ) {
        try {
            String chatId = preferenceService.getTelegramChatId(userId);
            telegramBotClient.sendMessage(chatId,
                    "🔔 CustosPay 텔레그램 알림 테스트입니다.\n\n"
                            + "현재 텔레그램 알림이 정상적으로 연결되어 있습니다.");
            log.info("텔레그램 테스트 발송 성공 - userId: {}, chatId: {}", userId, chatId);
            return ResponseEntity.ok(ApiResponse.success("텔레그램 테스트 발송 성공"));
        } catch (Exception e) {
            log.warn("텔레그램 테스트 발송 실패 - userId: {}, error: {}", userId, e.getMessage());
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("텔레그램 테스트 발송에 실패했습니다: " + e.getMessage()));
        }
    }

    /**
     * 텔레그램 chatId를 직접 입력하여 봇을 연동한다.
     * 사용자가 프론트에서 본인의 텔레그램 chatId를 입력하면 DB에 저장하고,
     * 봇으로 연동 확인 메시지를 전송해 유효성을 검증한다.
     *
     * @param userId Gateway가 주입한 사용자 ID (X-User-Id)
     * @param body   { "chatId": "7326133334" }
     * @return 연동 결과 응답
     */
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "텔레그램 chatId 직접 연동", description = "텔레그램 chatId를 입력하여 봇을 연동합니다. 연동 확인 메시지가 전송됩니다.")
    @PostMapping("/telegram")
    public ResponseEntity<ApiResponse<NotificationPreferenceResponse>> linkTelegramByChatId(
            @RequestHeader("X-User-Id") Long userId,
            @RequestBody Map<String, String> body
    ) {
        String chatId = body.get("chatId");
        if (chatId == null || chatId.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("텔레그램 chatId를 입력해주세요."));
        }

        // chatId 숫자 검증
        try {
            Long.parseLong(chatId.trim());
        } catch (NumberFormatException e) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("올바른 텔레그램 chatId(숫자)를 입력해주세요."));
        }

        // 봇으로 확인 메시지를 보내서 chatId가 유효한지 검증
        try {
            telegramBotClient.sendMessage(chatId.trim(),
                    "🔔 CustosPay 알림 봇이 연동되었습니다!\n이제 가격 알림과 결제 알림을 텔레그램으로 받을 수 있습니다.");
        } catch (Exception e) {
            log.warn("텔레그램 chatId 검증 실패 - chatId: {}, error: {}", chatId, e.getMessage());
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("텔레그램 메시지 전송에 실패했습니다. 봇(@CustosPay_bot)에게 먼저 /start를 보내주세요."));
        }

        // DB에 chatId 저장 + 텔레그램 활성화
        preferenceService.linkTelegram(userId, chatId.trim());
        NotificationPreferenceResponse response = preferenceService.getPreference(userId);

        log.info("텔레그램 chatId 직접 연동 완료 - userId: {}, chatId: {}", userId, chatId);
        return ResponseEntity.ok(ApiResponse.success(response, "텔레그램 연동 성공"));
    }

    /**
     * 이메일 테스트 발송 API.
     * 입력된 이메일 주소로 테스트 메일을 발송하여 SMTP 설정이 정상인지 확인한다.
     *
     * @param userId Gateway가 주입한 사용자 ID (X-User-Id)
     * @param body   { "email": "your@email.com" }
     * @return 발송 결과 응답
     */
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "이메일 테스트 발송", description = "입력된 이메일 주소로 테스트 알림 메일을 발송합니다.")
    @PostMapping("/email/test")
    public ResponseEntity<ApiResponse<Void>> sendTestEmail(
            @RequestHeader("X-User-Id") Long userId,
            @RequestBody Map<String, String> body
    ) {
        String email = body.get("email");
        if (email == null || email.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("이메일 주소를 입력해주세요."));
        }

        try {
            emailSender.send(
                    email.trim(),
                    "[CustosPay] 이메일 알림 테스트",
                    "CustosPay 이메일 알림이 정상적으로 설정되었습니다.\n\n"
                            + "이 메일은 테스트용이며, 실제 가격 알림 및 결제 알림이 이 주소로 발송됩니다.\n\n"
                            + "- CustosPay AI"
            );
            log.info("이메일 테스트 발송 성공 - userId: {}, email: {}", userId, email);
            return ResponseEntity.ok(ApiResponse.success("테스트 이메일 발송 성공"));
        } catch (Exception e) {
            log.error("이메일 테스트 발송 실패 - userId: {}, email: {}, error: {}", userId, email, e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error("이메일 발송에 실패했습니다: " + e.getMessage()));
        }
    }
}
