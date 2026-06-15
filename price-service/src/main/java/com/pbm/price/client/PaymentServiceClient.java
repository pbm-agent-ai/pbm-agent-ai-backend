package com.pbm.price.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.math.BigDecimal;

/**
 * payment-service 내부 호출 클라이언트.
 * <p>
 * 역할: 세션키 한도 검증 시 사용자의 지갑 한도를 payment-service에서 조회한다.
 *       즉시 충족 시 동기적으로 세션키를 등록한다.
 *       서비스 간 직접 호출(게이트웨이 우회)로 X-User-Id 헤더만 전달한다.
 */
@Slf4j
@Component
public class PaymentServiceClient {

    private final WebClient webClient;

    public PaymentServiceClient(@Value("${payment-service.url:http://localhost:8084}") String paymentServiceUrl) {
        this.webClient = WebClient.builder()
                .baseUrl(paymentServiceUrl)
                .build();
    }

    /**
     * 사용자의 PBM 지갑 한도를 조회한다.
     * 지갑이 없거나 호출 실패 시 null을 반환한다.
     *
     * @param userId 사용자 ID
     * @return 지갑 한도 (KRW), 조회 실패 시 null
     */
    public BigDecimal getWalletLimit(Long userId) {
        try {
            WalletLimitResponse response = webClient.get()
                    .uri("/api/v1/wallet")
                    .header("X-User-Id", userId.toString())
                    .retrieve()
                    .bodyToMono(WalletLimitResponse.class)
                    .block();

            if (response != null && response.data() != null) {
                return response.data().walletLimit();
            }
            return null;
        } catch (WebClientResponseException.NotFound e) {
            log.warn("지갑 미존재 - userId: {}", userId);
            return null;
        } catch (Exception e) {
            log.warn("payment-service 지갑 한도 조회 실패 - userId: {}, 원인: {}", userId, e.getMessage());
            return null;
        }
    }

    /**
     * 세션키를 동기적으로 등록한다.
     * <p>
     * 즉시 충족 시나리오에서 사용된다.
     * payment-service의 POST /api/v1/session-keys를 호출하여
     * 블록체인 트랜잭션 완료까지 동기적으로 대기한다.
     *
     * @param userId            사용자 ID
     * @param subscriptionId    모니터링 구독 ID
     * @param aiAgentAddress    AI 에이전트 주소
     * @param aiAgentPrivateKey AI 에이전트 개인키
     * @param limitKrw          세션키 한도 (KRW)
     * @param validSeconds      유효 기간 (초)
     * @param platform          플랫폼 (NAVER, ALIEXPRESS)
     * @return 세션키 등록 성공 시 true, 실패 시 false
     */
    public boolean registerSessionKey(
            Long userId,
            Long subscriptionId,
            String aiAgentAddress,
            String aiAgentPrivateKey,
            long limitKrw,
            long validSeconds,
            String platform
    ) {
        try {
            log.info("세션키 등록 요청 (동기) - userId: {}, subscriptionId: {}, aiAgent: {}",
                    userId, subscriptionId, aiAgentAddress);

            SessionKeyRegistrationRequest request = new SessionKeyRegistrationRequest(
                    subscriptionId,
                    aiAgentAddress,
                    aiAgentPrivateKey,
                    limitKrw,
                    validSeconds,
                    platform
            );

            SessionKeyRegistrationResponse response = webClient.post()
                    .uri("/api/v1/session-keys")
                    .header("X-User-Id", userId.toString())
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(SessionKeyRegistrationResponse.class)
                    .block();

            if (response != null && response.success()) {
                log.info("세션키 등록 성공 (동기) - userId: {}, subscriptionId: {}, txHash: {}",
                        userId, subscriptionId,
                        response.data() != null ? response.data().transactionHash() : "N/A");
                return true;
            }
            return false;
        } catch (Exception e) {
            log.error("세션키 등록 실패 (동기) - userId: {}, subscriptionId: {}, 원인: {}",
                    userId, subscriptionId, e.getMessage(), e);
            return false;
        }
    }

    // ─── 응답 파싱용 내부 record ───────────────────────────────────────
    private record WalletLimitResponse(boolean success, WalletData data) {}
    private record WalletData(BigDecimal walletLimit) {}

    private record SessionKeyRegistrationRequest(
            Long subscriptionId,
            String aiAgentAddress,
            String aiAgentPrivateKey,
            long limitKrw,
            long validSeconds,
            String platform
    ) {}

    private record SessionKeyRegistrationResponse(
            boolean success,
            SessionKeyRegistrationData data,
            String message
    ) {}

    private record SessionKeyRegistrationData(
            Long sessionKeyId,
            String transactionHash,
            String walletAddress,
            String aiAgentAddress
    ) {}
}
