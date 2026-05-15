package com.pbm.price.service;

import com.pbm.price.client.ExternalApiClient;
import com.pbm.price.dto.response.AliExpressShoppingItem;
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
 * AliExpressShoppingService 단위 테스트
 * ExternalApiClient를 Mock하여 서비스 계층 동작 검증
 * - 서비스는 클라이언트에 위임하는 얇은 레이어이므로, 파라미터 전달과 결과 반환을 확인한다.
 */
@ExtendWith(MockitoExtension.class)
class AliExpressShoppingServiceTest {

    @Mock
    private ExternalApiClient externalApiClient;

    @Mock
    private ProductPersistenceService productPersistenceService;

    private AliExpressShoppingService aliExpressShoppingService;

    @BeforeEach
    void setUp() {
        aliExpressShoppingService = new AliExpressShoppingService(externalApiClient, productPersistenceService);
    }

    @Test
    @DisplayName("AliExpress 상품 검색 - ExternalApiClient를 통해 검색 결과 반환")
    void searchProducts_returnsResultsFromClient() {
        // given - 클라이언트가 반환할 Mock 결과
        List<AliExpressShoppingItem> expectedResults = List.of(
                new AliExpressShoppingItem("무선 이어폰", "9.99", "15000", "25000", "AliExpress Store", "https://aliexpress.com/item/1", "1001", "https://img.example.com/1.jpg", "95", "200001", "이어폰", "300001", "무선이어폰"),
                new AliExpressShoppingItem("블루투스 스피커", "8.00", "8000", "12000", "Tech Shop", "https://aliexpress.com/item/2", "1002", "https://img.example.com/2.jpg", "88", "200002", "스피커", "300002", "블루투스스피커")
        );
        when(externalApiClient.searchAliExpressProductItems("이어폰", 1, 10, null, "KRW", "KO", "KR", null, null))
                .thenReturn(expectedResults);

        // when
        List<SearchResponse> results = aliExpressShoppingService.searchProducts(
                "이어폰", 1, 10, null, "KRW", "KO", "KR", null, null
        );

        // then
        assertThat(results).hasSize(2);
        assertThat(results.get(0).title()).isEqualTo("무선 이어폰");
        assertThat(results.get(1).title()).isEqualTo("블루투스 스피커");
        verify(externalApiClient, times(1)).searchAliExpressProductItems("이어폰", 1, 10, null, "KRW", "KO", "KR", null, null);
        verify(productPersistenceService).saveAliExpressSearchResults("이어폰", "KRW", expectedResults);
    }

    @Test
    @DisplayName("AliExpress 상품 검색 - 정렬 및 트래킹 ID 포함 시 클라이언트에 전달됨")
    void searchProducts_passesOptionalParamsToClient() {
        // given
        when(externalApiClient.searchAliExpressProductItems("이어폰", 2, 20, "SALE_PRICE_ASC", "USD", "EN", "US", "100,200", "track123"))
                .thenReturn(List.of());

        // when
        List<SearchResponse> results = aliExpressShoppingService.searchProducts(
                "이어폰", 2, 20, "SALE_PRICE_ASC", "USD", "EN", "US", "100,200", "track123"
        );

        // then
        assertThat(results).isEmpty();
        verify(externalApiClient).searchAliExpressProductItems("이어폰", 2, 20, "SALE_PRICE_ASC", "USD", "EN", "US", "100,200", "track123");
        verify(productPersistenceService).saveAliExpressSearchResults("이어폰", "USD", List.of());
    }

    @Test
    @DisplayName("AliExpress 상품 검색 - 클라이언트 예외 발생 시 그대로 전파 (ExternalApiException)")
    void searchProducts_propagatesClientException() {
        // given - Circuit Breaker OPEN 또는 재시도 실패 시 ExternalApiException이 발생함
        when(externalApiClient.searchAliExpressProductItems("에러키워드", 1, 10, null, "KRW", "KO", "KR", null, null))
                .thenThrow(new ExternalApiException("external-api-service AliExpress API 호출 불가 (Circuit Breaker OPEN 또는 오류)"));

        // when & then - ExternalApiException이 서비스 계층으로 전파됨
        assertThatThrownBy(() -> aliExpressShoppingService.searchProducts(
                "에러키워드", 1, 10, null, "KRW", "KO", "KR", null, null
        ))
                .isInstanceOf(ExternalApiException.class)
                .hasMessageContaining("external-api-service");

        verify(productPersistenceService, never()).saveAliExpressSearchResults(anyString(), anyString(), anyList());
    }
}
