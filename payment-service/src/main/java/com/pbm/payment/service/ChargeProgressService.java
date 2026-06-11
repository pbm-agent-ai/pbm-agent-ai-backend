package com.pbm.payment.service;

import com.pbm.payment.dto.response.ChargeProgressEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 토큰 충전 진행 단계 SSE 이벤트 관리 서비스.
 * <p>
 * 역할: 사용자별 SseEmitter를 메모리에 보관하고, 충전 흐름의 각 단계에서
 *       실시간으로 이벤트를 프론트엔드에 push한다.
 * 생명주기:
 *   1. subscribe() → SseEmitter 생성 및 등록, "CONNECTED" 이벤트 발송
 *   2. emit() → 각 충전 단계마다 호출 (TX_SENT, TX_CONFIRMED 등)
 *   3. complete() → 정상 완료 시 "DONE" 이벤트 후 스트림 종료
 *   4. error() → 실패 시 "FAILED" 이벤트 후 스트림 종료
 */
@Slf4j
@Service
public class ChargeProgressService {

    /** SSE 스트림 타임아웃: 5분 (블록체인 처리 최대 대기 시간 포함) */
    private static final long SSE_TIMEOUT_MS = 300_000L;

    /** userId → SseEmitter 맵 (충전 진행 중인 사용자만 존재) */
    private final Map<Long, SseEmitter> emitters = new ConcurrentHashMap<>();

    /**
     * 사용자의 충전 진행 SSE 스트림을 구독한다.
     * <p>
     * 기존 구독이 있으면 먼저 종료하고 새 emitter를 등록한다.
     * 등록 직후 "CONNECTED" 이벤트를 발송하여 프론트엔드가 확인 후 충전 요청을 보낼 수 있게 한다.
     *
     * @param userId 사용자 식별자
     * @return 생성된 SseEmitter (컨트롤러가 응답으로 반환)
     */
    public SseEmitter subscribe(Long userId) {
        // 기존 emitter가 있으면 정리
        SseEmitter existing = emitters.remove(userId);
        if (existing != null) {
            try { existing.complete(); } catch (Exception ignored) {}
        }

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        emitters.put(userId, emitter);

        emitter.onCompletion(() -> {
            emitters.remove(userId, emitter);
            log.debug("SSE 스트림 완료 - userId: {}", userId);
        });
        emitter.onTimeout(() -> {
            emitters.remove(userId, emitter);
            emitter.complete();
            log.warn("SSE 스트림 타임아웃 - userId: {}", userId);
        });
        emitter.onError(e -> {
            emitters.remove(userId, emitter);
            log.warn("SSE 스트림 오류 - userId: {}, 원인: {}", userId, e.getMessage());
        });

        // 연결 확인 이벤트 (프론트엔드는 이 이벤트를 받은 후 충전 요청 전송)
        sendEvent(userId, emitter, "CONNECTED",
                ChargeProgressEvent.of("CONNECTED", "스트림 연결됨"));

        log.info("충전 진행 SSE 구독 완료 - userId: {}", userId);
        return emitter;
    }

    /**
     * 특정 사용자에게 충전 진행 단계 이벤트를 발송한다.
     *
     * @param userId  대상 사용자
     * @param step    단계 식별자 (SSE 이벤트 name)
     * @param message 단계 설명
     */
    public void emit(Long userId, String step, String message) {
        emit(userId, step, message, null);
    }

    /**
     * 특정 사용자에게 충전 진행 단계 이벤트를 발송한다 (상세 정보 포함).
     *
     * @param userId  대상 사용자
     * @param step    단계 식별자 (SSE 이벤트 name)
     * @param message 단계 설명
     * @param detail  추가 정보 (txHash, 금액 등)
     */
    public void emit(Long userId, String step, String message, String detail) {
        SseEmitter emitter = emitters.get(userId);
        if (emitter == null) {
            log.debug("SSE emitter 없음 (이미 완료 또는 미구독) - userId: {}, step: {}", userId, step);
            return;
        }
        sendEvent(userId, emitter, step, ChargeProgressEvent.of(step, message, detail));
    }

    /**
     * 충전 처리가 정상 완료되었을 때 "DONE" 이벤트를 발송하고 스트림을 종료한다.
     *
     * @param userId 사용자 식별자
     */
    public void complete(Long userId) {
        SseEmitter emitter = emitters.remove(userId);
        if (emitter == null) return;
        try {
            emitter.send(SseEmitter.event()
                    .name("DONE")
                    .data(ChargeProgressEvent.of("DONE", "충전 및 수수료 처리 완료")));
            emitter.complete();
        } catch (IOException e) {
            log.warn("DONE 이벤트 전송 실패 - userId: {}", userId);
        }
    }

    /**
     * 충전 처리 실패 시 "FAILED" 이벤트를 발송하고 스트림을 종료한다.
     *
     * @param userId  사용자 식별자
     * @param reason  실패 원인 메시지
     */
    public void error(Long userId, String reason) {
        SseEmitter emitter = emitters.remove(userId);
        if (emitter == null) return;
        try {
            emitter.send(SseEmitter.event()
                    .name("FAILED")
                    .data(ChargeProgressEvent.of("FAILED", "충전 실패", reason)));
            emitter.complete();
        } catch (IOException e) {
            log.warn("FAILED 이벤트 전송 실패 - userId: {}", userId);
        }
    }

    // ── private 헬퍼 ─────────────────────────────────────────────────────

    private void sendEvent(Long userId, SseEmitter emitter, String eventName, ChargeProgressEvent payload) {
        try {
            emitter.send(SseEmitter.event()
                    .name(eventName)
                    .data(payload));
            log.info("SSE 이벤트 발송 - userId: {}, step: {}, message: {}",
                    userId, payload.step(), payload.message());
        } catch (IOException e) {
            emitters.remove(userId, emitter);
            log.warn("SSE 이벤트 전송 실패 (클라이언트 끊김) - userId: {}, step: {}", userId, eventName);
        }
    }
}
