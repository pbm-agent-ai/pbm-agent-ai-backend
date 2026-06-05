package com.pbm.payment.config;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.web3j.crypto.Credentials;
import okhttp3.OkHttpClient;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.http.HttpService;
import org.springframework.context.annotation.Primary;

import java.util.concurrent.TimeUnit;

/**
 * Web3j 블록체인 연동 설정 클래스.
 *
 * Sepolia 테스트넷에 연결하여 스마트 컨트랙트를 호출할 수 있도록
 * Web3j 인스턴스와 AI 에이전트 Credentials를 빈으로 등록한다.
 */
@Slf4j
@Getter
@Configuration
public class Web3Config {

    @Value("${blockchain.rpc-url}")
    private String rpcUrl;

    @Value("${blockchain.private-key}")
    private String privateKey;

    @Value("${blockchain.contract.account-factory}")
    private String accountFactoryAddress;

    @Value("${blockchain.contract.pbm-token}")
    private String pbmTokenAddress;

    @Value("${blockchain.contract.paymaster}")
    private String paymasterAddress;

    /**
     * Sepolia 테스트넷과 통신하는 Web3j 인스턴스 빈 등록.
     * RPC URL은 Alchemy/Infura 환경변수로 주입받는다.
     */
    @Bean
    public Web3j web3j() {
        log.info("Web3j 초기화 - RPC URL: {}", rpcUrl);
        // OkHttpClient에 타임아웃 설정 — 미설정 시 RPC 응답 지연으로 스레드가 무한 대기함
        OkHttpClient okHttpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)   // TCP 연결 타임아웃
                .readTimeout(30, TimeUnit.SECONDS)      // 응답 읽기 타임아웃
                .writeTimeout(30, TimeUnit.SECONDS)     // 요청 전송 타임아웃
                .build();
        Web3j web3j = Web3j.build(new HttpService(rpcUrl, okHttpClient));

        try {
            String clientVersion = web3j.web3ClientVersion().send().getWeb3ClientVersion();
            log.info("블록체인 연결 성공 - 클라이언트: {}", clientVersion);
        } catch (Exception e) {
            log.warn("블록체인 연결 확인 실패 (서비스는 계속 시작됨): {}", e.getMessage());
        }

        return web3j;
    }

    /**
     * 마스터 개인키로 Credentials 빈 등록.
     * AccountFactory.createAccount() 및 addSessionKey() 호출 시 사용한다.
     * 개인키는 반드시 환경변수로 주입받는다.
     */
    @Bean(name = "masterCredentials")
    @Primary
    public Credentials masterCredentials() {
        Credentials credentials = Credentials.create(privateKey);
        log.info("마스터 지갑 주소(Owner): {}", credentials.getAddress());
        return credentials;
    }
}
