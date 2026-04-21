package com.pbm.price.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pbm.price.dto.response.SearchResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * 네이버 쇼핑 API 연동 서비스
 * 키워드 기반 상품 검색 기능 제공
 */
@Slf4j
@Service
public class NaverShoppingService {

    private static final String NAVER_SHOP_API_URL = "https://openapi.naver.com/v1/search/shop.json";

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    /** 네이버 API 클라이언트 ID (환경변수에서 주입) */
    @Value("${naver.client-id}")
    private String clientId;

    /** 네이버 API 클라이언트 시크릿 (환경변수에서 주입) */
    @Value("${naver.client-secret}")
    private String clientSecret;

    public NaverShoppingService(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 키워드로 네이버 쇼핑 상품 검색
     *
     * @param keyword 검색 키워드
     * @param display 검색 결과 개수 (기본 10, 최대 100)
     * @return 검색된 상품 목록
     */
    public List<SearchResponse> searchProducts(String keyword, int display) {
        // 요청 헤더에 네이버 API 인증 정보 설정
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Naver-Client-Id", clientId);
        headers.set("X-Naver-Client-Secret", clientSecret);

        // 검색 URL 구성
        String url = UriComponentsBuilder.fromHttpUrl(NAVER_SHOP_API_URL)
                .queryParam("query", keyword)
                .queryParam("display", display)
                .queryParam("sort", "sim") // 정확도순 정렬
                .toUriString();

        log.info("네이버 쇼핑 API 호출 - 키워드: {}, 개수: {}", keyword, display);

        try {
            // API 호출
            ResponseEntity<String> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    String.class
            );

            // JSON 응답 파싱
            return parseResponse(response.getBody());
        } catch (Exception e) {
            log.error("네이버 쇼핑 API 호출 실패 - 키워드: {}, 에러: {}", keyword, e.getMessage());
            throw new RuntimeException("네이버 쇼핑 API 호출 중 오류가 발생했습니다.", e);
        }
    }

    /**
     * 네이버 API JSON 응답을 SearchResponse 목록으로 변환
     *
     * @param responseBody API 응답 JSON 문자열
     * @return 파싱된 상품 목록
     */
    private List<SearchResponse> parseResponse(String responseBody) {
        List<SearchResponse> results = new ArrayList<>();

        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode items = root.path("items");

            for (JsonNode item : items) {
                SearchResponse product = new SearchResponse(
                        item.path("title").asText(),
                        item.path("lprice").asText(),
                        item.path("hprice").asText(),
                        item.path("mallName").asText(),
                        item.path("link").asText()
                );
                results.add(product);
            }

            log.info("검색 결과 파싱 완료 - {}건", results.size());
        } catch (Exception e) {
            log.error("응답 파싱 실패: {}", e.getMessage());
            throw new RuntimeException("검색 결과 파싱 중 오류가 발생했습니다.", e);
        }

        return results;
    }
}
