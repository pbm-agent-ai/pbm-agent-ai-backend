package com.pbm.price.service;

import com.pbm.price.client.ExternalApiClient;
import com.pbm.price.dto.response.NaverShoppingItem;
import com.pbm.price.dto.response.SearchResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NaverProductUrlServiceTest {

    @Mock
    private ExternalApiClient externalApiClient;

    @InjectMocks
    private NaverProductUrlService naverProductUrlService;

    @Test
    @DisplayName("catalog URL의 productId와 일치하는 검색 결과만 후보로 반환한다")
    void resolveProductsByUrls_returnsMatchedProducts() {
        when(externalApiClient.searchNaverProductItems("로지텍 mx master 3s black", 100, 1))
                .thenReturn(List.of(
                        new NaverShoppingItem(
                                "로지텍 MX MASTER 3S bluetooth edition, 블랙",
                                "139000",
                                "",
                                "네이버",
                                "https://search.shopping.naver.com/catalog/57981069328",
                                "57981069328",
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null
                        ),
                        new NaverShoppingItem(
                                "다른 상품",
                                "10000",
                                "",
                                "네이버",
                                "https://search.shopping.naver.com/catalog/111",
                                "111",
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null
                        )
                ));

        List<SearchResponse> results = naverProductUrlService.resolveProductsByUrls(
                "로지텍 mx master 3s black",
                List.of("https://search.shopping.naver.com/catalog/57981069328")
        );

        assertThat(results).hasSize(1);
        assertThat(results.get(0).productId()).isEqualTo("57981069328");
        assertThat(results.get(0).productUrl()).isEqualTo("https://search.shopping.naver.com/catalog/57981069328");
    }

    @Test
    @DisplayName("catalog URL이면 productId를 우선 추출해 링크가 달라도 후보를 찾는다")
    void resolveProductsByUrls_matchesByExtractedProductIdFirst() {
        when(externalApiClient.searchNaverProductItems("로지텍 mx master 3s black", 100, 1))
                .thenReturn(List.of(
                        new NaverShoppingItem(
                                "로지텍 MX MASTER 3S bluetooth edition, 블랙",
                                "139000",
                                "",
                                "네이버",
                                "https://search.shopping.naver.com/catalog/57981069328?query=%EB%A1%9C%EC%A7%80%ED%85%8D",
                                "57981069328",
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null
                        )
                ));

        List<SearchResponse> results = naverProductUrlService.resolveProductsByUrls(
                "로지텍 mx master 3s black",
                List.of("https://search.shopping.naver.com/catalog/57981069328")
        );

        assertThat(results).hasSize(1);
        assertThat(results.get(0).productId()).isEqualTo("57981069328");
    }

    @Test
    @DisplayName("catalog productId를 추출할 수 없는 URL은 후보 복원 대상에서 제외한다")
    void resolveProductsByUrls_ignoresUrlsWithoutCatalogProductId() {
        when(externalApiClient.searchNaverProductItems("로지텍 mx master 3s black", 100, 1))
                .thenReturn(List.of(
                        new NaverShoppingItem(
                                "로지텍 MX MASTER 3S bluetooth edition, 블랙",
                                "139000",
                                "",
                                "네이버",
                                "https://search.shopping.naver.com/catalog/57981069328",
                                "57981069328",
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null
                        )
                ));

        List<SearchResponse> results = naverProductUrlService.resolveProductsByUrls(
                "로지텍 mx master 3s black",
                List.of("https://smartstore.naver.com/main/products/11034074434")
        );

        assertThat(results).isEmpty();
    }
}
