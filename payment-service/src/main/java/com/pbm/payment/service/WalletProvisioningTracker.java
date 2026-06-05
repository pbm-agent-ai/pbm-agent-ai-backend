package com.pbm.payment.service;

import com.pbm.payment.domain.WalletProvisioningStatus;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 지갑 생성 진행상태 메모리 추적기.
 * <p>
 * ConcurrentHashMap 기반으로 userId별 진행상태를 관리한다.
 * 서비스 재시작 시 휘발되므로 로컬 개발/디버깅 용도로만 사용한다.
 * (운영 환경에서는 Redis 등 외부 저장소로 대체 필요)
 */
@Component
public class WalletProvisioningTracker {

    private final ConcurrentHashMap<Long, State> states = new ConcurrentHashMap<>();

    /**
     * 진행상태를 갱신한다.
     *
     * @param userId  사용자 식별자
     * @param status  현재 상태
     * @param message 상세 메시지
     */
    public void update(Long userId, WalletProvisioningStatus status, String message) {
        states.put(userId, new State(status, message, Instant.now(), null));
    }

    /**
     * 실패 상태로 갱신한다.
     *
     * @param userId       사용자 식별자
     * @param errorMessage 실패 사유
     */
    public void updateError(Long userId, String errorMessage) {
        states.put(userId, new State(WalletProvisioningStatus.FAILED, errorMessage, Instant.now(), errorMessage));
    }

    /**
     * userId의 현재 진행상태를 조회한다.
     *
     * @param userId 사용자 식별자
     * @return 진행상태 (없으면 empty)
     */
    public Optional<State> get(Long userId) {
        return Optional.ofNullable(states.get(userId));
    }

    /**
     * userId의 진행상태를 제거한다. (메모리 정리용)
     *
     * @param userId 사용자 식별자
     */
    public void remove(Long userId) {
        states.remove(userId);
    }

    /**
     * 해당 userId의 지갑 생성이 현재 진행 중인지 확인한다.
     * <p>
     * SAVED(이미 저장됨)나 FAILED(실패) 상태가 아니고,
     * 추적 정보가 존재하면 진행 중으로 간주한다.
     * FAILED는 재시도 가능하므로 진행 중이 아님.
     *
     * @param userId 사용자 식별자
     * @return 진행 중이면 true
     */
    public boolean isInProgress(Long userId) {
        State state = states.get(userId);
        if (state == null) {
            return false;
        }
        return switch (state.status()) {
            case SAVED, FAILED -> false;
            default -> true;
        };
    }

    /**
     * 진행상태 불변 레코드.
     *
     * @param status       현재 상태
     * @param message      상세 메시지
     * @param updatedAt    갱신 일시
     * @param errorMessage 실패 사유 (실패 시)
     */
    public record State(
            WalletProvisioningStatus status,
            String message,
            Instant updatedAt,
            String errorMessage
    ) {}
}
