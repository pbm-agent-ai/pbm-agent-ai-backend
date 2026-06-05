package com.pbm.payment.dto.response;

import com.pbm.payment.domain.UserWallet;
import com.pbm.payment.domain.WalletProvisioningStatus;
import com.pbm.payment.service.WalletProvisioningTracker;

import java.time.Instant;

/**
 * PBM 스마트 지갑 생성 진행상태 응답 DTO.
 *
 * @param status       상태 코드 (enum name)
 * @param label        한글 상태 레이블
 * @param message      상세 메시지
 * @param currentStep  현재 단계 (0~6, FAILED는 -1)
 * @param totalSteps   전체 단계 (항상 7)
 * @param updatedAt    상태 갱신 일시
 * @param errorMessage 실패 사유 (FAILED 상태일 때만)
 */
public record WalletProvisioningResponse(
        String status,
        String label,
        String message,
        int currentStep,
        int totalSteps,
        Instant updatedAt,
        String errorMessage
) {
    private static final int TOTAL_STEPS = 7;

    /** 추적기 상태로부터 응답 DTO 생성 */
    public static WalletProvisioningResponse from(WalletProvisioningTracker.State state) {
        WalletProvisioningStatus s = state.status();
        return new WalletProvisioningResponse(
                s.name(),
                s.getLabel(),
                state.message(),
                s.getStep(),
                TOTAL_STEPS,
                state.updatedAt(),
                state.errorMessage()
        );
    }

    /** DB에 지갑이 이미 존재하는 경우 완료 상태 응답 */
    public static WalletProvisioningResponse completed(UserWallet wallet) {
        return new WalletProvisioningResponse(
                WalletProvisioningStatus.SAVED.name(),
                WalletProvisioningStatus.SAVED.getLabel(),
                "지갑 생성이 이미 완료되었습니다.",
                WalletProvisioningStatus.SAVED.getStep(),
                TOTAL_STEPS,
                wallet.getUpdatedAt(),
                null
        );
    }

    /** 추적 정보가 없는 경우 (생성 요청 전) */
    public static WalletProvisioningResponse notStarted() {
        return new WalletProvisioningResponse(
                WalletProvisioningStatus.NOT_STARTED.name(),
                WalletProvisioningStatus.NOT_STARTED.getLabel(),
                "지갑 생성을 시작할 준비가 되었습니다.",
                WalletProvisioningStatus.NOT_STARTED.getStep(),
                TOTAL_STEPS,
                Instant.now(),
                null
        );
    }
}
