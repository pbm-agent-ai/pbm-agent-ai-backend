package com.pbm.payment.controller;

import com.pbm.payment.common.ApiResponse;
import com.pbm.payment.domain.SessionKey;
import com.pbm.payment.domain.UserWallet;
import com.pbm.payment.dto.request.TokenChargeRequest;
import com.pbm.payment.dto.request.WalletLimitUpdateRequest;
import com.pbm.payment.dto.request.WalletCreateRequest;
import com.pbm.payment.dto.response.TokenChargeResponse;
import com.pbm.payment.dto.response.TokenTransactionResponse;
import com.pbm.payment.dto.response.WalletBalanceResponse;
import com.pbm.payment.dto.response.WalletProvisioningResponse;
import com.pbm.payment.dto.response.WalletResponse;
import com.pbm.payment.repository.SessionKeyRepository;
import com.pbm.payment.service.BlockchainService;
import com.pbm.payment.service.ChargeProgressService;
import com.pbm.payment.service.TokenChargeService;
import com.pbm.payment.service.WalletProvisioningProgressService;
import com.pbm.payment.service.WalletService;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.web3j.crypto.Credentials;
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
import org.springframework.web.bind.annotation.PutMapping;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

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
    private final TokenChargeService tokenChargeService;
    private final ChargeProgressService chargeProgressService;
    private final WalletProvisioningProgressService walletProgressService;
    private final SessionKeyRepository sessionKeyRepository;

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
     * 사용자의 PBM 지갑 한도를 수정한다.
     *
     * @param userId JWT에서 추출된 사용자 ID
     * @param request 지갑 한도 수정 요청
     * @return 수정된 지갑 정보
     */
    @PutMapping("/limit")
    public ApiResponse<WalletResponse> updateWalletLimit(
            @RequestHeader("X-User-Id") Long userId,
            @RequestBody WalletLimitUpdateRequest request
    ) {
        if (request.walletLimitKrw() == null || request.walletLimitKrw() < 1) {
            throw new IllegalArgumentException("지갑 한도는 1 KRW 이상이어야 합니다.");
        }

        UserWallet updated = walletService.updateWalletLimit(userId, request.walletLimitKrw());
        return ApiResponse.success(WalletResponse.from(updated), "지갑 한도 변경 성공");
    }

    /**
     * 지갑 생성 진행 단계를 SSE로 실시간 스트리밍한다.
     * <p>
     * 프론트엔드 사용 순서:
     *   1. GET /api/v1/wallet/provisioning-status/stream 으로 SSE 구독
     *   2. "CONNECTED" 이벤트 수신 후 POST /api/v1/wallet 요청
     *   3. 각 단계 이벤트(KEYPAIR_CREATED, FUNDING_USER_EOA 등)를 수신하여 UI 업데이트
     *   4. "DONE" 또는 "FAILED" 이벤트 수신 후 스트림 닫기
     *
     * @param userId JWT에서 추출된 사용자 ID
     * @return SseEmitter
     */
    @GetMapping(value = "/provisioning-status/stream", produces = "text/event-stream")
    public SseEmitter streamProvisioningStatus(
            @RequestHeader("X-User-Id") Long userId
    ) {
        log.info("지갑 생성 진행 SSE 구독 요청 - userId: {}", userId);
        return walletProgressService.subscribe(userId);
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

    /**
     * 토큰 충전 진행 단계를 SSE(Server-Sent Events)로 실시간 스트리밍한다.
     * <p>
     * 프론트엔드 사용 순서:
     *   1. GET /api/v1/wallet/charge/stream 으로 SSE 구독
     *   2. "CONNECTED" 이벤트 수신 후 POST /api/v1/wallet/charge 요청
     *   3. 각 단계 이벤트(TX_SENT, TX_CONFIRMED 등)를 수신하여 UI 업데이트
     *   4. "DONE" 또는 "FAILED" 이벤트 수신 후 스트림 닫기
     *
     * @param userId JWT에서 추출된 사용자 ID
     * @return SseEmitter (Spring이 자동으로 text/event-stream 응답으로 처리)
     */
    @GetMapping(value = "/charge/stream", produces = "text/event-stream")
    public SseEmitter streamChargeProgress(
            @RequestHeader("X-User-Id") Long userId
    ) {
        log.info("충전 진행 SSE 구독 요청 - userId: {}", userId);
        return chargeProgressService.subscribe(userId);
    }

    /**
     * 사용자 스마트 지갑에 PBM 토큰을 충전한다.
     * <p>
     * 마스터 지갑에서 사용자 PBMSmartAccount로 ERC-20 transfer를 실행한다.
     * 블록체인 확정까지 대기하므로 응답에 수십 초 소요될 수 있다.
     *
     * @param userId  JWT에서 추출된 사용자 ID
     * @param request 충전 요청 (amountPbm)
     * @return 충전 결과 (txHash, status, amount)
     */
    @PostMapping("/charge")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<TokenChargeResponse> charge(
            @RequestHeader("X-User-Id") Long userId,
            @RequestBody TokenChargeRequest request
    ) {
        log.info("PBM 토큰 충전 요청 - userId: {}, amount: {} PBM", userId, request.amountPbm());
        TokenChargeResponse response = tokenChargeService.charge(userId, request.amountPbm());
        return ApiResponse.success(response, "PBM 토큰 충전 성공");
    }

    /**
     * 사용자의 토큰 충전·차감 전체 내역을 최신순으로 조회한다.
     *
     * @param userId JWT에서 추출된 사용자 ID
     * @return 충전·차감 통합 거래 내역 목록
     */
    @GetMapping("/transactions")
    public ApiResponse<List<TokenTransactionResponse>> getTransactions(
            @RequestHeader("X-User-Id") Long userId
    ) {
        log.info("토큰 거래 내역 조회 요청 - userId: {}", userId);
        List<TokenTransactionResponse> transactions = tokenChargeService.getTransactions(userId);
        return ApiResponse.success(transactions, "토큰 거래 내역 조회 성공");
    }

    /**
     * 사용자의 충전 내역만 조회한다.
     *
     * @param userId JWT에서 추출된 사용자 ID
     * @return 충전 내역 목록
     */
    @GetMapping("/charge/history")
    public ApiResponse<List<TokenTransactionResponse>> getChargeHistory(
            @RequestHeader("X-User-Id") Long userId
    ) {
        log.info("충전 내역 조회 요청 - userId: {}", userId);
        List<TokenTransactionResponse> history = tokenChargeService.getChargeHistory(userId);
        return ApiResponse.success(history, "충전 내역 조회 성공");
    }

    // ──────────────────────────────────────────────────────────────────
    // 관리자 API
    // ──────────────────────────────────────────────────────────────────

    /**
     * 지정된 AI 에이전트 주소 목록을 on-chain에서 revoke한다.
     * DB에 없는 on-chain 세션키도 처리 가능.
     *
     * @param body { "aiAgentAddresses": ["0x...", "0x..."] }
     */
    @PostMapping("/admin/revoke-session-keys")
    public ApiResponse<Map<String, Object>> revokeSessionKeysByAddress(
            @RequestHeader("X-User-Id") Long userId,
            @RequestBody Map<String, List<String>> body
    ) {
        List<String> addresses = body.get("aiAgentAddresses");
        if (addresses == null || addresses.isEmpty()) {
            throw new IllegalArgumentException("aiAgentAddresses를 입력해주세요.");
        }

        UserWallet wallet = walletService.findByUserId(userId)
                .orElseThrow(() -> new IllegalStateException("지갑이 없습니다."));
        Credentials userCredentials = walletService.getUserCredentials(userId);

        // 사용자 EOA ETH 충전
        try {
            blockchainService.fundUserAddress(wallet.getUserAddress());
        } catch (Exception e) {
            log.warn("사용자 EOA ETH 충전 실패 - {}", e.getMessage());
        }

        int revokedCount = 0;
        List<String> failedAddresses = new java.util.ArrayList<>();
        for (String addr : addresses) {
            try {
                String txHash = blockchainService.revokeSessionKey(
                        wallet.getWalletAddress(), addr.trim(), userCredentials);
                blockchainService.waitForReceiptPublic(txHash);
                revokedCount++;
                log.info("세션키 on-chain revoke 완료 - aiAgent: {}, txHash: {}", addr, txHash);

                // DB에도 ACTIVE로 있으면 REVOKED로 변경
                sessionKeyRepository.findByAiAgentAddressAndStatus(addr.trim(), com.pbm.payment.domain.SessionKeyStatus.ACTIVE)
                        .ifPresent(sk -> { sk.revoke(); sessionKeyRepository.save(sk); });
            } catch (Exception e) {
                log.warn("세션키 revoke 실패 - aiAgent: {}, error: {}", addr, e.getMessage());
                failedAddresses.add(addr + ": " + e.getMessage());
            }
        }

        BigInteger totalAllocated = blockchainService.getTotalAllocated(wallet.getWalletAddress());
        return ApiResponse.success(Map.of(
                "revokedCount", revokedCount,
                "failedAddresses", failedAddresses,
                "onChainTotalAllocatedWei", totalAllocated.toString()
        ), "세션키 revoke 완료");
    }

    /**
     * 사용자의 모든 ACTIVE 세션키를 on-chain revoke하고 DB에서 REVOKED로 변경한다.
     * 그 후 지갑 한도를 새 값으로 업데이트한다.
     *
     * @param userId Gateway가 주입한 사용자 ID
     * @param body   { "newLimitKrw": 1000000 }
     * @return on-chain 상태 정보
     */
    @PostMapping("/admin/reset-and-update-limit")
    public ApiResponse<Map<String, Object>> resetAndUpdateLimit(
            @RequestHeader("X-User-Id") Long userId,
            @RequestBody Map<String, Long> body
    ) {
        Long newLimitKrw = body.get("newLimitKrw");
        if (newLimitKrw == null || newLimitKrw < 1) {
            throw new IllegalArgumentException("newLimitKrw는 1 이상이어야 합니다.");
        }

        UserWallet wallet = walletService.findByUserId(userId)
                .orElseThrow(() -> new IllegalStateException("지갑이 없습니다."));

        // 사용자 Credentials 조회
        Credentials userCredentials = walletService.getUserCredentials(userId);

        // 0. 사용자 EOA에 ETH 부족분 충전 (revoke/updateLimit 트랜잭션 가스비)
        try {
            blockchainService.fundUserAddress(wallet.getUserAddress());
            log.info("사용자 EOA ETH 충전 완료 - userAddress: {}", wallet.getUserAddress());
        } catch (Exception e) {
            log.warn("사용자 EOA ETH 충전 실패 (계속 진행) - {}", e.getMessage());
        }

        // 1. 모든 ACTIVE 세션키를 on-chain revoke
        List<SessionKey> activeKeys = sessionKeyRepository.findByUserIdAndStatus(userId, com.pbm.payment.domain.SessionKeyStatus.ACTIVE);
        int revokedCount = 0;
        for (SessionKey key : activeKeys) {
            try {
                String txHash = blockchainService.revokeSessionKey(
                        wallet.getWalletAddress(),
                        key.getAiAgentAddress(),
                        userCredentials
                );
                blockchainService.waitForReceiptPublic(txHash);
                key.revoke();
                sessionKeyRepository.save(key);
                revokedCount++;
                log.info("세션키 revoke 완료 - aiAgent: {}, txHash: {}", key.getAiAgentAddress(), txHash);
            } catch (Exception e) {
                log.warn("세션키 revoke 실패 (건너뜀) - aiAgent: {}, error: {}", key.getAiAgentAddress(), e.getMessage());
            }
        }

        // 2. 지갑 한도 on-chain 업데이트
        String updateTxHash;
        try {
            updateTxHash = blockchainService.updateWalletLimit(
                    wallet.getWalletAddress(), newLimitKrw, userCredentials);
        } catch (Exception e) {
            log.error("지갑 한도 변경 실패 - wallet: {}, error: {}", wallet.getWalletAddress(), e.getMessage());
            return ApiResponse.error("지갑 한도 변경 실패: " + e.getMessage());
        }

        // 3. DB 한도 업데이트
        wallet.updateWalletLimit(newLimitKrw);
        walletService.save(wallet);

        // 4. on-chain 상태 확인
        BigInteger onChainLimit = blockchainService.getWalletLimit(wallet.getWalletAddress());
        BigInteger onChainAllocated = blockchainService.getTotalAllocated(wallet.getWalletAddress());

        Map<String, Object> result = Map.of(
                "revokedSessionKeys", revokedCount,
                "updateLimitTxHash", updateTxHash,
                "newLimitKrw", newLimitKrw,
                "onChainWalletLimitWei", onChainLimit.toString(),
                "onChainTotalAllocatedWei", onChainAllocated.toString()
        );

        log.info("지갑 리셋 완료 - userId: {}, revoked: {}, newLimit: {} KRW", userId, revokedCount, newLimitKrw);
        return ApiResponse.success(result, "세션키 전체 revoke + 지갑 한도 변경 완료");
    }
}
