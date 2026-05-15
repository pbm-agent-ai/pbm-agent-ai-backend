package com.pbm.price.service;

import com.pbm.price.client.ExternalApiClient;
import com.pbm.price.dto.response.AliExpressShoppingItem;
import com.pbm.price.dto.response.AliexpressProductDetailResponse;
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
class AliExpressProductUrlServiceTest {

    @Mock
    private ExternalApiClient externalApiClient;

    @InjectMocks
    private AliExpressProductUrlService aliExpressProductUrlService;

    @Test
    @DisplayName("유효한 AliExpress URL만 productId로 추출해 detail API 성공 상품만 반환한다")
    void resolveProductsByUrls_returnsOnlyResolvedProducts() {
        when(externalApiClient.getAliExpressProductDetail("1005006782975346", "KRW", "KO", "KR"))
                .thenReturn(new AliexpressProductDetailResponse(
                        new AliExpressShoppingItem(
                                "로지텍 MX 마스터 무선 블루투스 마우스, 하이 엔드 크로스 스크린 노트북, 3S",
                                "599.62",
                                "136200",
                                "289787",
                                "Stone's Store",
                                "https://ko.aliexpress.com/item/1005006782975346.html",
                                "1005006782975346",
                                null,
                                null,
                                null,
                                null,
                                null,
                                null
                        )
                ));
        when(externalApiClient.getAliExpressProductDetail("1005010633549414", "KRW", "KO", "KR"))
                .thenReturn(new AliexpressProductDetailResponse(null));

        List<SearchResponse> results = aliExpressProductUrlService.resolveProductsByUrls(
                List.of(
                        "https://ko.aliexpress.com/item/1005006782975346.html",
                        "https://ko.aliexpress.com/item/1005010633549414.html",
                        "invalid-url"
                ),
                "KRW",
                "KO",
                "KR"
        );

        assertThat(results).hasSize(1);
        assertThat(results.get(0).productId()).isEqualTo("1005006782975346");
        assertThat(results.get(0).lprice()).isEqualTo("136200");
    }
}
