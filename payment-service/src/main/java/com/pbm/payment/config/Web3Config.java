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

    @Value("${blockchain.fallback-rpc-url:https://ethereum-sepolia-rpc.publicnode.com}")
    private String fallbackRpcUrl;

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
        return buildWeb3jClient("primary", rpcUrl);
    }

    /**
     * Primary RPC에만 보이는 local pending tx를 진단하기 위한 보조 Web3j 클라이언트.
     */
    @Bean(name = "fallbackWeb3j")
    public Web3j fallbackWeb3j() {
        return buildWeb3jClient("fallback", fallbackRpcUrl);
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

    private Web3j buildWeb3jClient(String label, String targetRpcUrl) {
        log.info("Web3j 초기화 - {} RPC URL: {}", label, targetRpcUrl);

        OkHttpClient okHttpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
        Web3j web3j = Web3j.build(new HttpService(targetRpcUrl, okHttpClient));

        try {
            String clientVersion = web3j.web3ClientVersion().send().getWeb3ClientVersion();
            log.info("블록체인 연결 성공 - {} 클라이언트: {}", label, clientVersion);
        } catch (Exception e) {
            log.warn("{} RPC 연결 확인 실패 (서비스는 계속 시작됨): {}", label, e.getMessage());
        }

        return web3j;
    }
}
