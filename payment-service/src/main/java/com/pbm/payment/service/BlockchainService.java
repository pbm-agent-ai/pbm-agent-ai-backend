package com.pbm.payment.service;

import com.pbm.payment.config.Web3Config;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Bool;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.Utf8String;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.TransactionEncoder;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.EthCall;
import org.web3j.protocol.core.methods.response.EthGetTransactionCount;
import org.web3j.protocol.core.methods.response.EthGetTransactionReceipt;
import org.web3j.protocol.core.methods.response.EthSendTransaction;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.utils.Numeric;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * 블록체인(Sepolia) 스마트 컨트랙트 호출 서비스.
 * <p>
 * 역할: Web3j를 사용하여 PBMSmartAccount, AccountFactory, PBMPaymasterWithOracle
 *       컨트랙트의 함수를 호출한다.
 * 동작:
 *   - 마스터 키로 AccountFactory.createAccount() 호출 → getAccount()로 배포 주소 확인
 *   - 마스터 키로 PBMSmartAccount.addSessionKey() 호출
 *   - AI 에이전트 키로 executeAIPayment() 호출 → receipt로 실제 가스비 계산 → postOp() 차감
 * 가스비 흐름:
 *   1. fundAiAgent() → AI 에이전트에 최소 ETH(0.0001 ETH) 지원 (1회 tx 비용)
 *   2. executeAIPayment() 완료 후 PBMPaymasterWithOracle.postOp() 호출
 *      → 실제 가스비를 사용자 스마트 지갑의 PBM 토큰으로 차감 (Chainlink Oracle 환산)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BlockchainService {

    /** 일반 함수 호출용 가스 리밋 */
    private static final BigInteger GAS_LIMIT = BigInteger.valueOf(300_000L);

    /** 컨트랙트 배포(createAccount) 전용 가스 리밋.
     *  실측: 1,477,551 gas 소모 후 서브콜에서 잔여 가스 부족으로 revert 발생.
     *  실제 필요 가스가 1.5M을 초과하므로 2.5M으로 상향.
     *  (5 Gwei 기준 2.5M = 0.0125 ETH 필요) */
    private static final BigInteger GAS_LIMIT_DEPLOY = BigInteger.valueOf(2_500_000L);

    /** Sepolia 테스트넷 체인 ID (EIP-155 재사용 공격 방지용) */
    private static final long CHAIN_ID = 11155111L;

    /**
     * AI 에이전트 초기 ETH 지원량: 0.0001 ETH (단일 tx 가스비 정도).
     * executeAIPayment() 1회 호출 후 postOp()로 사용자 PBM에서 가스비가 회수되므로
     * 반복 충전 없이 재사용 가능한 최소 금액만 지원한다.
     * (같은 패키지 내 Consumer에서 참조)
     */
    static final BigInteger AI_AGENT_FUND_AMOUNT = BigInteger.valueOf(50_000_000_000_000L); // 0.00005 ETH

    /** PBM 토큰 소수점: 18자리 */
    private static final BigInteger TOKEN_DECIMALS = BigInteger.TEN.pow(18);

    /** 트랜잭션 receipt 대기 최대 횟수 (1회당 5초, 최대 60초 = 1분, 디버깅용 단축) */
    private static final int RECEIPT_MAX_RETRIES = 12;
    private static final long RECEIPT_POLL_INTERVAL_MS = 5_000L;

    private final Web3j web3j;
    private final Credentials masterCredentials;
    private final Web3Config web3Config;

    // ──────────────────────────────────────────────────────────────────
    // 지갑 생성
    // ──────────────────────────────────────────────────────────────────

    /**
     * AccountFactory.createAccount()를 호출하여 새 PBMSmartAccount를 배포하고
     * 배포된 지갑 주소를 반환한다.
     * <p>
     * owner는 사용자 고유 키쌍에서 파생된 EOA 주소이며,
     * 가스비는 마스터 지갑(masterCredentials)이 부담한다.
     * 배포 완료 확인은 getAccount()로 체인에서 직접 조회한다.
     *
     * @param walletLimitKrw  지갑 전체 PBM 한도 (KRW 기준 정수)
     * @param userCredentials 사용자 고유 키쌍에서 생성된 Credentials (owner로 등록)
     * @return 배포된 PBMSmartAccount 컨트랙트 주소
     */
    public String createWallet(long walletLimitKrw, Credentials userCredentials) {
        // 사용자 고유 주소를 owner로 사용 — 이후 addSessionKey 등은 user 키로 서명
        String ownerAddress = userCredentials.getAddress();
        BigInteger limitInWei = BigInteger.valueOf(walletLimitKrw).multiply(TOKEN_DECIMALS);

        log.info("지갑 생성 시작 - owner(userAddress): {}, walletLimit: {} KRW", ownerAddress, walletLimitKrw);

        // AccountFactory.createAccount(address _owner, address _pbmTokenAddress, uint256 _walletLimit, address _paymasterAddress)
        Function createFn = new Function(
                "createAccount",
                Arrays.asList(
                        new Address(ownerAddress),
                        new Address(web3Config.getPbmTokenAddress()),
                        new Uint256(limitInWei),
                        new Address(web3Config.getPaymasterAddress())  // Paymaster approve를 위해 전달
                ),
                List.of()
        );

        // 컨트랙트 배포는 가스를 많이 소모하므로 GAS_LIMIT_DEPLOY 사용
        // 가스비는 마스터 지갑이 부담 (서비스 셋업 비용)
        String txHash = sendTransaction(masterCredentials, web3Config.getAccountFactoryAddress(), createFn, BigInteger.ZERO, GAS_LIMIT_DEPLOY);
        log.info("createAccount tx 전송 - txHash: {}", txHash);

        // 트랜잭션 확정 대기 (revert 여부도 확인)
        waitForReceipt(txHash);

        // AccountFactory.getAccount(address _owner) → 배포된 지갑 주소 조회
        String walletAddress = getAccountFromFactory(ownerAddress);
        log.info("지갑 생성 완료 - ownerAddress: {}, walletAddress: {}", ownerAddress, walletAddress);
        return walletAddress;
    }

    /**
     * AccountFactory.getAccount(ownerAddress)를 eth_call로 조회하여 지갑 주소를 반환한다.
     *
     * @param ownerAddress 마스터 지갑 주소 (owner)
     * @return 배포된 PBMSmartAccount 주소
     */
    public String getAccountFromFactory(String ownerAddress) {
        // AccountFactory.getAccount(address _owner) → address
        Function getAccountFn = new Function(
                "getAccount",
                List.of(new Address(ownerAddress)),
                List.of(new TypeReference<Address>() {})
        );

        String result = ethCall(ownerAddress, web3Config.getAccountFactoryAddress(), getAccountFn);

        List<Type> decoded = FunctionReturnDecoder.decode(result, getAccountFn.getOutputParameters());
        if (decoded.isEmpty()) {
            throw new RuntimeException("getAccount 결과 없음 - ownerAddress: " + ownerAddress);
        }
        String address = decoded.get(0).getValue().toString();

        // 지갑이 아직 없는 경우 (0x000...000 반환)
        if (address.equals("0x0000000000000000000000000000000000000000")) {
            throw new RuntimeException("지갑이 존재하지 않습니다. createAccount가 완료되지 않았을 수 있습니다.");
        }
        return address;
    }

    // ──────────────────────────────────────────────────────────────────
    // 세션키 관리
    // ──────────────────────────────────────────────────────────────────

    /**
     * PBMSmartAccount.addSessionKey()를 호출하여 AI 에이전트 세션키를 등록한다.
     * <p>
     * 컨트랙트 내 require(msg.sender == owner)가 있으므로,
     * 반드시 사용자 Credentials(owner)로 서명해야 한다.
     *
     * @param walletAddress   PBMSmartAccount 주소
     * @param aiAgentAddress  AI 에이전트 주소
     * @param limitKrw        세션키 한도 (KRW)
     * @param validSeconds    유효 기간 (초)
     * @param platform        플랫폼 구분 ("NAVER", "ALIEXPRESS")
     * @param userCredentials 사용자 EOA Credentials (PBMSmartAccount owner, 트랜잭션 서명용)
     * @return 트랜잭션 receipt
     */
    public TransactionReceipt addSessionKey(String walletAddress, String aiAgentAddress,
                                long limitKrw, long validSeconds, String platform,
                                Credentials userCredentials) {
        BigInteger limitInWei = BigInteger.valueOf(limitKrw).multiply(TOKEN_DECIMALS);

        log.info("세션키 등록 - wallet: {}, aiAgent: {}, limit: {} KRW, validSeconds: {}, platform: {}, signer: {}",
                walletAddress, aiAgentAddress, limitKrw, validSeconds, platform, userCredentials.getAddress());

        // PBMSmartAccount.addSessionKey(address, uint256, uint256, string)
        Function fn = new Function(
                "addSessionKey",
                Arrays.asList(
                        new Address(aiAgentAddress),
                        new Uint256(limitInWei),
                        new Uint256(BigInteger.valueOf(validSeconds)),
                        new Utf8String(platform)
                ),
                List.of()
        );

        // owner인 사용자 키로 서명 — msg.sender == owner 조건 충족
        String txHash = sendTransaction(userCredentials, walletAddress, fn, BigInteger.ZERO);
        TransactionReceipt receipt = waitForReceipt(txHash);
        log.info("addSessionKey tx 완료 - wallet: {}, aiAgent: {}, txHash: {}", walletAddress, aiAgentAddress, txHash);
        return receipt;
    }

    /**
     * PBMSmartAccount.revokeSessionKey()를 호출하여 세션키를 취소한다.
     * owner인 사용자 Credentials로 서명해야 한다.
     *
     * @param walletAddress   PBMSmartAccount 주소
     * @param aiAgentAddress  취소할 AI 에이전트 주소
     * @param userCredentials 사용자 EOA Credentials (owner)
     * @return 트랜잭션 해시
     */
    public String revokeSessionKey(String walletAddress, String aiAgentAddress,
                                   Credentials userCredentials) {
        log.info("세션키 취소 - wallet: {}, aiAgent: {}", walletAddress, aiAgentAddress);

        Function fn = new Function(
                "revokeSessionKey",
                List.of(new Address(aiAgentAddress)),
                List.of()
        );

        // owner인 사용자 키로 서명
        String txHash = sendTransaction(userCredentials, walletAddress, fn, BigInteger.ZERO);
        log.info("revokeSessionKey tx 완료 - txHash: {}", txHash);
        return txHash;
    }

    // ──────────────────────────────────────────────────────────────────
    // AI 에이전트 가스비 지원
    // ──────────────────────────────────────────────────────────────────

    /**
     * AI 에이전트 주소에 최소 ETH(0.0001 ETH)를 전송한다.
     * <p>
     * executeAIPayment() 1회 호출에 필요한 가스비만 지원한다.
     * 실제 가스 비용은 결제 완료 후 postOp()를 통해 사용자 PBM으로 회수된다.
     *
     * @param aiAgentAddress ETH를 받을 AI 에이전트 주소
     * @return 트랜잭션 해시
     */
    public TransactionReceipt fundAiAgent(String aiAgentAddress) {
        log.info("AI 에이전트 ETH 지원(0.0001 ETH) - aiAgent: {}", aiAgentAddress);

        try {
            BigInteger nonce = getNonce(masterCredentials.getAddress());
            BigInteger gasPrice = getGasPrice(); // 네트워크 gas price 동적 조회
            log.info("AI 에이전트 ETH 전송 - nonce: {}, gasPrice: {} wei, amount: {} wei, to: {}",
                    nonce, gasPrice, AI_AGENT_FUND_AMOUNT, aiAgentAddress);
            RawTransaction rawTx = RawTransaction.createEtherTransaction(
                    nonce, gasPrice, BigInteger.valueOf(21_000L), aiAgentAddress, AI_AGENT_FUND_AMOUNT
            );
            // EIP-155: 체인 ID 포함 서명 (Sepolia = 11155111) — 미포함 시 트랜잭션이 거부/정체됨
            byte[] signed = TransactionEncoder.signMessage(rawTx, CHAIN_ID, masterCredentials);
            EthSendTransaction sent = web3j.ethSendRawTransaction(Numeric.toHexString(signed)).send();

            if (sent.hasError()) {
                throw new RuntimeException("ETH 전송 오류: " + sent.getError().getMessage());
            }
            String txHash = sent.getTransactionHash();
            log.info("AI 에이전트 ETH 전송 성공 - txHash: {}, nonce: {}, gasPrice: {} wei",
                    txHash, nonce, gasPrice);
            // 다음 트랜잭션(addSessionKey)과 nonce 충돌 방지를 위해 확정 대기
            TransactionReceipt receipt = waitForReceipt(txHash);
            log.info("AI 에이전트 ETH 지원 완료 - txHash: {}", txHash);
            return receipt;
        } catch (Exception e) {
            throw new RuntimeException("AI 에이전트 ETH 지원 실패: " + e.getMessage(), e);
        }
    }

    /**
     * 사용자 EOA 주소에 가스비용 ETH를 지원한다.
     * <p>
     * 사용자 EOA는 addSessionKey, revokeSessionKey 등 owner 권한 트랜잭션을 서명해야 하므로
     * ETH가 필요하다. 지갑 최초 생성 시 마스터 지갑에서 1회 지원한다.
     * 지원량(0.002 ETH)은 세션키 등록/취소용 테스트 가스비를 충당하기 위한 최소 여유 금액이다.
     *
     * @param userAddress ETH를 받을 사용자 EOA 주소
     * @return 트랜잭션 receipt
     */
    public TransactionReceipt fundUserAddress(String userAddress) {
        // 0.002 ETH — 세션키 등록/취소 약 20회 가스비 (5 Gwei 기준)
        BigInteger fundAmount = BigInteger.valueOf(2_000_000_000_000_000L);
        log.info("사용자 EOA ETH 지원(0.002 ETH) - userAddress: {}", userAddress);

        try {
            BigInteger nonce = getNonce(masterCredentials.getAddress());
            BigInteger gasPrice = getGasPrice(); // 네트워크 gas price 동적 조회
            log.info("사용자 EOA ETH 전송 - nonce: {}, gasPrice: {} wei, fundAmount: {} wei, to: {}",
                    nonce, gasPrice, fundAmount, userAddress);
            RawTransaction rawTx = RawTransaction.createEtherTransaction(
                    nonce, gasPrice, BigInteger.valueOf(21_000L), userAddress, fundAmount
            );
            byte[] signed = TransactionEncoder.signMessage(rawTx, CHAIN_ID, masterCredentials);
            EthSendTransaction sent = web3j.ethSendRawTransaction(Numeric.toHexString(signed)).send();

            if (sent.hasError()) {
                throw new RuntimeException("ETH 전송 오류: " + sent.getError().getMessage());
            }
            String txHash = sent.getTransactionHash();
            log.info("사용자 EOA ETH 전송 성공 - txHash: {}, nonce: {}, gasPrice: {} wei",
                    txHash, nonce, gasPrice);
            TransactionReceipt receipt = waitForReceipt(txHash);
            log.info("사용자 EOA ETH 지원 완료 - userAddress: {}, txHash: {}", userAddress, txHash);
            return receipt;
        } catch (Exception e) {
            throw new RuntimeException("사용자 EOA ETH 지원 실패: " + e.getMessage(), e);
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // PBM 결제 실행 (Paymaster 연동)
    // ──────────────────────────────────────────────────────────────────

    /**
     * PBMSmartAccount.executeAIPayment()를 호출하여 PBM 토큰 결제를 실행한다.
     * <p>
     * 결제 흐름:
     * 1. validatePaymasterUserOp() → 사용자 PBM 잔고 검증
     * 2. executeAIPayment() → AI 에이전트 키로 서명하여 결제 실행
     * 3. 트랜잭션 receipt에서 실제 가스 사용량 계산
     * 4. postOp() → 가스비를 사용자 스마트 지갑의 PBM 토큰으로 차감
     *
     * @param walletAddress     PBMSmartAccount 주소
     * @param aiAgentPrivateKey AI 에이전트 개인키
     * @param recipientAddress  PBM 토큰 수신 주소
     * @param amountKrw         결제 금액 (KRW)
     * @return 트랜잭션 해시
     */
    public String executePayment(String walletAddress, String aiAgentPrivateKey,
                                 String recipientAddress, long amountKrw) {
        Credentials aiAgent = Credentials.create(aiAgentPrivateKey);
        BigInteger amountInWei = BigInteger.valueOf(amountKrw).multiply(TOKEN_DECIMALS);

        log.info("PBM 결제 시작 - wallet: {}, aiAgent: {}, amount: {} KRW",
                walletAddress, aiAgent.getAddress(), amountKrw);

        // 네트워크 gas price 동적 조회 — 조회 실패 시 5 Gwei fallback
        BigInteger gasPrice = getGasPrice();

        // 1. 결제 전 사용자 PBM 잔고 검증 (예상 가스비 기준)
        BigInteger estimatedGasEth = GAS_LIMIT.multiply(gasPrice); // 최대 예상 가스비 (wei)
        validatePaymasterUserOp(walletAddress, estimatedGasEth);

        // 2. PBMSmartAccount.executeAIPayment(address targetService, uint256 amount)
        Function payFn = new Function(
                "executeAIPayment",
                Arrays.asList(
                        new Address(recipientAddress),
                        new Uint256(amountInWei)
                ),
                List.of()
        );

        // 동일한 gasPrice로 트랜잭션 생성 (중복 조회 방지)
        String txHash = sendTransaction(aiAgent, walletAddress, payFn, BigInteger.ZERO, GAS_LIMIT, gasPrice);
        log.info("executeAIPayment tx 전송 - txHash: {}", txHash);

        // 3. Receipt 대기 → 실제 가스 사용량 계산
        TransactionReceipt receipt = waitForReceipt(txHash);
        BigInteger gasUsed = receipt.getGasUsed();
        BigInteger actualGasEth = gasUsed.multiply(gasPrice); // 실제 가스비 (wei)

        log.info("결제 tx 확정 - gasUsed: {}, actualGasEth: {} wei", gasUsed, actualGasEth);

        // 4. postOp() → 실제 가스비를 사용자 PBM으로 차감
        deductGasCostFromWallet(walletAddress, actualGasEth);

        log.info("PBM 결제 완료 - wallet: {}, txHash: {}", walletAddress, txHash);
        return txHash;
    }

    // ──────────────────────────────────────────────────────────────────
    // Paymaster 연동
    // ──────────────────────────────────────────────────────────────────

    /**
     * PBMPaymasterWithOracle.validatePaymasterUserOp()을 호출하여
     * 사용자 스마트 지갑의 PBM 잔고가 가스비 충당에 충분한지 검증한다.
     *
     * @param walletAddress  사용자 PBMSmartAccount 주소
     * @param requiredEthFee 필요 가스비 (wei 단위 ETH)
     * @throws IllegalStateException PBM 잔고 부족 시
     */
    public void validatePaymasterUserOp(String walletAddress, BigInteger requiredEthFee) {
        // PBMPaymasterWithOracle.validatePaymasterUserOp(address user, uint256 requiredEthFee) → bool
        Function fn = new Function(
                "validatePaymasterUserOp",
                Arrays.asList(
                        new Address(walletAddress),
                        new Uint256(requiredEthFee)
                ),
                List.of(new TypeReference<Bool>() {})
        );

        String result = ethCall(masterCredentials.getAddress(), web3Config.getPaymasterAddress(), fn);
        List<Type> decoded = FunctionReturnDecoder.decode(result, fn.getOutputParameters());

        if (decoded.isEmpty() || !(boolean) decoded.get(0).getValue()) {
            throw new IllegalStateException(
                    "PBM 잔고 부족: 가스비를 충당할 PBM 토큰이 부족합니다. wallet=" + walletAddress);
        }
        log.info("Paymaster 검증 통과 - wallet: {}, requiredEthFee: {} wei", walletAddress, requiredEthFee);
    }

    /**
     * PBMPaymasterWithOracle.postOp()을 호출하여 실제 가스비를 PBM 토큰으로 차감한다.
     * <p>
     * Paymaster가 Chainlink Oracle로 ETH → PBM 환산 후 사용자 지갑에서 차감한다.
     *
     * <pre>
     * ⚠️ 사전 조건 (컨트랙트 레벨):
     *    postOp() 내부에서 pbmToken.transferFrom(smartWallet, paymaster, pbmAmount)가 호출된다.
     *    따라서 스마트 지갑(walletAddress)이 Paymaster 컨트랙트에게 PBM 사용을 approve 해야 한다.
     *    이 approve는 스마트 지갑 자체가 서명해야 하므로, PBMSmartAccount 컨트랙트에
     *    생성자 또는 별도 setup 함수에서 approve(paymaster, type(uint256).max)를 추가해야 한다.
     *    현재 배포된 컨트랙트에서는 이 approve 경로가 없으므로 on-chain 실행 시 revert될 수 있다.
     * </pre>
     *
     * @param walletAddress 사용자 PBMSmartAccount 주소 (PBM이 차감될 대상)
     * @param actualEthFee  실제 가스 사용량 (wei 단위 ETH, receipt.gasUsed * gasPrice)
     */
    public void deductGasCostFromWallet(String walletAddress, BigInteger actualEthFee) {
        log.info("가스비 PBM 차감 시작 - wallet: {}, actualEthFee: {} wei", walletAddress, actualEthFee);

        // PBMPaymasterWithOracle.postOp(address user, uint256 actualEthFee)
        Function fn = new Function(
                "postOp",
                Arrays.asList(
                        new Address(walletAddress),
                        new Uint256(actualEthFee)
                ),
                List.of()
        );

        String txHash = sendTransaction(masterCredentials, web3Config.getPaymasterAddress(), fn, BigInteger.ZERO);
        // 확정까지 대기 — 다음 트랜잭션과 nonce 충돌 방지
        waitForReceipt(txHash);
        log.info("가스비 PBM 차감 완료 - wallet: {}, txHash: {}", walletAddress, txHash);
    }

    /**
     * ERC-20 PBM 토큰 잔액을 조회한다.
     * <p>
     * PBM 토큰 컨트랙트의 balanceOf(address)를 직접 호출한다.
     *
     * @param walletAddress 잔액을 조회할 주소 (스마트 지갑 또는 EOA)
     * @return PBM 잔액 (wei 단위, 18 decimals)
     */
    public BigInteger getPbmBalance(String walletAddress) {
        Function fn = new Function(
                "balanceOf",
                List.of(new Address(walletAddress)),
                List.of(new TypeReference<Uint256>() {})
        );

        String pbmTokenAddress = web3Config.getPbmTokenAddress();
        log.info("[디버그] balanceOf 호출 - tokenContract: {}, wallet: {}", pbmTokenAddress, walletAddress);
        String result = ethCall(masterCredentials.getAddress(), pbmTokenAddress, fn);
        log.info("[디버그] balanceOf 원시 결과: {}", result);
        List<Type> decoded = FunctionReturnDecoder.decode(result, fn.getOutputParameters());

        if (decoded.isEmpty()) {
            throw new RuntimeException("balanceOf 결과 없음 - wallet: " + walletAddress);
        }
        BigInteger balanceWei = (BigInteger) decoded.get(0).getValue();
        log.info("PBM 잔액 조회 - wallet: {}, balance: {} wei ({} PBM)",
                walletAddress, balanceWei,
                new java.math.BigDecimal(balanceWei).divide(new java.math.BigDecimal(TOKEN_DECIMALS), 4, java.math.RoundingMode.DOWN));
        return balanceWei;
    }

    /**
     * PBMPaymasterWithOracle.convertEthToPbm()으로 ETH 금액을 PBM 토큰으로 환산한다. (참조용)
     *
     * @param ethAmountWei ETH 금액 (wei)
     * @return 환산된 PBM 토큰 수량 (wei 단위)
     */
    public BigInteger convertEthToPbm(BigInteger ethAmountWei) {
        Function fn = new Function(
                "convertEthToPbm",
                List.of(new Uint256(ethAmountWei)),
                List.of(new TypeReference<Uint256>() {})
        );

        String result = ethCall(masterCredentials.getAddress(), web3Config.getPaymasterAddress(), fn);
        List<Type> decoded = FunctionReturnDecoder.decode(result, fn.getOutputParameters());

        if (decoded.isEmpty()) {
            throw new RuntimeException("convertEthToPbm 결과 없음");
        }
        return (BigInteger) decoded.get(0).getValue();
    }

    // ──────────────────────────────────────────────────────────────────
    // 공통 유틸리티
    // ──────────────────────────────────────────────────────────────────

    /**
     * 서명된 트랜잭션을 블록체인에 전송하고 트랜잭션 해시를 반환한다.
     * gasLimit 미지정 시 기본값(GAS_LIMIT), gasPrice는 동적 조회해서 사용.
     */
    private String sendTransaction(Credentials credentials, String toAddress,
                                   Function function, BigInteger value) {
        return sendTransaction(credentials, toAddress, function, value, GAS_LIMIT, getGasPrice());
    }

    /**
     * 서명된 트랜잭션을 블록체인에 전송하고 트랜잭션 해시를 반환한다.
     * gasLimit을 명시적으로 지정할 수 있다 (컨트랙트 배포 등 가스 소모가 많은 경우).
     * gasPrice는 동적 조회해서 사용.
     */
    private String sendTransaction(Credentials credentials, String toAddress,
                                   Function function, BigInteger value, BigInteger gasLimit) {
        return sendTransaction(credentials, toAddress, function, value, gasLimit, getGasPrice());
    }

    /**
     * 서명된 트랜잭션을 블록체인에 전송하고 트랜잭션 해시를 반환한다.
     * gasLimit과 gasPrice를 모두 명시적으로 지정할 수 있다.
     */
    private String sendTransaction(Credentials credentials, String toAddress,
                                   Function function, BigInteger value, BigInteger gasLimit, BigInteger gasPrice) {
        try {
            String encoded = FunctionEncoder.encode(function);
            BigInteger nonce = getNonce(credentials.getAddress());

            log.info("트랜잭션 전송 - fn: {}, nonce: {}, gasPrice: {} wei, gasLimit: {}, to: {}",
                    function.getName(), nonce, gasPrice, gasLimit, toAddress);
            RawTransaction rawTx = RawTransaction.createTransaction(
                    nonce, gasPrice, gasLimit, toAddress, value, encoded
            );
            // EIP-155: 체인 ID 포함 서명으로 다른 네트워크(메인넷 등) 재사용 공격 방지
            byte[] signed = TransactionEncoder.signMessage(rawTx, CHAIN_ID, credentials);
            EthSendTransaction sent = web3j.ethSendRawTransaction(Numeric.toHexString(signed)).send();

            if (sent.hasError()) {
                throw new RuntimeException("트랜잭션 오류: " + sent.getError().getMessage());
            }
            String txHash = sent.getTransactionHash();
            if (txHash == null || txHash.isBlank()) {
                throw new RuntimeException("트랜잭션 해시 없음 (제출 실패 가능)");
            }
            log.info("트랜잭션 전송 완료 - fn: {}, txHash: {}, nonce: {}, gasPrice: {} wei",
                    function.getName(), txHash, nonce, gasPrice);
            return txHash;
        } catch (Exception e) {
            log.error("트랜잭션 전송 실패 - to: {}, fn: {}, 원인: {}",
                    toAddress, function.getName(), e.getMessage(), e);
            throw new RuntimeException("트랜잭션 전송 실패: " + e.getMessage(), e);
        }
    }

    /**
     * eth_call로 view 함수를 호출하고 raw 결과 hex를 반환한다.
     */
    private String ethCall(String fromAddress, String toAddress, Function function) {
        try {
            String encoded = FunctionEncoder.encode(function);
            EthCall call = web3j.ethCall(
                    Transaction.createEthCallTransaction(fromAddress, toAddress, encoded),
                    DefaultBlockParameterName.LATEST
            ).send();

            if (call.hasError()) {
                throw new RuntimeException("eth_call 오류: " + call.getError().getMessage());
            }
            return call.getValue();
        } catch (Exception e) {
            throw new RuntimeException("eth_call 실패 - fn: " + function.getName() + ": " + e.getMessage(), e);
        }
    }

    /**
     * 트랜잭션 receipt를 폴링하여 확정될 때까지 대기한다.
     *
     * @param txHash 대기할 트랜잭션 해시
     * @return 확정된 TransactionReceipt
     * @throws RuntimeException 타임아웃 시
     */
    private TransactionReceipt waitForReceipt(String txHash) {
        log.debug("트랜잭션 확정 대기 - txHash: {}", txHash);
        for (int i = 0; i < RECEIPT_MAX_RETRIES; i++) {
            try {
                Thread.sleep(RECEIPT_POLL_INTERVAL_MS);
                EthGetTransactionReceipt response = web3j.ethGetTransactionReceipt(txHash).send();
                Optional<TransactionReceipt> receipt = response.getTransactionReceipt();
                if (receipt.isPresent()) {
                    TransactionReceipt r = receipt.get();
                    log.info("트랜잭션 확정 완료 - txHash: {}, blockNumber: {}, status: {}",
                            txHash, r.getBlockNumber(), r.getStatus());
                    // status "0x0" = revert → 재시도해도 소용없으므로 즉시 예외 발생
                    if ("0x0".equals(r.getStatus())) {
                        throw new RuntimeException("트랜잭션 revert - txHash: " + txHash
                                + " (가스 부족 또는 컨트랙트 로직 실패)");
                    }
                    return r;
                }
            } catch (RuntimeException e) {
                // revert 또는 대기 중단은 재시도 없이 즉시 상위로 전파
                throw e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("트랜잭션 대기 중단 - txHash: " + txHash, e);
            } catch (Exception e) {
                // 네트워크 오류 등 일시적 장애는 재시도
                log.warn("Receipt 조회 중 오류 (재시도 {}/{}): {}", i + 1, RECEIPT_MAX_RETRIES, e.getMessage());
            }
        }
        throw new RuntimeException("트랜잭션 확정 타임아웃 (60초) - txHash: " + txHash);
    }

    /**
     * 현재 Sepolia 네트워크의 gas price를 동적으로 조회하고 20% buffer를 추가한다.
     * <p>
     * buffer를 두는 이유: 조회 시점과 트랜잭션이 블록에 포함되는 시점 사이에
     * gas price가 상승할 가능성이 있으므로, 일부러 여유를 둬서
     * "replacement transaction underpriced" 오류나 채굴 지연을 방지한다.
     * <p>
     * 조회 실패 시 기본값 5 Gwei + 20% = 6 Gwei를 fallback으로 사용한다.
     * (5 Gwei는 Sepolia에서 트랜잭션이 정상 처리되는 최저 범위)
     *
     * @return gas price (wei 단위, 20% buffer 적용)
     */
    private BigInteger getGasPrice() {
        try {
            BigInteger baseGasPrice = web3j.ethGasPrice().send().getGasPrice();
            return baseGasPrice.multiply(BigInteger.valueOf(120)).divide(BigInteger.valueOf(100));
        } catch (Exception e) {
            log.warn("네트워크 gas price 조회 실패, 기본값(5 Gwei) + 20% buffer 사용: {}", e.getMessage());
            return BigInteger.valueOf(6_000_000_000L); // 5 Gwei + 20% = 6 Gwei fallback
        }
    }

    /**
     * 주어진 주소의 nonce를 조회한다.
     * <p>
     * PENDING 기준으로 조회하여 멤풀에 대기 중인 트랜잭션의 nonce도 포함한다.
     * LATEST 기준이면 pending tx를 무시해 nonce 충돌("replacement transaction underpriced")이 발생한다.
     */
    private BigInteger getNonce(String address) throws Exception {
        EthGetTransactionCount response = web3j
                .ethGetTransactionCount(address, DefaultBlockParameterName.PENDING)
                .send();
        return response.getTransactionCount();
    }
}
