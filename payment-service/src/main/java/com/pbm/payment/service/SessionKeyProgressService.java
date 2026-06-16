package com.pbm.payment.service;

import com.pbm.payment.dto.response.SessionKeyProgressEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 세션키 등록 진행 상태를 인메모리에 저장하는 서비스.
 *
 * 역할: 세션키 등록 각 단계에서 상태를 갱신하고, extension이 폴링으로 조회할 수 있게 한다.
 */
@Slf4j
@Service
public class SessionKeyProgressService {

    private final Map<Long, SessionKeyProgressEvent> progressMap = new ConcurrentHashMap<>();

    public void emit(Long userId, String step, String message) {
        emit(userId, step, message, null);
    }

    public void emit(Long userId, String step, String message, String detail) {
        SessionKeyProgressEvent event = SessionKeyProgressEvent.of(step, message, detail);
        progressMap.put(userId, event);
        log.info("세션키 진행 상태 갱신 - userId: {}, step: {}, message: {}", userId, step, message);
    }

    public void complete(Long userId, String detail) {
        progressMap.put(userId, SessionKeyProgressEvent.of("DONE", "세션키 등록 완료", detail));
        log.info("세션키 등록 완료 - userId: {}", userId);
    }

    public void error(Long userId, String reason) {
        progressMap.put(userId, SessionKeyProgressEvent.of("FAILED", "세션키 등록 실패", reason));
        log.warn("세션키 등록 실패 - userId: {}, reason: {}", userId, reason);
    }

    /**
     * 현재 진행 상태를 조회하고 제거한다 (DONE/FAILED일 때만 제거).
     */
    public SessionKeyProgressEvent poll(Long userId) {
        SessionKeyProgressEvent event = progressMap.get(userId);
        if (event != null && ("DONE".equals(event.step()) || "FAILED".equals(event.step()))) {
            progressMap.remove(userId);
        }
        return event;
    }
}
