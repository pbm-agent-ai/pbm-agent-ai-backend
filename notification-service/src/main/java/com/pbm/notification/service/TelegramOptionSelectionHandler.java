package com.pbm.notification.service;

import com.pbm.notification.client.TelegramBotClient;
import com.pbm.notification.dto.PendingOptionSelection;
import com.pbm.notification.dto.event.OptionSelectionRequestEvent;
import com.pbm.notification.dto.event.OptionSelectionResponseEvent;
import com.pbm.notification.publisher.OptionSelectionResponsePublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 텔레그램 웹훅으로 수신한 사용자 응답에서 옵션 선택을 처리하는 핸들러.
 *
 * 역할: 사용자가 옵션 번호("1") 또는 옵션명("블랙")으로 응답하면
 *       해당 옵션 값을 매칭하여 command-service에 Kafka 이벤트를 발행한다.
 * 연관: TelegramWebhookController, PendingOptionStore, OptionSelectionResponsePublisher.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TelegramOptionSelectionHandler {

    private final PendingOptionStore pendingOptionStore;
    private final OptionSelectionResponsePublisher responsePublisher;
    private final TelegramBotClient telegramBotClient;

    /**
     * 텔레그램 사용자 응답을 처리한다.
     *
     * @param chatId 텔레그램 chat ID
     * @param text   사용자가 입력한 텍스트 (번호 또는 옵션명)
     * @return true이면 옵션 선택 요청이 있어서 처리됨, false이면 대기 중인 요청 없음
     */
    public boolean handleReply(String chatId, String text) {
        PendingOptionSelection pending = pendingOptionStore.findByChatId(chatId);
        if (pending == null) {
            return false; // 대기 중인 옵션 선택 요청 없음
        }

        // 응답에서 옵션 값 매칭
        String selectedValue = resolveSelection(text, pending.optionGroups());
        if (selectedValue == null) {
            // 매칭 실패 → 재입력 요청
            telegramBotClient.sendMessage(chatId,
                    "⚠️ 올바른 옵션을 선택해주세요.\n번호(예: 1) 또는 옵션명(예: 블랙)을 입력해주세요.");
            log.warn("옵션 매칭 실패 - chatId: {}, text: {}", chatId, text);
            return true;
        }

        // 매칭 성공 → Kafka 이벤트 발행
        OptionSelectionResponseEvent event = new OptionSelectionResponseEvent(
                UUID.randomUUID().toString(),
                "OPTION_SELECTED",
                Instant.now(),
                "notification-service",
                new OptionSelectionResponseEvent.Payload(
                        pending.userId(),
                        pending.runId(),
                        selectedValue
                )
        );
        responsePublisher.publish(event);

        // 대기 요청 제거 + 확인 메시지 전송
        pendingOptionStore.remove(chatId);
        telegramBotClient.sendMessage(chatId,
                "✅ <b>'" + selectedValue + "'</b> 옵션이 선택되었습니다. 구매를 진행합니다.");

        log.info("옵션 선택 완료 - chatId: {}, runId: {}, selected: {}", chatId, pending.runId(), selectedValue);
        return true;
    }

    /**
     * 사용자 응답에서 옵션 값을 매칭한다.
     * 1) 번호 입력: "1" → 첫 번째 그룹의 첫 번째 옵션
     * 2) 텍스트 입력: "블랙" → 옵션 목록에서 포함 여부로 매칭
     */
    private String resolveSelection(String reply, List<OptionSelectionRequestEvent.OptionGroup> groups) {
        if (groups == null || groups.isEmpty()) return null;

        String trimmed = reply.trim();

        // 모든 옵션을 flat 목록으로 펼침 (여러 그룹이 있어도 번호는 순차)
        List<String> allOptions = groups.stream()
                .flatMap(g -> g.options().stream())
                .toList();

        // 1) 번호 매칭
        try {
            int index = Integer.parseInt(trimmed) - 1;
            if (index >= 0 && index < allOptions.size()) {
                return allOptions.get(index);
            }
        } catch (NumberFormatException ignored) {
            // 숫자가 아니면 텍스트 매칭으로 진행
        }

        // 2) 텍스트 정확 매칭 (대소문자 무시)
        String lowerReply = trimmed.toLowerCase();
        for (String option : allOptions) {
            if (option.toLowerCase().equals(lowerReply)) {
                return option;
            }
        }

        // 3) 텍스트 포함 매칭
        for (String option : allOptions) {
            if (option.toLowerCase().contains(lowerReply) || lowerReply.contains(option.toLowerCase())) {
                return option;
            }
        }

        return null;
    }
}
