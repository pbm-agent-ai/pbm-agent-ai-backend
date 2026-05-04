package com.pbm.price.controller;

import com.pbm.price.dto.response.SearchResponse;
import com.pbm.price.exception.ExternalApiException;
import com.pbm.price.service.NaverShoppingService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * NaverShoppingController 웹 레이어 테스트
 * MockMvc를 사용하여 HTTP 요청/응답 계약을 검증한다.
 *
 * 검증 대상:
 * - 정상 응답: 200 OK + ApiResponse JSON 형식
 * - 외부 API 장애: 503 Service Unavailable + ApiResponse 에러 JSON
 *   (ExternalApiException이 GlobalExceptionHandler를 통해 503으로 매핑됨)
 */
@WebMvcTest(NaverShoppingController.class)
class NaverShoppingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    /** NaverShoppingService를 Mock으로 대체하여 비즈니스 로직을 격리한다 */
    @MockBean
    private NaverShoppingService naverShoppingService;

    @Test
    @DisplayName("GET /api/v1/naver/search: 정상 검색 시 200 OK와 ApiResponse JSON 반환")
    void searchProducts_success_returns200WithApiResponse() throws Exception {
        // given - 서비스가 정상 검색 결과를 반환하는 상황
        List<SearchResponse> mockResults = List.of(
                new SearchResponse("에어팟 프로", "250000", "350000", "애플스토어", "https://example.com/1"),
                new SearchResponse("갤럭시 버즈", "120000", "180000", "삼성스토어", "https://example.com/2")
        );
        when(naverShoppingService.searchProducts(eq("이어폰"), eq(10)))
                .thenReturn(mockResults);

        // when & then - HTTP 상태코드와 ApiResponse JSON 형식 검증
        mockMvc.perform(get("/api/v1/naver/search")
                        .param("keyword", "이어폰")
                        .param("display", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("검색 성공"))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].title").value("에어팟 프로"))
                .andExpect(jsonPath("$.data[1].title").value("갤럭시 버즈"));
    }

    @Test
    @DisplayName("GET /api/v1/naver/search: ExternalApiException 발생 시 503과 ApiResponse 에러 JSON 반환")
    void searchProducts_externalApiException_returns503WithErrorJson() throws Exception {
        // given - 서비스가 외부 API 장애로 ExternalApiException을 던지는 상황
        when(naverShoppingService.searchProducts(anyString(), anyInt()))
                .thenThrow(new ExternalApiException(
                        "external-api-service 네이버 쇼핑 API 호출 불가 (Circuit Breaker OPEN 또는 오류)"));

        // when & then - 503 상태코드와 ApiResponse 에러 JSON 형식 검증
        mockMvc.perform(get("/api/v1/naver/search")
                        .param("keyword", "에러키워드")
                        .param("display", "10"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.message").isNotEmpty());
    }
}
