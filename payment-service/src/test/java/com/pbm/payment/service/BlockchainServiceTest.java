package com.pbm.payment.service;

import com.pbm.payment.config.Web3Config;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.SignedRawTransaction;
import org.web3j.crypto.TransactionDecoder;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.Request;
import org.web3j.protocol.core.methods.response.EthGasPrice;
import org.web3j.protocol.core.methods.response.EthGetTransactionCount;
import org.web3j.protocol.core.methods.response.EthSendTransaction;

import java.math.BigInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * BlockchainService 단위 테스트.
 * <p>
 * Web3j를 Mock으로 대체하여 실제 블록체인 네트워크 없이
 * 동적 gas price 조회(+ 50% buffer) 및 fallback 동작을 검증한다.
 * <p>
 * 검증 대상:
 * - sendTransaction 경로에서 동적 gas price + 50% buffer 반영 (revokeSessionKey 사용, waitForReceipt 미호출)
 * - ethGasPrice() 조회 실패 시 fallback 6 Gwei 동작
 */
@ExtendWith(MockitoExtension.class)
class BlockchainServiceTest {

    @Mock
    private Web3j web3j;

    @Mock
    private Web3j fallbackWeb3j;

    @Mock
    private Credentials masterCredentials;

    @Mock
    private Web3Config web3Config;

    @InjectMocks
    private BlockchainService blockchainService;

    /** Hardhat 테스트 계정 #0 (유효한 EOA 개인키) */
    private static final String TEST_PRIVATE_KEY = "ac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80";

    /** 50% buffer 적용 헬퍼: getGasPrice() 내부 로직과 동일한 계산 */
    private BigInteger applyBuffer(BigInteger baseGasPrice) {
        return baseGasPrice.multiply(BigInteger.valueOf(150)).divide(BigInteger.valueOf(100));
    }

    @Test
    @DisplayName("ethGasPrice()로 조회한 gas price + 50% buffer가 sendTransaction 트랜잭션에 반영된다")
    void testDynamicGasPriceUsedInSendTransaction() throws Exception {
        // given - 네트워크 gas price = 8 Gwei → 50% buffer 적용 시 12 Gwei
        BigInteger baseGasPrice = BigInteger.valueOf(8_000_000_000L);
        BigInteger expectedGasPrice = applyBuffer(baseGasPrice);
        prepareEthGasPriceMock(baseGasPrice);
        prepareNonceMock(BigInteger.TEN, BigInteger.TEN);

        ArgumentCaptor<String> rawTxCaptor = ArgumentCaptor.forClass(String.class);
        prepareSendRawTransactionMock(rawTxCaptor, "0xrevoke-tx-hash");

        // revokeSessionKey는 waitForReceipt를 호출하지 않아 빠르게 검증 가능
        Credentials userCredentials = Credentials.create(TEST_PRIVATE_KEY);

        // Hardhat 테스트 계정 주소 (유효한 hex 형식의 EOA 주소)
        String walletAddress = "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266";
        String aiAgentAddress = "0x70997970C51812dc3A010C7d01b50e0d17dc79C8";

        // when
        String txHash = blockchainService.revokeSessionKey(
                walletAddress, aiAgentAddress, userCredentials);

        // then
        assertThat(txHash).isEqualTo("0xrevoke-tx-hash");
        verify(web3j).ethGasPrice(); // gas price 조회 호출 검증

        // 전송된 raw transaction hex에서 gas price 디코딩하여 검증 (buffer 포함)
        String capturedHex = rawTxCaptor.getValue();
        assertThat(capturedHex).isNotBlank();
        SignedRawTransaction decoded = (SignedRawTransaction) TransactionDecoder.decode(capturedHex);
        assertThat(decoded.getGasPrice()).isEqualTo(expectedGasPrice);
    }

    @Test
    @DisplayName("ethGasPrice() 조회 실패 시 fallback 6 Gwei로 트랜잭션 생성")
    void testGasPriceFallbackOnFailure() throws Exception {
        // given - ethGasPrice()가 RuntimeException 발생
        prepareEthGasPriceMockFailure();
        prepareNonceMock(BigInteger.ONE, BigInteger.ONE);

        ArgumentCaptor<String> rawTxCaptor = ArgumentCaptor.forClass(String.class);
        prepareSendRawTransactionMock(rawTxCaptor, "0xfallback-tx");

        Credentials userCredentials = Credentials.create(TEST_PRIVATE_KEY);
        String walletAddress = "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266";
        String aiAgentAddress = "0x70997970C51812dc3A010C7d01b50e0d17dc79C8";

        // when
        String txHash = blockchainService.revokeSessionKey(
                walletAddress, aiAgentAddress, userCredentials);

        // then - fallback(6 Gwei) 그대로 사용 검증
        BigInteger fallbackGasPrice = BigInteger.valueOf(6_000_000_000L);
        String capturedHex = rawTxCaptor.getValue();
        assertThat(capturedHex).isNotBlank();
        SignedRawTransaction decoded = (SignedRawTransaction) TransactionDecoder.decode(capturedHex);
        assertThat(decoded.getGasPrice()).isEqualTo(fallbackGasPrice);
        assertThat(txHash).isEqualTo("0xfallback-tx");
    }

    @Test
    @DisplayName("사용자 EOA에 pending nonce gap이 있으면 latest nonce와 상향 gas price로 replacement tx를 생성한다")
    void testUserEoaNonceGapUsesLatestNonceAndBumpedGasPrice() throws Exception {
        BigInteger baseGasPrice = BigInteger.valueOf(10_000_000_000L);
        BigInteger bufferedGasPrice = applyBuffer(baseGasPrice);
        BigInteger expectedReplacementGasPrice = bufferedGasPrice.multiply(BigInteger.TWO);
        prepareEthGasPriceMock(baseGasPrice);
        prepareNonceMock(BigInteger.valueOf(7), BigInteger.valueOf(8));

        ArgumentCaptor<String> rawTxCaptor = ArgumentCaptor.forClass(String.class);
        prepareSendRawTransactionMock(rawTxCaptor, "0xreplacement-tx");

        Credentials userCredentials = Credentials.create(TEST_PRIVATE_KEY);
        String walletAddress = "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266";
        String aiAgentAddress = "0x70997970C51812dc3A010C7d01b50e0d17dc79C8";

        String txHash = blockchainService.revokeSessionKey(
                walletAddress, aiAgentAddress, userCredentials);

        assertThat(txHash).isEqualTo("0xreplacement-tx");
        String capturedHex = rawTxCaptor.getValue();
        SignedRawTransaction decoded = (SignedRawTransaction) TransactionDecoder.decode(capturedHex);
        assertThat(decoded.getNonce()).isEqualTo(BigInteger.valueOf(7));
        assertThat(decoded.getGasPrice()).isEqualTo(expectedReplacementGasPrice);
    }

    // ──────────────── Mock 헬퍼 ────────────────

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void prepareEthGasPriceMock(BigInteger gasPrice) throws Exception {
        EthGasPrice response = mock(EthGasPrice.class);
        when(response.getGasPrice()).thenReturn(gasPrice);

        Request request = mock(Request.class);
        when(request.send()).thenReturn(response);

        doReturn(request).when(web3j).ethGasPrice();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void prepareEthGasPriceMockFailure() throws Exception {
        Request request = mock(Request.class);
        when(request.send()).thenThrow(new RuntimeException("RPC connection refused"));

        doReturn(request).when(web3j).ethGasPrice();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void prepareNonceMock(BigInteger latestNonce, BigInteger pendingNonce) throws Exception {
        EthGetTransactionCount latestResponse = mock(EthGetTransactionCount.class);
        when(latestResponse.getTransactionCount()).thenReturn(latestNonce);

        Request latestRequest = mock(Request.class);
        when(latestRequest.send()).thenReturn(latestResponse);

        EthGetTransactionCount pendingResponse = mock(EthGetTransactionCount.class);
        when(pendingResponse.getTransactionCount()).thenReturn(pendingNonce);

        Request pendingRequest = mock(Request.class);
        when(pendingRequest.send()).thenReturn(pendingResponse);

        doReturn(latestRequest).when(web3j)
                .ethGetTransactionCount(anyString(), eq(DefaultBlockParameterName.LATEST));
        doReturn(pendingRequest).when(web3j)
                .ethGetTransactionCount(anyString(), eq(DefaultBlockParameterName.PENDING));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void prepareSendRawTransactionMock(ArgumentCaptor<String> captor, String txHash) throws Exception {
        EthSendTransaction response = mock(EthSendTransaction.class);
        when(response.hasError()).thenReturn(false);
        when(response.getTransactionHash()).thenReturn(txHash);

        Request request = mock(Request.class);
        when(request.send()).thenReturn(response);

        doReturn(request).when(web3j).ethSendRawTransaction(captor.capture());
    }
}
