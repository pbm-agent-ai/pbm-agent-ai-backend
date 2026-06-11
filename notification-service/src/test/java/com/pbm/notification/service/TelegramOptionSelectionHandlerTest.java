package com.pbm.notification.service;

import com.pbm.notification.client.TelegramBotClient;
import com.pbm.notification.dto.PendingOptionSelection;
import com.pbm.notification.dto.event.OptionSelectionRequestEvent;
import com.pbm.notification.dto.event.OptionSelectionResponseEvent;
import com.pbm.notification.publisher.OptionSelectionResponsePublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * TelegramOptionSelectionHandler 단위 테스트.
 * 번호/텍스트 매칭, 매칭 실패 재요청, 대기 요청 없음 시나리오를 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class TelegramOptionSelectionHandlerTest {

    @Mock private PendingOptionStore pendingOptionStore;
    @Mock private OptionSelectionResponsePublisher responsePublisher;
    @Mock private TelegramBotClient telegramBotClient;

    private TelegramOptionSelectionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new TelegramOptionSelectionHandler(pendingOptionStore, responsePublisher, telegramBotClient);
    }

    private PendingOptionSelection createPending(String runId) {
        List<OptionSelectionRequestEvent.OptionGroup> groups = List.of(
                new OptionSelectionRequestEvent.OptionGroup("색상", List.of("블랙", "화이트", "실버"))
        );
        return new PendingOptionSelection(runId, 1L, groups, Instant.now().plusSeconds(180));
    }

    @Test
    @DisplayName("번호 입력 '1' → 첫 번째 옵션 '블랙' 매칭, Kafka 발행")
    void handleReply_numberInput_matchesFirstOption() {
        // given
        String chatId = "chat-100";
        PendingOptionSelection pending = createPending("run-1");
        when(pendingOptionStore.findByChatId(chatId)).thenReturn(pending);

        // when
        boolean handled = handler.handleReply(chatId, "1");

        // then
        assertThat(handled).isTrue();
        ArgumentCaptor<OptionSelectionResponseEvent> captor = ArgumentCaptor.forClass(OptionSelectionResponseEvent.class);
        verify(responsePublisher).publish(captor.capture());
        assertThat(captor.getValue().payload().selectedValue()).isEqualTo("블랙");
        verify(pendingOptionStore).remove(chatId);
    }

    @Test
    @DisplayName("텍스트 입력 '화이트' → 정확 매칭")
    void handleReply_textInput_exactMatch() {
        // given
        String chatId = "chat-200";
        when(pendingOptionStore.findByChatId(chatId)).thenReturn(createPending("run-2"));

        // when
        boolean handled = handler.handleReply(chatId, "화이트");

        // then
        assertThat(handled).isTrue();
        ArgumentCaptor<OptionSelectionResponseEvent> captor = ArgumentCaptor.forClass(OptionSelectionResponseEvent.class);
        verify(responsePublisher).publish(captor.capture());
        assertThat(captor.getValue().payload().selectedValue()).isEqualTo("화이트");
    }

    @Test
    @DisplayName("매칭 실패 → 재입력 요청 메시지 전송")
    void handleReply_noMatch_sendsRetryMessage() {
        // given
        String chatId = "chat-300";
        when(pendingOptionStore.findByChatId(chatId)).thenReturn(createPending("run-3"));

        // when
        boolean handled = handler.handleReply(chatId, "빨강");

        // then
        assertThat(handled).isTrue();
        verify(responsePublisher, never()).publish(any());
        verify(telegramBotClient).sendMessage(eq(chatId), contains("올바른 옵션"));
    }

    @Test
    @DisplayName("대기 중인 요청 없음 → false 반환")
    void handleReply_noPending_returnsFalse() {
        // given
        when(pendingOptionStore.findByChatId("chat-999")).thenReturn(null);

        // when
        boolean handled = handler.handleReply("chat-999", "블랙");

        // then
        assertThat(handled).isFalse();
        verify(responsePublisher, never()).publish(any());
    }
}
