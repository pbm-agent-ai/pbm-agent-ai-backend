package com.pbm.payment.service;

import com.pbm.payment.config.Web3Config;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
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
import org.web3j.protocol.core.methods.response.EthTransaction;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.utils.Numeric;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

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
public class BlockchainService {

    /** 일반 함수 호출용 가스 리밋 */
    public static final BigInteger GAS_LIMIT = BigInteger.valueOf(300_000L);

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

    /** 트랜잭션 receipt 대기 설정 (기본 60초, 복구 폴링 30초) */
    private static final int RECEIPT_TIMEOUT_SECONDS = 60;
    private static final int RECOVERY_TIMEOUT_SECONDS = 30;
    private static final int RECEIPT_POLL_INTERVAL_SECONDS = 5;
    private static final long RECEIPT_POLL_INTERVAL_MS = RECEIPT_POLL_INTERVAL_SECONDS * 1_000L;
    private static final int RECEIPT_MAX_RETRIES = RECEIPT_TIMEOUT_SECONDS / RECEIPT_POLL_INTERVAL_SECONDS;
    private static final int RECOVERY_RECEIPT_MAX_RETRIES = RECOVERY_TIMEOUT_SECONDS / RECEIPT_POLL_INTERVAL_SECONDS;

    private final Web3j web3j;
    private final Web3j fallbackWeb3j;
    private final Credentials masterCredentials;
    private final Web3Config web3Config;
    private final Map<String, SubmittedTransactionContext> submittedTransactions = new ConcurrentHashMap<>();

    private record SubmittedTransactionContext(
            String txHash,
            String rawTransactionHex,
            String signerAddress,
            Credentials signerCredentials,
            BigInteger nonce,
            BigInteger gasPrice,
            BigInteger gasLimit,
            String toAddress,
            BigInteger value,
            String encodedData,
            String functionName
    ) {
    }

    public BlockchainService(
            Web3j web3j,
            @Qualifier("fallbackWeb3j") Web3j fallbackWeb3j,
            Credentials masterCredentials,
            Web3Config web3Config
    ) {
        this.web3j = web3j;
        this.fallbackWeb3j = fallbackWeb3j;
        this.masterCredentials = masterCredentials;
        this.web3Config = web3Config;
    }

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

    /**
     * PBMSmartAccount.updateWalletLimit()를 호출하여 지갑 총 한도를 변경한다.
     * owner인 사용자 Credentials로 서명해야 한다.
     *
     * @param walletAddress   PBMSmartAccount 주소
     * @param newLimitKrw     새 한도 (KRW)
     * @param userCredentials 사용자 EOA Credentials (owner)
     * @return 트랜잭션 해시
     */
    public String updateWalletLimit(String walletAddress, long newLimitKrw, Credentials userCredentials) {
        BigInteger limitInWei = BigInteger.valueOf(newLimitKrw).multiply(TOKEN_DECIMALS);
        log.info("지갑 한도 변경 - wallet: {}, newLimit: {} KRW", walletAddress, newLimitKrw);

        Function fn = new Function(
                "updateWalletLimit",
                List.of(new Uint256(limitInWei)),
                List.of()
        );

        String txHash = sendTransaction(userCredentials, walletAddress, fn, BigInteger.ZERO);
        waitForReceipt(txHash);
        log.info("updateWalletLimit tx 완료 - wallet: {}, newLimit: {} KRW, txHash: {}", walletAddress, newLimitKrw, txHash);
        return txHash;
    }

    /**
     * PBMSmartAccount.totalAllocated()를 조회한다 (현재 활성 세션키들의 한도 합계).
     *
     * @param walletAddress PBMSmartAccount 주소
     * @return totalAllocated (wei 단위)
     */
    public BigInteger getTotalAllocated(String walletAddress) {
        Function fn = new Function(
                "totalAllocated",
                List.of(),
                List.of(new TypeReference<Uint256>() {})
        );
        String result = ethCall(masterCredentials.getAddress(), walletAddress, fn);
        List<Type> decoded = FunctionReturnDecoder.decode(result, fn.getOutputParameters());
        return ((Uint256) decoded.get(0)).getValue();
    }

    /**
     * PBMSmartAccount.walletLimit()를 조회한다.
     *
     * @param walletAddress PBMSmartAccount 주소
     * @return walletLimit (wei 단위)
     */
    public BigInteger getWalletLimit(String walletAddress) {
        Function fn = new Function(
                "walletLimit",
                List.of(),
                List.of(new TypeReference<Uint256>() {})
        );
        String result = ethCall(masterCredentials.getAddress(), walletAddress, fn);
        List<Type> decoded = FunctionReturnDecoder.decode(result, fn.getOutputParameters());
        return ((Uint256) decoded.get(0)).getValue();
    }

    // ──────────────────────────────────────────────────────────────────
    // AI 에이전트 가스비 지원
    // ──────────────────────────────────────────────────────────────────

    /**
     * AI 에이전트 주소에 최소 ETH(0.00005 ETH)를 전송한다.
     * <p>
     * 기존 호환성을 위한 메서드. 기본 지원 금액(AI_AGENT_FUND_AMOUNT)으로
     * {@link #fundAiAgent(String, BigInteger)}를 호출한다.
     *
     * @param aiAgentAddress ETH를 받을 AI 에이전트 주소
     * @return 트랜잭션 receipt
     */
    public TransactionReceipt fundAiAgent(String aiAgentAddress) {
        return fundAiAgent(aiAgentAddress, AI_AGENT_FUND_AMOUNT);
    }

    /**
     * AI 에이전트 주소에 지정된 금액의 ETH를 전송한다.
     * <p>
     * executeAIPayment()는 AI 에이전트 키로 서명해야 하므로,
     * AI 에이전트 주소에 ETH가 필요하다.
     * 이 메서드는 waitForReceipt()를 호출하여 트랜잭션 confirm을 기다리므로,
     * 반환 시점에는 AI 에이전트가 ETH를 받은 상태가 보장된다.
     *
     * @param aiAgentAddress ETH를 받을 AI 에이전트 주소
     * @param amount         전송할 ETH 금액 (wei)
     * @return 트랜잭션 receipt
     */
    public TransactionReceipt fundAiAgent(String aiAgentAddress, BigInteger amount) {
        log.info("AI 에이전트 ETH 지원 - aiAgent: {}, amount: {} wei", aiAgentAddress, amount);

        try {
            BigInteger nonce = getNonce(masterCredentials.getAddress());
            BigInteger gasPrice = getGasPrice(); // 네트워크 gas price 동적 조회
            log.info("AI 에이전트 ETH 전송 - nonce: {}, gasPrice: {} wei, amount: {} wei, to: {}",
                    nonce, gasPrice, amount, aiAgentAddress);

            // ETH 전송 트랜잭션 생성 (21,000 gas는 ETH 전송 기본 가스)
            RawTransaction rawTx = RawTransaction.createEtherTransaction(
                    nonce, gasPrice, BigInteger.valueOf(21_000L), aiAgentAddress, amount
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
            // 다음 트랜잭션과 nonce 충돌 방지를 위해 확정 대기
            TransactionReceipt receipt = waitForReceipt(txHash);
            log.info("AI 에이전트 ETH 지원 완료 - txHash: {}", txHash);
            return receipt;
        } catch (Exception e) {
            throw new RuntimeException("AI 에이전트 ETH 지원 실패: " + e.getMessage(), e);
        }
    }

    /**
     * AI 에이전트 주소의 ETH 잔액을 확인하고 필요 가스비보다 부족하면 ETH를 지원한다.
     * <p>
     * executeAIPayment()는 AI 에이전트 키로 서명해야 하므로,
     * AI 에이전트 주소에 최소한의 ETH(가스비)가 있어야 한다.
     * fundAiAgent()는 waitForReceipt()를 호출하여 트랜잭션 confirm을 기다리므로,
     * 이 메서드 반환 시점에는 AI 에이전트가 ETH를 받은 상태가 보장된다.
     *
     * @param aiAgentAddress AI 에이전트 주소
     * @param requiredEth    필요 가스비 (wei)
     */
    public void ensureAiAgentFunded(String aiAgentAddress, BigInteger requiredEth) {
        try {
            // AI 에이전트의 현재 ETH 잔액 조회 (PENDING 블록 기준으로 멤풀에 대기 중인 tx 포함)
            BigInteger balance = web3j.ethGetBalance(aiAgentAddress, DefaultBlockParameterName.PENDING)
                    .send().getBalance();

            log.info("AI 에이전트 ETH 잔액 확인 - aiAgent: {}, balance: {} wei, required: {} wei",
                    aiAgentAddress, balance, requiredEth);

            // 잔액이 필요 가스비보다 부족하면 부족한 금액만 지원
            if (balance.compareTo(requiredEth) < 0) {
                BigInteger shortage = requiredEth.subtract(balance);
                log.info("AI 에이전트 ETH 부족 - 잔액: {} wei, 필요: {} wei, 부족분: {} wei 지원 시작",
                        balance, requiredEth, shortage);
                fundAiAgent(aiAgentAddress, shortage);
            } else {
                log.debug("AI 에이전트 ETH 잔액 충분 - aiAgent: {}, balance: {} wei",
                        aiAgentAddress, balance);
            }
        } catch (Exception e) {
            log.warn("AI 에이전트 잔액 확인 실패, ETH 지원 시도 - aiAgent: {}, error: {}",
                    aiAgentAddress, e.getMessage());
            // 잔액 확인 실패 시에도 일단 기본 금액 지원 시도 (이미 잔액이 충분했을 수 있으므로 결제는 진행)
            try {
                fundAiAgent(aiAgentAddress);
            } catch (Exception fundError) {
                log.error("AI 에이전트 ETH 지원 실패 - aiAgent: {}, error: {}",
                        aiAgentAddress, fundError.getMessage());
                // ETH 지원 실패해도 결제는 시도 (이미 잔액이 충분했을 수 있음)
            }
        }
    }

    /**
     * 사용자 EOA 주소에 고정 금액(0.002 ETH)의 가스비용 ETH를 지원한다.
     * <p>
     * 기존 호환성을 위한 메서드. 기본 지원 금액(0.002 ETH)으로
     * {@link #fundUserAddress(String, BigInteger)}를 호출한다.
     *
     * @param userAddress ETH를 받을 사용자 EOA 주소
     * @return 트랜잭션 receipt
     */
    public TransactionReceipt fundUserAddress(String userAddress) {
        // 0.002 ETH — 세션키 등록/취소 약 20회 가스비 (5 Gwei 기준)
        BigInteger fundAmount = BigInteger.valueOf(2_000_000_000_000_000L);
        return fundUserAddress(userAddress, fundAmount);
    }

    /**
     * 사용자 EOA 주소에 지정된 금액의 ETH를 전송한다.
     * <p>
     * 사용자 EOA는 addSessionKey, revokeSessionKey 등 owner 권한 트랜잭션을 서명해야 하므로
     * ETH가 필요하다. 부족분만 계산하여 전송할 때 사용한다.
     * 이 메서드는 waitForReceipt()를 호출하여 트랜잭션 confirm을 기다리므로,
     * 반환 시점에는 사용자 EOA가 ETH를 받은 상태가 보장된다.
     *
     * @param userAddress ETH를 받을 사용자 EOA 주소
     * @param amount      전송할 ETH 금액 (wei)
     * @return 트랜잭션 receipt
     */
    public TransactionReceipt fundUserAddress(String userAddress, BigInteger amount) {
        log.info("사용자 EOA ETH 지원 - userAddress: {}, amount: {} wei", userAddress, amount);

        try {
            BigInteger nonce = getNonce(masterCredentials.getAddress());
            BigInteger gasPrice = getGasPrice(); // 네트워크 gas price 동적 조회
            log.info("사용자 EOA ETH 전송 - nonce: {}, gasPrice: {} wei, amount: {} wei, to: {}",
                    nonce, gasPrice, amount, userAddress);
            RawTransaction rawTx = RawTransaction.createEtherTransaction(
                    nonce, gasPrice, BigInteger.valueOf(21_000L), userAddress, amount
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

    /**
     * 사용자 EOA 주소의 ETH 잔액을 확인하고 필요 가스비보다 부족하면 부족한 금액만큼 ETH를 지원한다.
     * <p>
     * addSessionKey(), revokeSessionKey() 등 사용자 EOA 키로 서명해야 하는
     * 트랜잭션 실행 전에 호출하여 가스비를 확보한다.
     * fundUserAddress()는 waitForReceipt()를 호출하여 트랜잭션 confirm을 기다리므로,
     * 이 메서드 반환 시점에는 사용자 EOA가 ETH를 받은 상태가 보장된다.
     *
     * @param userAddress  사용자 EOA 주소
     * @param requiredGas  필요 가스비 (wei)
     */
    public void ensureUserEoaFunded(String userAddress, BigInteger requiredGas) {
        try {
            // 사용자 EOA의 현재 ETH 잔액 조회 (PENDING 블록 기준으로 멤풀에 대기 중인 tx 포함)
            BigInteger balance = web3j.ethGetBalance(userAddress, DefaultBlockParameterName.PENDING)
                    .send().getBalance();

            log.info("사용자 EOA ETH 잔액 확인 - userAddress: {}, balance: {} wei, required: {} wei",
                    userAddress, balance, requiredGas);

            // 잔액이 필요 가스비보다 부족하면 부족한 금액만 지원
            if (balance.compareTo(requiredGas) < 0) {
                BigInteger shortage = requiredGas.subtract(balance);
                log.info("사용자 EOA ETH 부족 - 잔액: {} wei, 필요: {} wei, 부족분: {} wei 지원 시작",
                        balance, requiredGas, shortage);
                fundUserAddress(userAddress, shortage);
            } else {
                log.debug("사용자 EOA ETH 잔액 충분 - userAddress: {}, balance: {} wei",
                        userAddress, balance);
            }
        } catch (Exception e) {
            log.warn("사용자 EOA 잔액 확인 실패, ETH 지원 시도 - userAddress: {}, error: {}",
                    userAddress, e.getMessage());
            // 잔액 확인 실패 시에도 일단 기본 금액 지원 시도 (이미 잔액이 충분했을 수 있으므로 트랜잭션은 진행)
            try {
                fundUserAddress(userAddress);
            } catch (Exception fundError) {
                log.error("사용자 EOA ETH 지원 실패 - userAddress: {}, error: {}",
                        userAddress, fundError.getMessage());
                // ETH 지원 실패해도 트랜잭션은 시도 (이미 잔액이 충분했을 수 있음)
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // PBM 결제 실행 (Paymaster 연동)
    // ──────────────────────────────────────────────────────────────────

    /**
     * PBMSmartAccount.executeAIPayment()를 호출하여 PBM 토큰 결제를 실행한다.
     * <p>
     * 결제 흐름:
     * 0. ensureAiAgentFunded() → AI 에이전트 ETH 잔액 확인 및 부족 시 ETH 지원
     * 1. validatePaymasterUserOp() → 사용자 PBM 잔고 검증
     * 2. executeAIPayment() → AI 에이전트 키로 서명하여 결제 실행
     * 3. 트랜잭션 receipt에서 실제 가스 사용량 계산
     * 4. postOp() → 가스비를 사용자 스마트 지갑의 PBM 토큰으로 차감
     *
     * @param walletAddress     PBMSmartAccount 주소
     * @param aiAgentPrivateKey AI 에이전트 개인키
     * @param recipientAddress  PBM 토큰 수신 주소
     * @param amountKrw         결제 금액 (KRW)
     * @return 결제 실행 결과 (트랜잭션 해시 + 실제 가스비 ETH wei)
     */
    public PaymentExecutionResult executePayment(String walletAddress, String aiAgentPrivateKey,
                                 String recipientAddress, long amountKrw) {
        Credentials aiAgent = Credentials.create(aiAgentPrivateKey);
        BigInteger amountInWei = BigInteger.valueOf(amountKrw).multiply(TOKEN_DECIMALS);

        log.info("PBM 결제 시작 - wallet: {}, aiAgent: {}, amount: {} KRW",
                walletAddress, aiAgent.getAddress(), amountKrw);

        // 네트워크 gas price 동적 조회 — 조회 실패 시 5 Gwei fallback
        BigInteger gasPrice = getGasPrice();

        // 0. AI 에이전트 ETH 잔액 확인 및 부족 시 지원
        //    executeAIPayment()는 AI 에이전트 키로 서명하므로 AI 에이전트 주소에 가스비 ETH가 있어야 한다.
        BigInteger estimatedGasEth = GAS_LIMIT.multiply(gasPrice);
        ensureAiAgentFunded(aiAgent.getAddress(), estimatedGasEth);

        // 1. 결제 전 사용자 PBM 잔고 검증 (예상 가스비 기준)
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

        log.info("PBM 결제 완료 - wallet: {}, txHash: {}, actualGasEth: {} wei", walletAddress, txHash, actualGasEth);
        return new PaymentExecutionResult(txHash, actualGasEth);
    }

    /**
     * 결제 실행 결과 DTO.
     *
     * @param txHash           블록체인 트랜잭션 해시
     * @param actualGasEthWei  실제 소모된 가스비 (ETH wei 단위)
     */
    public record PaymentExecutionResult(String txHash, BigInteger actualGasEthWei) {}

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
        deductGasCostFromWallet(walletAddress, actualEthFee, null);
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
    // 토큰 충전
    // ──────────────────────────────────────────────────────────────────

    /**
     * 마스터 지갑에서 사용자 PBMSmartAccount로 PBM 토큰을 전송한다 (충전).
     * <p>
     * ERC-20 표준 transfer(address to, uint256 amount)를 호출한다.
     * 마스터 지갑이 서명자이므로 마스터 지갑에 충분한 PBM 잔액이 있어야 한다.
     *
     * @param toAddress 수신 주소 (사용자 PBMSmartAccount 컨트랙트 주소)
     * @param amountWei 전송할 토큰 수량 (wei 단위, 18 decimals)
     * @return 온체인 확정된 TransactionReceipt (txHash, gasUsed, effectiveGasPrice 포함)
     */
    public TransactionReceipt transferPbmToken(String toAddress, BigInteger amountWei) {
        return transferPbmToken(toAddress, amountWei, null);
    }

    /**
     * 마스터 지갑에서 사용자 PBMSmartAccount로 PBM 토큰을 전송한다 (충전).
     * progressCallback을 통해 TX_SENT / TX_CONFIRMED 단계 알림을 받을 수 있다.
     *
     * @param toAddress        수신 주소 (사용자 PBMSmartAccount 컨트랙트 주소)
     * @param amountWei        전송할 토큰 수량 (wei 단위, 18 decimals)
     * @param progressCallback 진행 단계 콜백 (null 허용) — "TX_SENT:{txHash}" / "TX_CONFIRMED:{blockNumber}" 형태
     * @return 온체인 확정된 TransactionReceipt
     */
    public TransactionReceipt transferPbmToken(String toAddress, BigInteger amountWei,
                                               Consumer<String> progressCallback) {
        log.info("PBM 토큰 충전 시작 - to: {}, amount: {} wei", toAddress, amountWei);

        // 명령어를 블록체인이 알아들을 수 있는 형태로 포장함
        Function fn = new Function(
                "transfer",
                Arrays.asList(new Address(toAddress), new Uint256(amountWei)),
                List.of(new TypeReference<Bool>() {})
        );

        // 포장한 명령을 블록체인 네트워크로 쏜다.
        String txHash = sendTransaction(masterCredentials, web3Config.getPbmTokenAddress(), fn, BigInteger.ZERO);
        if (progressCallback != null) progressCallback.accept("TX_SENT:" + txHash);

        // 블록체인 네트워크에서 거래가 완전히 승인(채굴)될 때까지 기다린다.
        TransactionReceipt receipt = waitForReceipt(txHash);
        if (progressCallback != null) progressCallback.accept("TX_CONFIRMED:" + receipt.getBlockNumber());

        log.info("PBM 토큰 충전 완료 - to: {}, amount: {} wei, txHash: {}", toAddress, amountWei, txHash);
        return receipt;
    }

    /**
     * 가스비 PBM 차감 — progressCallback 지원 버전.
     *
     * @param walletAddress    사용자 PBMSmartAccount 주소
     * @param actualEthFee     실제 소모된 ETH 가스비 (wei)
     * @param progressCallback 진행 단계 콜백 (null 허용) — "GAS_TX_SENT:{txHash}" / "GAS_TX_CONFIRMED" 형태
     */
    public void deductGasCostFromWallet(String walletAddress, BigInteger actualEthFee,
                                        Consumer<String> progressCallback) {
        log.info("가스비 PBM 차감 시작 - wallet: {}, actualEthFee: {} wei", walletAddress, actualEthFee);

        // 수수료 정산을 담당하는 Paymaster에게 postOp(사후 정산) 명령을 내린다.
        Function fn = new Function(
                "postOp",
                Arrays.asList(new Address(walletAddress), new Uint256(actualEthFee)),
                List.of()
        );

        String txHash = sendTransaction(masterCredentials, web3Config.getPaymasterAddress(), fn, BigInteger.ZERO);
        if (progressCallback != null) progressCallback.accept("GAS_TX_SENT:" + txHash);

        waitForReceipt(txHash);
        if (progressCallback != null) progressCallback.accept("GAS_TX_CONFIRMED");

        log.info("가스비 PBM 차감 완료 - wallet: {}, txHash: {}", walletAddress, txHash);
    }

    /**
     * TransactionReceipt에서 실제 소모된 ETH 가스비를 계산한다.
     * <p>
     * actualEthFee = gasUsed × effectiveGasPrice (EIP-1559 기준)
     *
     * @param receipt 온체인 확정된 트랜잭션 receipt
     * @return 실제 소모된 ETH 가스비 (wei 단위)
     */
    public BigInteger calculateEthFee(TransactionReceipt receipt) {
        BigInteger gasUsed = receipt.getGasUsed();
        String effectiveGasPriceHex = receipt.getEffectiveGasPrice();
        BigInteger effectiveGasPrice;
        if (effectiveGasPriceHex != null && !effectiveGasPriceHex.isBlank()) {
            effectiveGasPrice = Numeric.decodeQuantity(effectiveGasPriceHex);
        } else {
            // EIP-1559 미지원 네트워크 폴백: 현재 가스 가격 사용
            effectiveGasPrice = getGasPrice();
        }
        BigInteger ethFee = gasUsed.multiply(effectiveGasPrice);
        log.info("가스비 계산 - gasUsed: {}, effectiveGasPrice: {} wei, totalEthFee: {} wei",
                gasUsed, effectiveGasPrice, ethFee);
        return ethFee;
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
            String signerAddress = credentials.getAddress();

            // nonce gap 감지: LATEST와 PENDING이 다르면 stuck tx가 있다는 의미
            BigInteger latestNonce = getNonce(signerAddress, true);
            BigInteger pendingNonce = getNonce(signerAddress, false);
            BigInteger nonce;

            if (!latestNonce.equals(pendingNonce)) {
                // pending tx가 있지만 체인에 포함 안 됨 → LATEST nonce로 덮어쓴다.
                // 같은 nonce + 더 높은 gasPrice로 보내면 기존 pending tx를 replacement한다.
                log.warn("[BlockchainService] nonce gap 감지 → LATEST nonce 사용 (replacement tx) - "
                        + "address={}, latest={}, pending={}, fn={}",
                        signerAddress, latestNonce, pendingNonce, function.getName());
                nonce = latestNonce;
                // replacement tx는 기존 tx보다 gasPrice가 높아야 함 → 2배로 상향
                gasPrice = gasPrice.multiply(BigInteger.TWO);
                log.info("[BlockchainService] replacement gasPrice 상향 - {} wei", gasPrice);
            } else {
                nonce = pendingNonce;
            }

            log.info("트랜잭션 전송 - fn: {}, nonce: {}, gasPrice: {} wei, gasLimit: {}, to: {}",
                    function.getName(), nonce, gasPrice, gasLimit, toAddress);
            RawTransaction rawTx = RawTransaction.createTransaction(
                    nonce, gasPrice, gasLimit, toAddress, value, encoded
            );
            // EIP-155: 체인 ID 포함 서명으로 다른 네트워크(메인넷 등) 재사용 공격 방지
            byte[] signed = TransactionEncoder.signMessage(rawTx, CHAIN_ID, credentials);
            String rawTransactionHex = Numeric.toHexString(signed);
            EthSendTransaction sent = web3j.ethSendRawTransaction(rawTransactionHex).send();

            if (sent.hasError()) {
                throw new RuntimeException("트랜잭션 오류: " + sent.getError().getMessage());
            }
            String txHash = sent.getTransactionHash();
            if (txHash == null || txHash.isBlank()) {
                throw new RuntimeException("트랜잭션 해시 없음 (제출 실패 가능)");
            }
            submittedTransactions.put(txHash, new SubmittedTransactionContext(
                    txHash,
                    rawTransactionHex,
                    signerAddress,
                    credentials,
                    nonce,
                    gasPrice,
                    gasLimit,
                    toAddress,
                    value,
                    encoded,
                    function.getName()
            ));
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
     * 트랜잭션 receipt를 폴링하여 확정될 때까지 대기한다 (외부 호출용).
     *
     * @param txHash 대기할 트랜잭션 해시
     * @return 확정된 TransactionReceipt
     * @throws RuntimeException 타임아웃 시
     */
    public TransactionReceipt waitForReceiptPublic(String txHash) {
        return waitForReceipt(txHash);
    }

    /**
     * 마스터 지갑의 stuck된 pending tx를 빈 replacement tx로 대체하여 해소한다.
     * 지정된 nonce로 자기 자신에게 0 ETH를 높은 gasPrice로 전송한다.
     *
     * @param stuckNonce stuck된 nonce 값
     * @return replacement 트랜잭션 해시
     */
    public String unstickMasterNonce(BigInteger stuckNonce) {
        String masterAddress = masterCredentials.getAddress();
        BigInteger gasPrice = getGasPrice().multiply(BigInteger.valueOf(5));
        BigInteger gasLimit = BigInteger.valueOf(21_000);

        log.info("마스터 지갑 nonce unstick 시도 - address: {}, nonce: {}, gasPrice: {} wei",
                masterAddress, stuckNonce, gasPrice);

        RawTransaction rawTx = RawTransaction.createEtherTransaction(
                stuckNonce, gasPrice, gasLimit, masterAddress, BigInteger.ZERO
        );
        byte[] signed = TransactionEncoder.signMessage(rawTx, CHAIN_ID, masterCredentials);
        try {
            EthSendTransaction sent = web3j.ethSendRawTransaction(Numeric.toHexString(signed)).send();
            if (sent.hasError()) {
                throw new RuntimeException("unstick tx 오류: " + sent.getError().getMessage());
            }
            String txHash = sent.getTransactionHash();
            log.info("unstick tx 전송 완료 - nonce: {}, txHash: {}", stuckNonce, txHash);
            return txHash;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("unstick tx 전송 실패", e);
        }
    }

    /**
     * 마스터 지갑의 latest/pending nonce 정보를 반환한다.
     */
    public BigInteger[] getMasterNonceInfo() {
        try {
            String masterAddress = masterCredentials.getAddress();
            BigInteger latest = getNonce(masterAddress, true);
            BigInteger pending = getNonce(masterAddress, false);
            return new BigInteger[]{latest, pending};
        } catch (Exception e) {
            throw new RuntimeException("마스터 지갑 nonce 조회 실패", e);
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
                Optional<TransactionReceipt> receipt = findReceiptAcrossClients(txHash);
                if (receipt.isPresent()) {
                    TransactionReceipt resolved = validateReceipt(txHash, receipt.get());
                    submittedTransactions.remove(txHash);
                    return resolved;
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

        SubmittedTransactionContext context = submittedTransactions.get(txHash);
        if (context != null) {
            TransactionReceipt recovered = attemptReceiptRecovery(context);
            submittedTransactions.remove(txHash);
            return recovered;
        }

        throw new RuntimeException("트랜잭션 확정 타임아웃 (" + RECEIPT_TIMEOUT_SECONDS + "초) - txHash: " + txHash);
    }

    private Optional<TransactionReceipt> findReceiptAcrossClients(String txHash) throws Exception {
        Optional<TransactionReceipt> primaryReceipt = getReceipt(web3j, txHash);
        if (primaryReceipt.isPresent()) {
            return primaryReceipt;
        }

        if (fallbackWeb3j != null && fallbackWeb3j != web3j) {
            Optional<TransactionReceipt> fallbackReceipt = getReceipt(fallbackWeb3j, txHash);
            if (fallbackReceipt.isPresent()) {
                log.info("fallback RPC에서 receipt 확인 - txHash: {}", txHash);
                return fallbackReceipt;
            }
        }

        return Optional.empty();
    }

    private TransactionReceipt attemptReceiptRecovery(SubmittedTransactionContext context) {
        try {
            boolean primaryVisible = isTransactionVisible(web3j, context.txHash());
            boolean fallbackVisible = fallbackWeb3j != null
                    && fallbackWeb3j != web3j
                    && isTransactionVisible(fallbackWeb3j, context.txHash());

            BigInteger primaryLatestNonce = getNonce(web3j, context.signerAddress(), true);
            BigInteger primaryPendingNonce = getNonce(web3j, context.signerAddress(), false);
            BigInteger fallbackLatestNonce = null;
            BigInteger fallbackPendingNonce = null;
            if (fallbackWeb3j != null && fallbackWeb3j != web3j) {
                fallbackLatestNonce = getNonce(fallbackWeb3j, context.signerAddress(), true);
                fallbackPendingNonce = getNonce(fallbackWeb3j, context.signerAddress(), false);
            }

            log.warn("stuck tx 진단 - txHash: {}, fn: {}, signer: {}, nonce: {}, primaryVisible: {}, fallbackVisible: {}, primaryLatest: {}, primaryPending: {}, fallbackLatest: {}, fallbackPending: {}",
                    context.txHash(),
                    context.functionName(),
                    context.signerAddress(),
                    context.nonce(),
                    primaryVisible,
                    fallbackVisible,
                    primaryLatestNonce,
                    primaryPendingNonce,
                    fallbackLatestNonce,
                    fallbackPendingNonce);

            if (primaryVisible && !fallbackVisible && fallbackWeb3j != null && fallbackWeb3j != web3j) {
                log.warn("primary RPC local pending으로 판단되어 fallback RPC로 raw tx 재브로드캐스트 시도 - txHash: {}",
                        context.txHash());
                broadcastRawTransaction(fallbackWeb3j, context.rawTransactionHex(), context.txHash(), "fallback");
                Optional<TransactionReceipt> rebroadcastReceipt = pollReceipt(context.txHash(), RECOVERY_RECEIPT_MAX_RETRIES);
                if (rebroadcastReceipt.isPresent()) {
                    return validateReceipt(context.txHash(), rebroadcastReceipt.get());
                }
            }

            if (!primaryVisible && !fallbackVisible) {
                log.warn("모든 RPC에서 tx가 보이지 않아 primary/fallback RPC로 재브로드캐스트 시도 - txHash: {}",
                        context.txHash());
                broadcastRawTransaction(web3j, context.rawTransactionHex(), context.txHash(), "primary");
                if (fallbackWeb3j != null && fallbackWeb3j != web3j) {
                    broadcastRawTransaction(fallbackWeb3j, context.rawTransactionHex(), context.txHash(), "fallback");
                }
                Optional<TransactionReceipt> rebroadcastReceipt = pollReceipt(context.txHash(), RECOVERY_RECEIPT_MAX_RETRIES);
                if (rebroadcastReceipt.isPresent()) {
                    return validateReceipt(context.txHash(), rebroadcastReceipt.get());
                }
            }

            if (!isMasterAddress(context.signerAddress())
                    && primaryPendingNonce.compareTo(primaryLatestNonce) > 0
                    && primaryLatestNonce.compareTo(context.nonce()) <= 0) {
                log.warn("사용자 EOA pending nonce 정체 감지 - replacement tx 시도 - signer: {}, nonce: {}, oldGasPrice: {}",
                        context.signerAddress(), context.nonce(), context.gasPrice());

                String replacementTxHash = submitReplacementTransaction(context);
                Optional<TransactionReceipt> replacementReceipt = pollReceipt(replacementTxHash, RECOVERY_RECEIPT_MAX_RETRIES);
                if (replacementReceipt.isPresent()) {
                    return validateReceipt(replacementTxHash, replacementReceipt.get());
                }
                throw new RuntimeException(
                        "replacement tx 전송 후에도 receipt를 찾지 못했습니다. originalTxHash=" + context.txHash()
                                + ", replacementTxHash=" + replacementTxHash);
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("stuck tx 복구 중 오류 - txHash: " + context.txHash() + ", error: " + e.getMessage(), e);
        }

        throw new RuntimeException(
                "트랜잭션 확정 타임아웃 (" + RECEIPT_TIMEOUT_SECONDS + "초 + 복구 "
                        + RECOVERY_TIMEOUT_SECONDS + "초) - txHash: " + context.txHash()
                        + " (primary/fallback RPC 모두 receipt 미확인, signer=" + context.signerAddress() + ")");
    }

    private Optional<TransactionReceipt> pollReceipt(String txHash, int maxRetries) throws Exception {
        for (int i = 0; i < maxRetries; i++) {
            Thread.sleep(RECEIPT_POLL_INTERVAL_MS);
            Optional<TransactionReceipt> receipt = findReceiptAcrossClients(txHash);
            if (receipt.isPresent()) {
                return receipt;
            }
        }
        return Optional.empty();
    }

    private Optional<TransactionReceipt> getReceipt(Web3j client, String txHash) throws Exception {
        EthGetTransactionReceipt response = client.ethGetTransactionReceipt(txHash).send();
        return response.getTransactionReceipt();
    }

    private boolean isTransactionVisible(Web3j client, String txHash) throws Exception {
        EthTransaction response = client.ethGetTransactionByHash(txHash).send();
        return response.getTransaction().isPresent();
    }

    private void broadcastRawTransaction(Web3j client, String rawTransactionHex, String expectedTxHash, String label) {
        try {
            EthSendTransaction sent = client.ethSendRawTransaction(rawTransactionHex).send();
            if (sent.hasError()) {
                String message = sent.getError().getMessage();
                if (message != null && (message.contains("already known") || message.contains("known transaction"))) {
                    log.info("{} RPC raw tx 이미 인지 - txHash: {}, message: {}", label, expectedTxHash, message);
                    return;
                }
                log.warn("{} RPC raw tx 재브로드캐스트 실패 - txHash: {}, error: {}", label, expectedTxHash, message);
                return;
            }

            String rebroadcastedHash = sent.getTransactionHash();
            log.info("{} RPC raw tx 재브로드캐스트 성공 - expectedTxHash: {}, actualTxHash: {}",
                    label, expectedTxHash, rebroadcastedHash);
        } catch (Exception e) {
            log.warn("{} RPC raw tx 재브로드캐스트 예외 - txHash: {}, error: {}", label, expectedTxHash, e.getMessage());
        }
    }

    private String submitReplacementTransaction(SubmittedTransactionContext context) throws Exception {
        BigInteger candidateGasPrice = getGasPrice().multiply(BigInteger.valueOf(2));
        BigInteger replacementGasPrice = context.gasPrice().multiply(BigInteger.valueOf(2)).max(candidateGasPrice);

        RawTransaction replacementTx = RawTransaction.createTransaction(
                context.nonce(),
                replacementGasPrice,
                context.gasLimit(),
                context.toAddress(),
                context.value(),
                context.encodedData()
        );
        byte[] signed = TransactionEncoder.signMessage(replacementTx, CHAIN_ID, context.signerCredentials());
        String rawTransactionHex = Numeric.toHexString(signed);

        EthSendTransaction sent = web3j.ethSendRawTransaction(rawTransactionHex).send();
        if (sent.hasError()) {
            throw new RuntimeException("replacement tx 오류: " + sent.getError().getMessage());
        }

        String replacementTxHash = sent.getTransactionHash();
        submittedTransactions.remove(context.txHash());
        submittedTransactions.put(replacementTxHash, new SubmittedTransactionContext(
                replacementTxHash,
                rawTransactionHex,
                context.signerAddress(),
                context.signerCredentials(),
                context.nonce(),
                replacementGasPrice,
                context.gasLimit(),
                context.toAddress(),
                context.value(),
                context.encodedData(),
                context.functionName()
        ));

        log.info("replacement tx 전송 완료 - originalTxHash: {}, replacementTxHash: {}, nonce: {}, gasPrice: {}",
                context.txHash(), replacementTxHash, context.nonce(), replacementGasPrice);

        if (fallbackWeb3j != null && fallbackWeb3j != web3j) {
            broadcastRawTransaction(fallbackWeb3j, rawTransactionHex, replacementTxHash, "fallback");
        }

        return replacementTxHash;
    }

    private TransactionReceipt validateReceipt(String txHash, TransactionReceipt receipt) {
        log.info("트랜잭션 확정 완료 - txHash: {}, blockNumber: {}, status: {}",
                txHash, receipt.getBlockNumber(), receipt.getStatus());
        if ("0x0".equals(receipt.getStatus())) {
            throw new RuntimeException("트랜잭션 revert - txHash: " + txHash
                    + " (가스 부족 또는 컨트랙트 로직 실패)");
        }
        return receipt;
    }

    private boolean isMasterAddress(String address) {
        return masterCredentials.getAddress().equalsIgnoreCase(address);
    }

    /**
     * 현재 Sepolia 네트워크의 gas price를 동적으로 조회하고 50% buffer를 추가한다.
     * <p>
     * buffer를 두는 이유: 조회 시점과 트랜잭션이 블록에 포함되는 시점 사이에
     * gas price가 상승할 가능성이 있으므로, 일부러 여유를 둬서
     * "replacement transaction underpriced" 오류나 채굴 지연을 방지한다.
     * <p>
     * 조회 실패 시 fallback 값 6 Gwei를 사용한다.
     * (5 Gwei는 Sepolia에서 트랜잭션이 정상 처리되는 최저 범위)
     *
     * @return gas price (wei 단위, 50% buffer 적용)
     */
    public BigInteger getGasPrice() {
        try {
            BigInteger baseGasPrice = web3j.ethGasPrice().send().getGasPrice();
            // 50% buffer: Sepolia 네트워크 혼잡 시 빠른 블록 포함을 위해 여유 확보
            return baseGasPrice.multiply(BigInteger.valueOf(150)).divide(BigInteger.valueOf(100));
        } catch (Exception e) {
            log.warn("네트워크 gas price 조회 실패, fallback 6 Gwei 사용: {}", e.getMessage());
            return BigInteger.valueOf(6_000_000_000L); // fallback gas price
        }
    }

    /**
     * 주어진 주소의 nonce를 조회한다.
     * <p>
     * PENDING 기준으로 조회하여 멤풀에 대기 중인 트랜잭션의 nonce도 포함한다.
     * LATEST 기준이면 pending tx를 무시해 nonce 충돌("replacement transaction underpriced")이 발생한다.
     */
    /**
     * 주어진 주소의 nonce를 조회한다.
     * <p>
     * LATEST(체인 확정)와 PENDING(멤풀 포함)을 모두 조회하여 nonce gap을 감지한다.
     * gap이 있으면 self-transfer(0 ETH)로 빈 nonce를 채운 뒤 정상 nonce를 반환한다.
     *
     * @param address 조회할 주소
     * @return 사용할 nonce (gap이 없으면 PENDING 값, gap 복구 후에는 복구 완료된 다음 nonce)
     */
    private BigInteger getNonce(String address) throws Exception {
        BigInteger latestNonce = web3j
                .ethGetTransactionCount(address, DefaultBlockParameterName.LATEST)
                .send().getTransactionCount();
        BigInteger pendingNonce = web3j
                .ethGetTransactionCount(address, DefaultBlockParameterName.PENDING)
                .send().getTransactionCount();

        if (!latestNonce.equals(pendingNonce)) {
            log.warn("[BlockchainService] nonce gap 감지 - address={}, latest={}, pending={}, gap={}",
                    address, latestNonce, pendingNonce, pendingNonce.subtract(latestNonce));

            // gap이 있으면 LATEST nonce부터 self-transfer로 빈 nonce를 채운다.
            // 이 주소의 Credentials가 필요하므로, master/user 구분 없이 처리한다.
            resolveNonceGap(address, latestNonce, pendingNonce);

            // gap 복구 후 최신 nonce를 다시 조회한다.
            BigInteger resolvedNonce = web3j
                    .ethGetTransactionCount(address, DefaultBlockParameterName.PENDING)
                    .send().getTransactionCount();
            log.info("[BlockchainService] nonce gap 복구 완료 - address={}, resolvedNonce={}", address, resolvedNonce);
            return resolvedNonce;
        }

        return pendingNonce;
    }

    /**
     * nonce gap을 self-transfer(자기 자신에게 0 ETH 전송)로 메운다.
     * <p>
     * 체인이 기대하는 nonce(latest)부터 mempool에 걸린 nonce(pending) 직전까지
     * 빈 트랜잭션을 전송하여 후속 트랜잭션이 처리될 수 있도록 한다.
     *
     * @param address      gap이 발생한 주소
     * @param latestNonce  체인 확정 nonce (다음으로 기대하는 nonce)
     * @param pendingNonce 멤풀 포함 nonce (이미 전송된 pending tx 이후 nonce)
     */
    private void resolveNonceGap(String address, BigInteger latestNonce, BigInteger pendingNonce) {
        // 해당 주소의 Credentials 결정: master 주소면 masterCredentials, 아니면 user Credentials
        Credentials credentials;
        if (address.equalsIgnoreCase(masterCredentials.getAddress())) {
            credentials = masterCredentials;
        } else {
            // 사용자 EOA의 경우 — 호출자가 이미 sendTransaction에서 credentials를 전달하므로
            // getNonce 시점에서는 credentials를 알 수 없다.
            // self-transfer 대신 LATEST nonce를 사용하도록 fallback한다.
            log.warn("[BlockchainService] 사용자 EOA nonce gap - LATEST nonce({})로 fallback. "
                    + "pending tx({})는 mempool에서 자동 만료될 때까지 대기", latestNonce, pendingNonce);
            return;
        }

        BigInteger gasPrice = getGasPrice();
        for (BigInteger nonce = latestNonce; nonce.compareTo(pendingNonce) < 0; nonce = nonce.add(BigInteger.ONE)) {
            try {
                log.info("[BlockchainService] nonce gap 복구 self-transfer - address={}, nonce={}", address, nonce);
                RawTransaction rawTx = RawTransaction.createEtherTransaction(
                        nonce, gasPrice, BigInteger.valueOf(21_000L), address, BigInteger.ZERO
                );
                byte[] signed = TransactionEncoder.signMessage(rawTx, CHAIN_ID, credentials);
                EthSendTransaction sent = web3j.ethSendRawTransaction(Numeric.toHexString(signed)).send();
                if (sent.hasError()) {
                    log.error("[BlockchainService] nonce gap 복구 실패 - nonce={}, error={}", nonce, sent.getError().getMessage());
                    break;
                }
                String txHash = sent.getTransactionHash();
                log.info("[BlockchainService] nonce gap 복구 tx 전송 - nonce={}, txHash={}", nonce, txHash);
                waitForReceipt(txHash);
            } catch (Exception e) {
                log.error("[BlockchainService] nonce gap 복구 중 예외 - nonce={}, error={}", nonce, e.getMessage());
                break;
            }
        }
    }

    /**
     * 주어진 주소의 nonce를 조회한다 (gap 복구 없이 단순 조회).
     * <p>
     * gap 복구가 불가능한 상황(사용자 EOA 등)에서 LATEST nonce를 반환하여
     * pending에 걸린 실패 tx를 덮어쓰도록 한다.
     *
     * @param address 조회할 주소
     * @param useLatest true면 LATEST(체인 확정) 기준, false면 PENDING 기준
     * @return nonce 값
     */
    private BigInteger getNonce(String address, boolean useLatest) throws Exception {
        return getNonce(web3j, address, useLatest);
    }

    private BigInteger getNonce(Web3j client, String address, boolean useLatest) throws Exception {
        DefaultBlockParameterName param = useLatest
                ? DefaultBlockParameterName.LATEST
                : DefaultBlockParameterName.PENDING;
        return client.ethGetTransactionCount(address, param).send().getTransactionCount();
    }
}
