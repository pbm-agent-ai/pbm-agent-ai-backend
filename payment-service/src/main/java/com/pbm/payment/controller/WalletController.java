package com.pbm.payment.controller;

import com.pbm.payment.common.ApiResponse;
import com.pbm.payment.domain.UserWallet;
import com.pbm.payment.dto.request.WalletCreateRequest;
import com.pbm.payment.dto.response.WalletBalanceResponse;
import com.pbm.payment.dto.response.WalletProvisioningResponse;
import com.pbm.payment.dto.response.WalletResponse;
import com.pbm.payment.service.BlockchainService;
import com.pbm.payment.service.WalletService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

/**
 * PBM 스마트 지갑 관리 REST API 컨트롤러.
 * <p>
 * 사용자별 PBMSmartAccount 지갑 생성 및 조회 기능을 제공한다.
 * 모든 요청은 JWT 인증이 필요하며, userId는 게이트웨이에서 X-User-Id 헤더로 주입된다.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/wallet")
@RequiredArgsConstructor
public class WalletController {

    private final WalletService walletService;
    private final BlockchainService blockchainService;

    private static final BigDecimal TOKEN_DECIMALS = BigDecimal.TEN.pow(18);

    /**
     * 사용자의 PBM 스마트 지갑을 생성한다.
     * <p>
     * 이미 지갑이 존재하면 기존 지갑 정보를 동기 응답으로 반환한다.
     * 지갑이 없으면 비동기로 생성을 시작하고 data=null로 즉시 응답한다.
     * 프론트는 응답을 받은 후 GET /api/v1/wallet/provisioning-status로 폴링하여 진행상태를 확인한다.
     *
     * @param userId  JWT에서 추출된 사용자 ID (게이트웨이가 헤더로 주입)
     * @param request 지갑 생성 요청 (walletLimitKrw)
     * @return 생성된 지갑 정보 (동기) 또는 data=null (비동기 시작)
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<WalletResponse> createWallet(
            @RequestHeader("X-User-Id") Long userId,
            @RequestBody WalletCreateRequest request
    ) {
        if (request.walletLimitKrw() == null || request.walletLimitKrw() < 1) {
            throw new IllegalArgumentException("지갑 한도는 1 KRW 이상이어야 합니다.");
        }

        // 1. 이미 지갑이 있으면 기존 정보 반환 (동기)
        UserWallet existing = walletService.createWallet(userId, request.walletLimitKrw());
        if (existing != null) {
            log.info("기존 지갑 반환 - userId: {}", userId);
            return ApiResponse.success(WalletResponse.from(existing), "지갑이 이미 존재합니다.");
        }

        // 2. 지갑이 없으면 비동기 생성 시작 후 즉시 응답
        log.info("지갑 비동기 생성 시작 - userId: {}, walletLimit: {} KRW", userId, request.walletLimitKrw());
        boolean started = walletService.startAsyncProvisioning(userId, request.walletLimitKrw());
        if (!started) {
            // 이미 생성이 진행 중인 경우 → 클라이언트는 provisioning-status로 폴링
            return ApiResponse.success(null, "지갑 생성이 이미 진행 중입니다.");
        }
        return ApiResponse.success(null, "지갑 생성을 시작했습니다.");
    }

    /**
     * 사용자의 PBM 스마트 지갑 정보를 조회한다.
     * 지갑이 없으면 data=null, success=true로 응답한다 (프론트에서 지갑 생성 UI 표시용).
     *
     * @param userId JWT에서 추출된 사용자 ID
     * @return 지갑 정보 (없으면 null)
     */
    @GetMapping
    public ApiResponse<WalletResponse> getMyWallet(
            @RequestHeader("X-User-Id") Long userId
    ) {
        log.info("지갑 조회 요청 - userId: {}", userId);
        return walletService.findByUserId(userId)
                .map(wallet -> ApiResponse.success(WalletResponse.from(wallet), "지갑 조회 성공"))
                .orElse(ApiResponse.success(null, "지갑이 없습니다"));
    }

    /**
     * 사용자의 PBM 스마트 지갑 생성 진행상태를 조회한다.
     * <p>
     * 지갑 생성 중에는 메모리 기반 추적 상태를 반환하고,
     * 이미 생성된 경우에는 완료(SAVED) 상태를 반환한다.
     *
     * @param userId JWT에서 추출된 사용자 ID
     * @return 지갑 생성 진행상태 (단계, 레이블, 메시지 등)
     */
    @GetMapping("/provisioning-status")
    public ApiResponse<WalletProvisioningResponse> getProvisioningStatus(
            @RequestHeader("X-User-Id") Long userId
    ) {
        log.info("지갑 생성 진행상태 조회 - userId: {}", userId);
        WalletProvisioningResponse status = walletService.getProvisioningStatus(userId);
        return ApiResponse.success(status, "지갑 생성 진행상태 조회 성공");
    }

    /**
     * 사용자의 PBM 토큰 잔액을 블록체인에서 직접 조회한다.
     * 지갑이 없으면 data=null로 응답한다.
     *
     * @param userId JWT에서 추출된 사용자 ID
     * @return PBM 잔액 (사람이 읽을 수 있는 단위 + wei 단위), 지갑 없으면 null
     */
    @GetMapping("/balance")
    public ApiResponse<WalletBalanceResponse> getMyBalance(
            @RequestHeader("X-User-Id") Long userId
    ) {
        log.info("PBM 잔액 조회 요청 - userId: {}", userId);
        return walletService.findByUserId(userId)
                .map(wallet -> {
                    BigInteger balanceWei = blockchainService.getPbmBalance(wallet.getWalletAddress());
                    BigDecimal balanceHuman = new BigDecimal(balanceWei)
                            .divide(TOKEN_DECIMALS, 4, RoundingMode.DOWN);
                    WalletBalanceResponse response = new WalletBalanceResponse(
                            wallet.getWalletAddress(),
                            balanceHuman,
                            balanceWei.toString()
                    );
                    return ApiResponse.success(response, "PBM 잔액 조회 성공");
                })
                .orElse(ApiResponse.success(null, "지갑이 없습니다"));
    }
}
