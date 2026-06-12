package com.pbm.notification.service;

import com.pbm.notification.client.TelegramBotClient;
import com.pbm.notification.domain.NotificationPreference;
import com.pbm.notification.dto.PendingOptionSelection;
import com.pbm.notification.dto.event.OptionSelectionRequestEvent;
import com.pbm.notification.repository.NotificationPreferenceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * 상품 옵션 선택 요청 이벤트를 소비하여 텔레그램으로 옵션 목록을 전송하는 컨슈머.
 *
 * 역할: command-service가 옵션 선택이 필요하다고 판단하면 이 컨슈머가 이벤트를 수신하여
 *       사용자에게 텔레그램으로 옵션 목록을 발송하고, 응답을 기다린다.
 * 연관: OptionSelectionRequestEvent, PendingOptionStore, TelegramBotClient.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OptionSelectionRequestConsumer {

    /** 옵션 선택 대기 타임아웃 (3분) */
    private static final long TIMEOUT_SECONDS = 180;

    private final TelegramBotClient telegramBotClient;
    private final NotificationPreferenceRepository preferenceRepository;
    private final PendingOptionStore pendingOptionStore;

    @KafkaListener(
            topics = "${app.kafka.topics.option-selection-request}",
            groupId = "notification-service",
            properties = {
                    "spring.json.value.default.type=com.pbm.notification.dto.event.OptionSelectionRequestEvent"
            }
    )
    public void consume(OptionSelectionRequestEvent event) {
        var payload = event.payload();
        log.info("옵션 선택 요청 수신 - userId: {}, runId: {}, groups: {}",
                payload.userId(), payload.runId(), payload.optionGroups().size());
        log.info("[OptionSelectionRequestConsumer] 텔레그램 옵션 요청 payload 디버그 - runId={}, groups={}",
                payload.runId(), summarizeOptionGroups(payload.optionGroups()));

        // userId → chatId 조회
        NotificationPreference pref = preferenceRepository.findByUserId(payload.userId()).orElse(null);
        if (pref == null || !pref.isTelegramLinked()) {
            log.warn("텔레그램 미연동 사용자 - userId: {}, 옵션 선택 요청 무시", payload.userId());
            return;
        }

        String chatId = pref.getTelegramChatId();

        // 텔레그램 메시지 구성
        String message = buildOptionMessage(payload.productName(), payload.optionGroups());
        telegramBotClient.sendMessage(chatId, message);

        // 대기 요청 저장 (3분 TTL)
        PendingOptionSelection pending = new PendingOptionSelection(
                payload.runId(),
                payload.userId(),
                payload.optionGroups(),
                Instant.now().plusSeconds(TIMEOUT_SECONDS)
        );
        pendingOptionStore.save(chatId, pending);

        log.info("옵션 선택 텔레그램 전송 완료 - chatId: {}, runId: {}", chatId, payload.runId());
    }

    /**
     * 텔레그램으로 보낼 옵션 선택 메시지를 구성한다.
     * 각 옵션 그룹의 옵션을 번호 목록으로 표시한다.
     */
    private String buildOptionMessage(
            String productName,
            List<OptionSelectionRequestEvent.OptionGroup> optionGroups
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append("🛒 <b>상품 옵션을 선택해주세요</b>\n");
        if (productName != null && !productName.isBlank()) {
            sb.append("상품: ").append(productName).append("\n");
        }
        sb.append("\n");

        for (var group : optionGroups) {
            // 옵션이 없는 그룹은 표시하지 않음 (종속 옵션에서 아직 비활성인 경우)
            List<String> options = group.options();
            if (options == null || options.isEmpty()) {
                continue;
            }
            sb.append("📋 <b>").append(group.groupName()).append("</b>\n");
            for (int i = 0; i < options.size(); i++) {
                sb.append(i + 1).append(". ").append(options.get(i)).append("\n");
            }
            sb.append("\n");
        }

        sb.append("번호 또는 옵션명을 입력해주세요. (3분 이내)");
        return sb.toString();
    }

    private List<String> summarizeOptionGroups(List<OptionSelectionRequestEvent.OptionGroup> optionGroups) {
        if (optionGroups == null) {
            return List.of();
        }
        return optionGroups.stream()
                .map(group -> String.format(
                        "group=%s options=%d values=%s",
                        group.groupName(),
                        group.options() == null ? 0 : group.options().size(),
                        group.options()
                ))
                .toList();
    }
}
