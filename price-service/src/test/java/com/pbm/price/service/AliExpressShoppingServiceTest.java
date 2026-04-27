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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * AliExpressShoppingService 단위 테스트
 * ExternalApiClient를 Mock하여 서비스 계층 동작 검증
 * - 서비스는 클라이언트에 위임하는 얇은 레이어이므로, 파라미터 전달과 결과 반환을 확인한다.
 */
@ExtendWith(MockitoExtension.class)
class AliExpressShoppingServiceTest {

    @Mock
    private ExternalApiClient externalApiClient;

    private AliExpressShoppingService aliExpressShoppingService;

    @BeforeEach
    void setUp() {
        aliExpressShoppingService = new AliExpressShoppingService(externalApiClient);
    }

    @Test
    @DisplayName("AliExpress 상품 검색 - ExternalApiClient를 통해 검색 결과 반환")
    void searchProducts_returnsResultsFromClient() {
        // given - 클라이언트가 반환할 Mock 결과
        List<SearchResponse> expectedResults = List.of(
                new SearchResponse("무선 이어폰", "15000", "25000", "AliExpress Store", "https://aliexpress.com/item/1"),
                new SearchResponse("블루투스 스피커", "8000", "12000", "Tech Shop", "https://aliexpress.com/item/2")
        );
        when(externalApiClient.searchAliExpressProducts("이어폰", 1, 10, null, "KRW", "KO", "KR", null))
                .thenReturn(expectedResults);

        // when
        List<SearchResponse> results = aliExpressShoppingService.searchProducts(
                "이어폰", 1, 10, null, "KRW", "KO", "KR", null
        );

        // then
        assertThat(results).hasSize(2);
        assertThat(results.get(0).title()).isEqualTo("무선 이어폰");
        assertThat(results.get(1).title()).isEqualTo("블루투스 스피커");
        verify(externalApiClient, times(1)).searchAliExpressProducts("이어폰", 1, 10, null, "KRW", "KO", "KR", null);
    }

    @Test
    @DisplayName("AliExpress 상품 검색 - 정렬 및 트래킹 ID 포함 시 클라이언트에 전달됨")
    void searchProducts_passesOptionalParamsToClient() {
        // given
        when(externalApiClient.searchAliExpressProducts("이어폰", 2, 20, "SALE_PRICE_ASC", "USD", "EN", "US", "track123"))
                .thenReturn(List.of());

        // when
        List<SearchResponse> results = aliExpressShoppingService.searchProducts(
                "이어폰", 2, 20, "SALE_PRICE_ASC", "USD", "EN", "US", "track123"
        );

        // then
        assertThat(results).isEmpty();
        verify(externalApiClient).searchAliExpressProducts("이어폰", 2, 20, "SALE_PRICE_ASC", "USD", "EN", "US", "track123");
    }

    @Test
    @DisplayName("AliExpress 상품 검색 - 클라이언트 예외 발생 시 그대로 전파")
    void searchProducts_propagatesClientException() {
        // given
        when(externalApiClient.searchAliExpressProducts("에러키워드", 1, 10, null, "KRW", "KO", "KR", null))
                .thenThrow(new RuntimeException("external-api-service AliExpress API 호출 중 오류가 발생했습니다."));

        // when & then
        assertThatThrownBy(() -> aliExpressShoppingService.searchProducts(
                "에러키워드", 1, 10, null, "KRW", "KO", "KR", null
        ))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("external-api-service");
    }
}