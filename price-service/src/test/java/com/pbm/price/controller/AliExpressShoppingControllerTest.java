package com.pbm.price.controller;

import com.pbm.price.dto.response.SearchResponse;
import com.pbm.price.exception.ExternalApiException;
import com.pbm.price.service.AliExpressShoppingService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AliExpressShoppingController 웹 레이어 테스트
 * MockMvc를 사용하여 HTTP 요청/응답 계약을 검증한다.
 *
 * 검증 대상:
 * - 정상 응답: 200 OK + ApiResponse JSON 형식
 * - 외부 API 장애: 503 Service Unavailable + ApiResponse 에러 JSON
 *   (ExternalApiException이 GlobalExceptionHandler를 통해 503으로 매핑됨)
 */
@WebMvcTest(AliExpressShoppingController.class)
class AliExpressShoppingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    /** AliExpressShoppingService를 Mock으로 대체하여 비즈니스 로직을 격리한다 */
    @MockBean
    private AliExpressShoppingService aliExpressShoppingService;

    @Test
    @DisplayName("GET /api/v1/aliexpress/search: 정상 검색 시 200 OK와 ApiResponse JSON 반환")
    void searchProducts_success_returns200WithApiResponse() throws Exception {
        // given - 서비스가 정상 검색 결과를 반환하는 상황
        List<SearchResponse> mockResults = List.of(
                new SearchResponse("무선 이어폰", "15000", "25000", "AliExpress Store", "https://aliexpress.com/item/1")
        );
        when(aliExpressShoppingService.searchProducts(
                eq("이어폰"), eq(1), eq(10), eq("SALE_PRICE_ASC"),
                eq("KRW"), eq("KO"), eq("KR"), eq(null)
        )).thenReturn(mockResults);

        // when & then - HTTP 상태코드와 ApiResponse JSON 형식 검증
        mockMvc.perform(get("/api/v1/aliexpress/search")
                        .param("keyword", "이어폰")
                        .param("pageNo", "1")
                        .param("pageSize", "10")
                        .param("sort", "SALE_PRICE_ASC"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("AliExpress 검색 성공"))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].title").value("무선 이어폰"));
    }

    @Test
    @DisplayName("GET /api/v1/aliexpress/search: ExternalApiException 발생 시 503과 ApiResponse 에러 JSON 반환")
    void searchProducts_externalApiException_returns503WithErrorJson() throws Exception {
        // given - 서비스가 외부 API 장애로 ExternalApiException을 던지는 상황
        when(aliExpressShoppingService.searchProducts(
                anyString(), anyInt(), anyInt(), any(), any(), any(), any(), any()))
                .thenThrow(new ExternalApiException(
                        "external-api-service AliExpress API 호출 불가 (Circuit Breaker OPEN 또는 오류)"));

        // when & then - 503 상태코드와 ApiResponse 에러 JSON 형식 검증
        mockMvc.perform(get("/api/v1/aliexpress/search")
                        .param("keyword", "에러키워드")
                        .param("pageNo", "1")
                        .param("pageSize", "10"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.message").isNotEmpty());
    }
}
