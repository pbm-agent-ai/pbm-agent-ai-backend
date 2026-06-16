package com.pbm.payment.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SessionKeyProgressService 단위 테스트.
 *
 * 검증 대상:
 * - SSE 구독 시 SseEmitter 반환
 * - 이벤트 발송(emit)이 예외 없이 동작
 * - complete/error 호출이 예외 없이 동작
 * - 미구독 상태에서 emit 호출 시 무시
 */
class SessionKeyProgressServiceTest {

    private SessionKeyProgressService service;

    @BeforeEach
    void setUp() {
        service = new SessionKeyProgressService();
    }

    @Test
    @DisplayName("구독 시 SseEmitter를 반환한다")
    void subscribe_반환값_확인() {
        SseEmitter emitter = service.subscribe(1L);
        assertThat(emitter).isNotNull();
    }

    @Test
    @DisplayName("구독 후 이벤트 발송이 정상 동작한다")
    void emit_정상_동작() {
        service.subscribe(1L);
        service.emit(1L, "WALLET_CHECK", "지갑 조회 중");
        service.emit(1L, "AI_AGENT_FUNDED", "ETH 지원 완료", "0xABC");
    }

    @Test
    @DisplayName("미구독 상태에서 emit 호출 시 예외가 발생하지 않는다")
    void emit_미구독_무시() {
        service.emit(999L, "WALLET_CHECK", "지갑 조회 중");
    }

    @Test
    @DisplayName("complete 호출이 정상 동작한다")
    void complete_정상_동작() {
        service.subscribe(1L);
        service.complete(1L, "0xTxHash");
    }

    @Test
    @DisplayName("error 호출이 정상 동작한다")
    void error_정상_동작() {
        service.subscribe(1L);
        service.error(1L, "등록 실패");
    }

    @Test
    @DisplayName("중복 구독 시 기존 emitter를 교체한다")
    void subscribe_중복_구독_교체() {
        SseEmitter first = service.subscribe(1L);
        SseEmitter second = service.subscribe(1L);
        assertThat(first).isNotSameAs(second);
    }
}
