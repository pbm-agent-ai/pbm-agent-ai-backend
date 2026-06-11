package com.pbm.notification.service;

import com.pbm.notification.dto.PendingOptionSelection;
import com.pbm.notification.dto.event.OptionSelectionRequestEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PendingOptionStore 단위 테스트.
 * 저장/조회/제거/만료 정리 기능을 검증한다.
 */
class PendingOptionStoreTest {

    private PendingOptionStore store;

    @BeforeEach
    void setUp() {
        store = new PendingOptionStore();
    }

    private PendingOptionSelection createPending(String runId, Instant expiresAt) {
        return new PendingOptionSelection(
                runId, 1L,
                List.of(new OptionSelectionRequestEvent.OptionGroup("색상", List.of("블랙", "화이트"))),
                expiresAt
        );
    }

    @Test
    @DisplayName("저장 후 조회 성공")
    void save_andFind_returnsStored() {
        // given
        PendingOptionSelection pending = createPending("run-1", Instant.now().plusSeconds(180));
        store.save("chat-1", pending);

        // when
        PendingOptionSelection found = store.findByChatId("chat-1");

        // then
        assertThat(found).isNotNull();
        assertThat(found.runId()).isEqualTo("run-1");
    }

    @Test
    @DisplayName("만료된 항목 조회 시 null 반환")
    void find_expired_returnsNull() {
        // given - 이미 만료된 항목
        PendingOptionSelection pending = createPending("run-2", Instant.now().minusSeconds(10));
        store.save("chat-2", pending);

        // when
        PendingOptionSelection found = store.findByChatId("chat-2");

        // then
        assertThat(found).isNull();
    }

    @Test
    @DisplayName("remove 후 조회 시 null 반환")
    void remove_thenFind_returnsNull() {
        // given
        store.save("chat-3", createPending("run-3", Instant.now().plusSeconds(180)));
        store.remove("chat-3");

        // when/then
        assertThat(store.findByChatId("chat-3")).isNull();
    }

    @Test
    @DisplayName("cleanExpired → 만료 항목 제거, 유효 항목 유지")
    void cleanExpired_removesOnlyExpired() {
        // given
        store.save("expired", createPending("run-exp", Instant.now().minusSeconds(10)));
        store.save("valid", createPending("run-val", Instant.now().plusSeconds(180)));

        // when
        store.cleanExpired();

        // then
        assertThat(store.findByChatId("expired")).isNull();
        assertThat(store.findByChatId("valid")).isNotNull();
    }
}
