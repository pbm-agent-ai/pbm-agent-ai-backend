package com.pbm.payment.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 지갑 생성 비동기 실행기.
 * <p>
 * WalletService.startAsyncProvisioning()에서 호출되며,
 * @Async로 실행되어 실제 지갑 배포 작업을 별도 스레드에서 처리한다.
 * 트랜잭션 경계를 명확히 하기 위해 WalletService.executeDeployAndSave()를
 * 별도 빈(proxy)을 통해 호출한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WalletProvisioningRunner {

    private final WalletService walletService;

    /**
     * 지갑 생성을 비동기로 실행한다.
     *
     * @param userId         사용자 식별자
     * @param walletLimitKrw 지갑 PBM 한도 (KRW 기준)
     */
    @Async
    public void run(Long userId, long walletLimitKrw) {
        log.info("비동기 지갑 생성 실행 - userId: {}", userId);
        try {
            walletService.executeDeployAndSave(userId, walletLimitKrw);
        } catch (Exception e) {
            log.error("비동기 지갑 생성 실패 - userId: {}, 원인: {}", userId, e.getMessage(), e);
            // 예외는 WalletProvisioningTracker에 기록되지 않을 수 있으므로
            // deployAndSaveWallet 내부에서 이미 기록됨
        }
    }
}
