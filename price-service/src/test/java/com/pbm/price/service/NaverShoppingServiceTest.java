package com.pbm.price.service;

import com.pbm.price.client.ExternalApiClient;
import com.pbm.price.dto.response.SearchResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * NaverShoppingService 단위 테스트
 * ExternalApiClient를 Mock하여 서비스 계층 동작 검증
 */
@ExtendWith(MockitoExtension.class)
class NaverShoppingServiceTest {

    @Mock
    private ExternalApiClient externalApiClient;

    private NaverShoppingService naverShoppingService;

    @BeforeEach
    void setUp() {
        naverShoppingService = new NaverShoppingService(externalApiClient);
    }

    @Test
    @DisplayName("상품 검색 - ExternalApiClient를 통해 검색 결과 반환")
    void searchProducts_returnsResultsFromClient() {
        // given
        List<SearchResponse> expectedResults = List.of(
                new SearchResponse("에어팟 프로", "250000", "350000", "애플스토어", "https://example.com/1"),
                new SearchResponse("갤럭시 버즈", "120000", "180000", "삼성스토어", "https://example.com/2")
        );
        when(externalApiClient.searchNaverProducts("이어폰", 10)).thenReturn(expectedResults);

        // when
        List<SearchResponse> results = naverShoppingService.searchProducts("이어폰", 10);

        // then
        assertThat(results).hasSize(2);
        assertThat(results.get(0).title()).isEqualTo("에어팟 프로");
        assertThat(results.get(1).title()).isEqualTo("갤럭시 버즈");
        verify(externalApiClient, times(1)).searchNaverProducts("이어폰", 10);
    }

    @Test
    @DisplayName("상품 검색 - 빈 키워드도 클라이언트에 전달됨")
    void searchProducts_passesKeywordToClient() {
        // given
        when(externalApiClient.searchNaverProducts("", 5)).thenReturn(List.of());

        // when
        List<SearchResponse> results = naverShoppingService.searchProducts("", 5);

        // then
        assertThat(results).isEmpty();
        verify(externalApiClient).searchNaverProducts("", 5);
    }

    @Test
    @DisplayName("상품 검색 - 클라이언트 예외 발생 시 그대로 전파")
    void searchProducts_propagatesClientException() {
        // given
        when(externalApiClient.searchNaverProducts("에러키워드", 10))
                .thenThrow(new RuntimeException("external-api-service 네이버 쇼핑 API 호출 중 오류가 발생했습니다."));

        // when & then
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> naverShoppingService.searchProducts("에러키워드", 10))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("external-api-service");
    }
}