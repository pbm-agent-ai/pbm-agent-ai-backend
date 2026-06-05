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

    // ─── 응답 파싱용 내부 record ───────────────────────────────────────
    private record WalletLimitResponse(boolean success, WalletData data) {}
    private record WalletData(BigDecimal walletLimit) {}
}
