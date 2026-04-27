package com.pbm.price.client;

import com.pbm.price.dto.response.NaverSearchResponse;
import com.pbm.price.dto.response.SearchResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

/**
 * external-api-service 호출 클라이언트
 * 네이버 쇼핑 API 등 외부 API 요청을 external-api-service로 직접 프록시한다.
 * 게이트웨이를 거치지 않고 external-api-service에 직접 호출한다.
 */
@Slf4j
@Component
public class ExternalApiClient {

    private final WebClient externalApiWebClient;

    public ExternalApiClient(WebClient externalApiWebClient) {
        this.externalApiWebClient = externalApiWebClient;
    }

    /**
     * external-api-service를 통해 네이버 쇼핑 상품 검색
     * external-api-service의 래퍼 응답(NaverSearchResponse)에서 items를 추출하여 반환한다.
     *
     * @param keyword 검색 키워드
     * @param display 검색 결과 개수
     * @return 검색된 상품 목록
     */
    public List<SearchResponse> searchNaverProducts(String keyword, int display) {
        log.info("external-api-service 네이버 쇼핑 검색 요청 - 키워드: {}, 개수: {}", keyword, display);

        try {
            // external-api-service의 네이버 쇼핑 엔드포인트 호출
            // 경로: GET /api/v1/naver/search?keyword={keyword}&display={display}
            // 응답: NaverSearchResponse 래퍼 (total, start, display, items)
            NaverSearchResponse response = externalApiWebClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/api/v1/naver/search")
                            .queryParam("keyword", keyword)
                            .queryParam("display", display)
                            .build())
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<NaverSearchResponse>() {})
                    .block();

            if (response == null || response.items() == null) {
                log.warn("external-api-service 응답이 null이거나 items가 없음");
                return List.of();
            }

            // NaverShoppingItem → SearchResponse 로 매핑
            List<SearchResponse> results = response.items().stream()
                    .map(item -> new SearchResponse(
                            item.title(),
                            item.lprice(),
                            item.hprice(),
                            item.mallName(),
                            item.link()
                    ))
                    .toList();

            log.info("external-api-service 응답 수신 완료 - {}건 (전체: {})", results.size(), response.total());
            return results;

        } catch (Exception e) {
            log.error("external-api-service 호출 실패 - 키워드: {}, 에러: {}", keyword, e.getMessage());
            throw new RuntimeException("external-api-service 네이버 쇼핑 API 호출 중 오류가 발생했습니다.", e);
        }
    }
}