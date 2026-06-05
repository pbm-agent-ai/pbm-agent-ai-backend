package com.pbm.payment.service;

import com.pbm.payment.domain.UserWallet;
import com.pbm.payment.domain.WalletProvisioningStatus;
import com.pbm.payment.dto.response.WalletProvisioningResponse;
import com.pbm.payment.repository.UserWalletRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Keys;
import org.web3j.utils.Numeric;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * PBM 스마트 컨트랙트 지갑 관리 서비스.
 * <p>
 * 역할: 사용자별 PBMSmartAccount 지갑 생성 및 조회를 처리한다.
 * 동작:
 *   1. 지갑 생성 요청 시 BlockchainService.createWallet()으로 컨트랙트 배포
 *   2. 배포된 지갑 주소를 UserWallet 엔티티로 DB에 저장
 *   3. 이후 userId로 지갑 주소 조회 제공
 * 연관: UserWalletRepository, BlockchainService.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WalletService {

    private final UserWalletRepository userWalletRepository;
    private final BlockchainService blockchainService;
    private final WalletProvisioningTracker provisioningTracker;
    private final WalletProvisioningRunner provisioningRunner;

    /**
     * 사용자의 PBM 스마트 지갑을 반환한다. (동기, 기존 지갑만)
     * <p>
     * 지갑이 존재하면 해당 엔티티를 반환하고,
     * 존재하지 않으면 null을 반환한다.
     * 비동기 생성을 시작하려면 {@link #startAsyncProvisioning(Long, long)}을 사용한다.
     *
     * @param userId 사용자 식별자
     * @return 기존 UserWallet 또는 null
     */
    public UserWallet createWallet(Long userId, long walletLimitKrw) {
        // 이미 지갑이 있으면 기존 지갑 반환
        return userWalletRepository.findByUserId(userId).orElse(null);
    }

    /**
     * 지갑 생성을 비동기로 시작한다.
     * <p>
     * 이미 지갑이 존재하거나 이미 생성이 진행 중이면 시작하지 않는다.
     *
     * @param userId         사용자 식별자
     * @param walletLimitKrw 지갑 전체 PBM 한도 (KRW 기준)
     * @return 생성이 실제로 시작되었으면 true, 중복/기존 존재로 시작되지 않았으면 false
     */
    public boolean startAsyncProvisioning(Long userId, long walletLimitKrw) {
        // 1. 이미 지갑이 있으면 시작 불필요
        if (userWalletRepository.existsByUserId(userId)) {
            log.info("이미 지갑이 존재하여 비동기 생성을 시작하지 않음 - userId: {}", userId);
            return false;
        }
        // 2. 이미 생성이 진행 중이면 중복 시작 방지
        if (provisioningTracker.isInProgress(userId)) {
            log.info("이미 지갑 생성이 진행 중 - userId: {}", userId);
            return false;
        }
        // 3. 추적기 초기화 후 비동기 실행
        log.info("지갑 비동기 생성 시작 - userId: {}, walletLimit: {} KRW", userId, walletLimitKrw);
        provisioningTracker.update(userId, WalletProvisioningStatus.NOT_STARTED, "지갑 생성을 준비하고 있습니다.");
        provisioningRunner.run(userId, walletLimitKrw);
        return true;
    }

    /**
     * userId별 지갑 생성 진행상태를 조회한다.
     * <p>
     * DB에 지갑이 이미 존재하면 완료(SAVED) 상태를 반환하고,
     * 존재하지 않으면 메모리 추적기의 상태를 반환한다.
     *
     * @param userId 사용자 식별자
     * @return 지갑 생성 진행상태 응답
     */
    public WalletProvisioningResponse getProvisioningStatus(Long userId) {
        // 1. DB에 지갑이 있으면 완료 상태 반환
        java.util.Optional<UserWallet> existing = userWalletRepository.findByUserId(userId);
        if (existing.isPresent()) {
            return WalletProvisioningResponse.completed(existing.get());
        }
        // 2. 메모리 추적 상태 확인 or 시작 전 상태
        return provisioningTracker.get(userId)
                .map(WalletProvisioningResponse::from)
                .orElseGet(WalletProvisioningResponse::notStarted);
    }

    /**
     * userId로 지갑 주소를 조회한다.
     *
     * @param userId 사용자 식별자
     * @return 지갑 주소 문자열
     * @throws IllegalArgumentException 지갑이 존재하지 않을 경우
     */
    public String getWalletAddress(Long userId) {
        return userWalletRepository.findByUserId(userId)
                .map(UserWallet::getWalletAddress)
                .orElseThrow(() -> new IllegalArgumentException(
                        "사용자의 PBM 지갑이 존재하지 않습니다. userId=" + userId));
    }

    /**
     * userId에 해당하는 지갑 엔티티를 조회한다.
     *
     * @param userId 사용자 식별자
     * @return UserWallet Optional
     */
    public java.util.Optional<UserWallet> findByUserId(Long userId) {
        return userWalletRepository.findByUserId(userId);
    }

    /**
     * userId에 해당하는 Credentials를 반환한다.
     * 저장된 개인키로 Credentials를 복원한다.
     *
     * @param userId 사용자 식별자
     * @return Credentials (addSessionKey 등 owner 권한 트랜잭션 서명용)
     * @throws IllegalArgumentException 지갑이 존재하지 않을 경우
     */
    public Credentials getUserCredentials(Long userId) {
        UserWallet wallet = userWalletRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "사용자의 PBM 지갑이 존재하지 않습니다. userId=" + userId));
        return Credentials.create(wallet.getUserPrivateKey());
    }

    /**
     * 지갑 배포 및 저장을 트랜잭션 내에서 실행한다.
     * <p>
     * WalletProvisioningRunner의 @Async 호출에 의해 실행되며,
     * 별도 빈(proxy)을 통한 호출이므로 @Transactional이 정상 동작한다.
     *
     * @param userId         사용자 식별자
     * @param walletLimitKrw 지갑 전체 PBM 한도 (KRW 기준)
     * @return 저장된 UserWallet 엔티티
     */
    @Transactional
    public UserWallet executeDeployAndSave(Long userId, long walletLimitKrw) {
        return deployAndSaveWallet(userId, walletLimitKrw);
    }

    /**
     * 블록체인에 지갑 컨트랙트를 배포하고 DB에 저장한다.
     * <p>
     * 동작 순서:
     * 1. 사용자 고유 키쌍 생성 (ECKeyPair → Credentials) — KEYPAIR_CREATED
     * 2. 사용자 EOA 주소에 가스비 ETH 지원 (마스터 지갑 → 사용자 주소) — FUNDING_USER_EOA → USER_EOA_FUNDED
     * 3. AccountFactory.createAccount() 호출 (사용자 주소를 owner로) — CREATING_SMART_WALLET → SMART_WALLET_CREATED
     * 4. DB에 지갑 정보 저장 (개인키 포함) — SAVED
     * 각 단계에서 provisioningTracker를 갱신하여 진행상태를 메모리에 기록한다.
     */
    private UserWallet deployAndSaveWallet(Long userId, long walletLimitKrw) {
        log.info("신규 PBM 지갑 배포 시작 - userId: {}, walletLimit: {} KRW", userId, walletLimitKrw);
        provisioningTracker.update(userId, WalletProvisioningStatus.NOT_STARTED, "지갑 생성 준비 중...");

        try {
            // 1. 사용자 고유 키쌍 생성
            ECKeyPair ecKeyPair;
            try {
                ecKeyPair = Keys.createEcKeyPair();
            } catch (Exception e) {
                throw new RuntimeException("키쌍 생성 실패: " + e.getMessage(), e);
            }
            Credentials userCredentials = Credentials.create(ecKeyPair);
            String userAddress = userCredentials.getAddress();
            // 64자리 hex 문자열 (0x 접두사 없음) — Credentials.create(hex)로 복원 가능
            String userPrivateKey = Numeric.toHexStringNoPrefixZeroPadded(ecKeyPair.getPrivateKey(), 64);
            log.info("사용자 키쌍 생성 완료 - userId: {}, userAddress: {}", userId, userAddress);
            provisioningTracker.update(userId, WalletProvisioningStatus.KEYPAIR_CREATED, "사용자 지갑 주소를 생성했습니다.");

            // 2. 사용자 EOA에 가스비 ETH 지원 (addSessionKey 등 트랜잭션 서명용)
            provisioningTracker.update(userId, WalletProvisioningStatus.FUNDING_USER_EOA, "마스터 지갑에서 ETH를 지급하고 있습니다.");
            blockchainService.fundUserAddress(userAddress);
            log.info("사용자 EOA ETH 지원 완료 - userId: {}, userAddress: {}", userId, userAddress);
            provisioningTracker.update(userId, WalletProvisioningStatus.USER_EOA_FUNDED, "마스터 지갑에서 ETH 지급이 완료되었습니다.");

            // 3. PBMSmartAccount 배포 (사용자 주소를 owner로)
            provisioningTracker.update(userId, WalletProvisioningStatus.CREATING_SMART_WALLET, "스마트 지갑을 블록체인에 배포하고 있습니다.");
            String walletAddress = blockchainService.createWallet(walletLimitKrw, userCredentials);
            provisioningTracker.update(userId, WalletProvisioningStatus.SMART_WALLET_CREATED, "스마트 지갑 배포가 완료되었습니다.");

            // 4. DB 저장 (개인키 포함)
            // ⚠️ TODO: 운영 환경에서는 userPrivateKey를 AES-256으로 암호화 후 저장
            UserWallet userWallet = UserWallet.create(
                    userId,
                    userAddress,
                    userPrivateKey,
                    walletAddress,
                    BigDecimal.valueOf(walletLimitKrw)
            );
            UserWallet saved = userWalletRepository.save(userWallet);
            provisioningTracker.update(userId, WalletProvisioningStatus.SAVED, "지갑 정보 저장까지 모두 완료되었습니다.");
            log.info("신규 PBM 지갑 저장 완료 - userId: {}, userAddress: {}, walletAddress: {}",
                    userId, userAddress, walletAddress);
            return saved;

        } catch (Exception e) {
            // 실패 시 FAILED 상태 기록 후 예외 재전파
            provisioningTracker.updateError(userId, e.getMessage());
            log.error("PBM 지갑 배포 실패 - userId: {}, 원인: {}", userId, e.getMessage(), e);
            throw e;
        }
    }
}
