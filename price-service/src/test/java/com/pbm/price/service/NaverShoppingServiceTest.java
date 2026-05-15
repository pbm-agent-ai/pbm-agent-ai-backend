package com.pbm.price.service;

import com.pbm.price.client.ExternalApiClient;
import com.pbm.price.dto.response.NaverShoppingItem;
import com.pbm.price.dto.response.SearchResponse;
import com.pbm.price.exception.ExternalApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * NaverShoppingService 단위 테스트
 * ExternalApiClient를 Mock하여 서비스 계층 동작 검증
 */
@ExtendWith(MockitoExtension.class)
class NaverShoppingServiceTest {

    @Mock
    private ExternalApiClient externalApiClient;

    @Mock
    private ProductPersistenceService productPersistenceService;

    private NaverShoppingService naverShoppingService;

    @BeforeEach
    void setUp() {
        naverShoppingService = new NaverShoppingService(externalApiClient, productPersistenceService);
    }

    @Test
    @DisplayName("상품 검색 - ExternalApiClient를 통해 검색 결과 반환")
    void searchProducts_returnsResultsFromClient() {
        // given
        List<NaverShoppingItem> expectedResults = List.of(
                new NaverShoppingItem("에어팟 프로", "250000", "350000", "애플스토어", "https://example.com/1", "1001", "https://img.example.com/1.jpg", "애플", "Apple", "디지털/가전", "이어폰", "무선이어폰", ""),
                new NaverShoppingItem("갤럭시 버즈", "120000", "180000", "삼성스토어", "https://example.com/2", "1002", "https://img.example.com/2.jpg", "삼성", "Samsung", "디지털/가전", "이어폰", "무선이어폰", "")
        );
        when(externalApiClient.searchNaverProductItems("이어폰", 10, 1)).thenReturn(expectedResults);

        // when
        List<SearchResponse> results = naverShoppingService.searchProducts("이어폰", 10);

        // then
        assertThat(results).hasSize(2);
        assertThat(results.get(0).title()).isEqualTo("에어팟 프로");
        assertThat(results.get(1).title()).isEqualTo("갤럭시 버즈");
        verify(externalApiClient, times(1)).searchNaverProductItems("이어폰", 10, 1);
        verify(productPersistenceService).saveNaverSearchResults("이어폰", expectedResults);
    }

    @Test
    @DisplayName("상품 검색 - 빈 키워드도 클라이언트에 전달됨")
    void searchProducts_passesKeywordToClient() {
        // given
        when(externalApiClient.searchNaverProductItems("", 5, 1)).thenReturn(List.of());

        // when
        List<SearchResponse> results = naverShoppingService.searchProducts("", 5);

        // then
        assertThat(results).isEmpty();
        verify(externalApiClient).searchNaverProductItems("", 5, 1);
        verify(productPersistenceService).saveNaverSearchResults("", List.of());
    }

    @Test
    @DisplayName("상품 검색 - 클라이언트 예외 발생 시 그대로 전파 (ExternalApiException)")
    void searchProducts_propagatesClientException() {
        // given - Circuit Breaker OPEN 또는 재시도 실패 시 ExternalApiException이 발생함
        when(externalApiClient.searchNaverProductItems("에러키워드", 10, 1))
                .thenThrow(new ExternalApiException("external-api-service 네이버 쇼핑 API 호출 불가 (Circuit Breaker OPEN 또는 오류)"));

        // when & then - ExternalApiException이 서비스 계층으로 전파됨
        assertThatThrownBy(() -> naverShoppingService.searchProducts("에러키워드", 10))
                .isInstanceOf(ExternalApiException.class)
                .hasMessageContaining("external-api-service");

        verify(productPersistenceService, never()).saveNaverSearchResults(anyString(), anyList());
    }
}
