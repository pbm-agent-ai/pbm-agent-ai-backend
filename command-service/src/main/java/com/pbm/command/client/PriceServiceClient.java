package com.pbm.command.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pbm.command.dto.response.BrowserSearchTaskResponse;
import com.pbm.command.dto.response.UrlMonitoringTaskResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * price-service 내부 HTTP 클라이언트.
 *
 * 역할: command-service가 price-service의 내부 전용 엔드포인트를 직접 호출한다.
 *       Eureka 서비스 디스커버리 + Spring Cloud LoadBalancer를 통해
 *       lb://price-service URL을 실제 인스턴스 IP/PORT로 해석한다.
 *       게이트웨이를 거치지 않으므로 JWT 인증 없이 X-User-Id 헤더만 전달한다.
 * 연관: BrowserDeviceService, InternalServiceConfig (loadBalancedRestTemplate)
 */
@Slf4j
@Component
public class PriceServiceClient {

    private static final String PRICE_SERVICE_BASE = "http://price-service";
    private static final String ACTIVE_TASKS_PATH = "/api/v1/url-monitoring/active-tasks";
    private static final String ACTIVE_BROWSER_SEARCH_TASKS_PATH = "/api/v1/browser-search/active-tasks";
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public PriceServiceClient(@Qualifier("loadBalancedRestTemplate") RestTemplate restTemplate,
                              ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 크롤링 체크 기한이 도래한 URL 모니터링 태스크 목록을 price-service에서 조회한다.
     *
     * price-service의 GET /api/v1/url-monitoring/active-tasks를 호출하며,
     * X-User-Id 헤더로 userId를 전달한다.
     * 오류 발생 시 빈 리스트를 반환하여 heartbeat 흐름을 유지한다.
     *
     * @param userId heartbeat 디바이스 소유자 ID
     * @return URL 모니터링 태스크 목록 (오류 시 빈 리스트)
     */
    public List<UrlMonitoringTaskResponse> getActiveUrlTasks(Long userId) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-User-Id", String.valueOf(userId));
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            ResponseEntity<Map> responseEntity = restTemplate.exchange(
                    PRICE_SERVICE_BASE + ACTIVE_TASKS_PATH,
                    HttpMethod.GET,
                    entity,
                    Map.class
            );

            // ApiResponse<List<UrlActiveTaskResponse>> 구조에서 data 필드를 꺼내 역직렬화
            Map<?, ?> body = responseEntity.getBody();
            if (body == null || !Boolean.TRUE.equals(body.get("success"))) {
                log.warn("price-service active-tasks 응답 실패 - userId: {}", userId);
                return List.of();
            }

            Object data = body.get("data");
            if (data == null) return List.of();

            // Jackson이 data를 List<Map>으로 파싱한 것을 UrlMonitoringTaskResponse로 변환
            List<Map<String, Object>> taskMaps = objectMapper.convertValue(
                    data, new TypeReference<>() {}
            );

            return taskMaps.stream()
                    .map(map -> new UrlMonitoringTaskResponse(
                            toLong(map.get("subscriptionId")),   // taskId = subscriptionId (통합 후 동일)
                            toLong(map.get("subscriptionId")),
                            (String) map.get("productUrl"),
                            toInteger(map.get("targetPrice")),
                            (String) map.get("currency"),
                            (String) map.get("condition"),
                            (String) map.get("intent"),
                            (String) map.get("commandId")
                    ))
                    .toList();

        } catch (Exception e) {
            // price-service 일시 다운이나 네트워크 오류 시 heartbeat는 정상 진행
            log.warn("price-service active-tasks 조회 실패 (heartbeat 계속 진행) - userId: {}, 원인: {}",
                    userId, e.getMessage());
            return List.of();
        }
    }

    /**
     * 크롤링 기한이 도래한 브라우저 검색 태스크 목록을 price-service에서 조회한다.
     *
     * @param userId heartbeat 디바이스 소유자 ID
     * @return 브라우저 검색 태스크 목록 (오류 시 빈 리스트)
     */
    public List<BrowserSearchTaskResponse> getActiveBrowserSearchTasks(Long userId) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-User-Id", String.valueOf(userId));
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            ResponseEntity<Map> responseEntity = restTemplate.exchange(
                    PRICE_SERVICE_BASE + ACTIVE_BROWSER_SEARCH_TASKS_PATH,
                    HttpMethod.GET,
                    entity,
                    Map.class
            );

            Map<?, ?> body = responseEntity.getBody();
            if (body == null || !Boolean.TRUE.equals(body.get("success"))) {
                log.warn("price-service browser-search active-tasks 응답 실패 - userId: {}", userId);
                return List.of();
            }

            Object data = body.get("data");
            if (data == null) return List.of();

            List<Map<String, Object>> taskMaps = objectMapper.convertValue(data, new TypeReference<>() {});
            return taskMaps.stream()
                    .map(map -> new BrowserSearchTaskResponse(
                            toLong(map.get("taskId")),
                            (String) map.get("commandId"),
                            (String) map.get("platform"),
                            (String) map.get("keyword"),
                            (String) map.get("searchUrl"),
                            toInteger(map.get("maxResults"))
                    ))
                    .toList();
        } catch (Exception e) {
            log.warn("price-service browser-search active-tasks 조회 실패 (heartbeat 계속 진행) - userId: {}, 원인: {}",
                    userId, e.getMessage());
            return List.of();
        }
    }

    private static Long toLong(Object obj) {
        if (obj == null) return null;
        if (obj instanceof Number n) return n.longValue();
        try { return Long.parseLong(obj.toString()); } catch (NumberFormatException e) { return null; }
    }

    private static Integer toInteger(Object obj) {
        if (obj == null) return null;
        if (obj instanceof Number n) return n.intValue();
        try { return Integer.parseInt(obj.toString()); } catch (NumberFormatException e) { return null; }
    }
}
