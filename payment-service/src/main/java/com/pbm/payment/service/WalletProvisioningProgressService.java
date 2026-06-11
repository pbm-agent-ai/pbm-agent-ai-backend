package com.pbm.payment.service;

import com.pbm.payment.domain.WalletProvisioningStatus;
import com.pbm.payment.dto.response.WalletProvisioningResponse;
import com.pbm.payment.service.WalletProvisioningTracker.State;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 지갑 생성 진행 단계 SSE 이벤트 관리 서비스.
 * <p>
 * 역할: 사용자별 SseEmitter를 메모리에 보관하고, 지갑 생성 흐름의 각 단계에서
 *       WalletProvisioningResponse 페이로드를 실시간으로 프론트엔드에 push한다.
 * <p>
 * SSE 이벤트 name = WalletProvisioningStatus enum 이름 (KEYPAIR_CREATED 등)
 * SSE 이벤트 data = WalletProvisioningResponse JSON (폴링 응답과 동일한 구조)
 * → 프론트엔드의 기존 toProvisioningStatusResponse() 변환 함수를 그대로 재사용 가능
 */
@Slf4j
@Service
public class WalletProvisioningProgressService {

    /** SSE 스트림 타임아웃: 10분 (지갑 배포 최대 대기 시간 포함) */
    private static final long SSE_TIMEOUT_MS = 600_000L;
    private static final int TOTAL_STEPS = 7;

    /** userId → SseEmitter 맵 */
    private final Map<Long, SseEmitter> emitters = new ConcurrentHashMap<>();

    /**
     * 사용자의 지갑 생성 진행 SSE 스트림을 구독한다.
     * 구독 직후 "CONNECTED" 이벤트를 발송하여 프론트엔드가 지갑 생성 요청을 보낼 시점을 알 수 있게 한다.
     *
     * @param userId 사용자 식별자
     * @return 생성된 SseEmitter
     */
    public SseEmitter subscribe(Long userId) {
        // 기존 emitter 정리
        SseEmitter existing = emitters.remove(userId);
        if (existing != null) {
            try { existing.complete(); } catch (Exception ignored) {}
        }

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        emitters.put(userId, emitter);

        emitter.onCompletion(() -> emitters.remove(userId, emitter));
        emitter.onTimeout(() -> {
            emitters.remove(userId, emitter);
            emitter.complete();
        });
        emitter.onError(e -> emitters.remove(userId, emitter));

        // 연결 확인 이벤트
        sendRaw(userId, emitter, "CONNECTED", "{\"connected\":true}");
        log.info("지갑 생성 진행 SSE 구독 완료 - userId: {}", userId);
        return emitter;
    }

    /**
     * 지갑 생성 단계 진행 이벤트를 발송한다.
     * WalletProvisioningResponse 구조로 직렬화하여 프론트엔드의 기존 변환 함수를 재사용한다.
     *
     * @param userId  사용자 식별자
     * @param status  현재 진행 상태
     * @param message 상세 메시지
     */
    public void emit(Long userId, WalletProvisioningStatus status, String message) {
        SseEmitter emitter = emitters.get(userId);
        if (emitter == null) return;

        WalletProvisioningResponse payload = new WalletProvisioningResponse(
                status.name(),
                status.getLabel(),
                message,
                status.getStep(),
                TOTAL_STEPS,
                Instant.now(),
                null
        );

        try {
            emitter.send(SseEmitter.event()
                    .name(status.name())
                    .data(payload));
            log.info("지갑 생성 SSE 이벤트 발송 - userId: {}, step: {}, message: {}",
                    userId, status.name(), message);
        } catch (IOException e) {
            emitters.remove(userId, emitter);
            log.warn("지갑 생성 SSE 이벤트 전송 실패 - userId: {}, step: {}", userId, status.name());
        }
    }

    /**
     * 지갑 생성 완료 시 "DONE" 이벤트를 발송하고 스트림을 종료한다.
     *
     * @param userId 사용자 식별자
     */
    public void complete(Long userId) {
        SseEmitter emitter = emitters.remove(userId);
        if (emitter == null) return;
        try {
            emitter.send(SseEmitter.event().name("DONE").data("{\"done\":true}"));
            emitter.complete();
            log.info("지갑 생성 SSE 스트림 완료 - userId: {}", userId);
        } catch (IOException ignored) {}
    }

    /**
     * 지갑 생성 실패 시 "FAILED" 이벤트를 발송하고 스트림을 종료한다.
     *
     * @param userId        사용자 식별자
     * @param errorMessage  실패 원인
     */
    public void error(Long userId, String errorMessage) {
        SseEmitter emitter = emitters.remove(userId);
        if (emitter == null) return;

        WalletProvisioningResponse payload = new WalletProvisioningResponse(
                WalletProvisioningStatus.FAILED.name(),
                WalletProvisioningStatus.FAILED.getLabel(),
                errorMessage,
                WalletProvisioningStatus.FAILED.getStep(),
                TOTAL_STEPS,
                Instant.now(),
                errorMessage
        );

        try {
            emitter.send(SseEmitter.event().name("FAILED").data(payload));
            emitter.complete();
            log.warn("지갑 생성 SSE 실패 이벤트 발송 - userId: {}, 원인: {}", userId, errorMessage);
        } catch (IOException ignored) {}
    }

    private void sendRaw(Long userId, SseEmitter emitter, String eventName, String data) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data));
        } catch (IOException e) {
            emitters.remove(userId, emitter);
        }
    }
}
