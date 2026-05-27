package com.pbm.price.client;

import com.pbm.price.dto.response.KoreaEximbankExchangeRateItem;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Collections;
import java.util.List;

/**
 * 한국수출입은행 환율 API 클라이언트.
 *
 * 역할: 지정 날짜의 환율 정보를 조회하여 반환한다.
 *      주말/공휴일에는 빈 목록이 반환되며, 상위 서비스에서 날짜 fallback 처리를 담당한다.
 *
 * API: GET https://www.koreaexim.go.kr/site/program/financial/exchangeJSON
 *      ?authkey={key}&searchdate={YYYYMMDD}&data=AP01
 */
@Slf4j
@Component
public class ExchangeRateClient {

    private final WebClient exchangeRateWebClient;

    @Value("${korea-eximbank.api-key:}")
    private String apiKey;

    public ExchangeRateClient(WebClient exchangeRateWebClient) {
        this.exchangeRateWebClient = exchangeRateWebClient;
    }

    /**
     * 특정 날짜의 환율 목록을 조회한다.
     *
     * @param searchDate 조회 날짜 (YYYYMMDD 형식, 예: "20240101")
     * @return 환율 항목 목록. 주말/공휴일이거나 오류 시 빈 목록 반환
     */
    public List<KoreaEximbankExchangeRateItem> fetchExchangeRates(String searchDate) {
        log.debug("한국수출입은행 환율 조회 요청 - searchDate: {}", searchDate);

        try {
            List<KoreaEximbankExchangeRateItem> items = exchangeRateWebClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .queryParam("authkey", apiKey)
                            .queryParam("searchdate", searchDate)
                            .queryParam("data", "AP01")
                            .build())
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<List<KoreaEximbankExchangeRateItem>>() {})
                    .block();

            if (items == null) {
                log.warn("한국수출입은행 환율 API 응답이 null - searchDate: {}", searchDate);
                return Collections.emptyList();
            }

            log.debug("한국수출입은행 환율 조회 완료 - searchDate: {}, 항목 수: {}", searchDate, items.size());
            return items;

        } catch (Exception e) {
            log.error("한국수출입은행 환율 API 호출 실패 - searchDate: {}, error: {}", searchDate, e.getMessage());
            return Collections.emptyList();
        }
    }
}
