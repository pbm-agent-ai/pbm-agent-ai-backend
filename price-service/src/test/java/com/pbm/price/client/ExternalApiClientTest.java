package com.pbm.price.client;

import com.pbm.price.dto.response.NaverSearchResponse;
import com.pbm.price.dto.response.NaverShoppingItem;
import com.pbm.price.dto.response.SearchResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClient.RequestHeadersSpec;
import org.springframework.web.reactive.function.client.WebClient.RequestHeadersUriSpec;
import org.springframework.web.reactive.function.client.WebClient.ResponseSpec;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * ExternalApiClient 단위 테스트
 * WebClient를 Mock하여 HTTP 호출 없이 동작 검증
 * external-api-service의 래퍼 응답(NaverSearchResponse)에서 items를 추출하여
 * SearchResponse 목록으로 매핑하는 로직을 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class ExternalApiClientTest {

    @Mock
    private WebClient webClient;

    @Mock
    private RequestHeadersUriSpec requestHeadersUriSpec;

    @Mock
    private RequestHeadersSpec requestHeadersSpec;

    @Mock
    private ResponseSpec responseSpec;

    private ExternalApiClient externalApiClient;

    @BeforeEach
    void setUp() {
        externalApiClient = new ExternalApiClient(webClient);
    }

    @Test
    @DisplayName("네이버 쇼핑 검색 - 래퍼 응답에서 items를 추출하여 SearchResponse 목록 반환")
    void searchNaverProducts_returnsResults_fromWrappedResponse() {
        // given - external-api-service의 래퍼 응답 구조
        NaverShoppingItem item1 = new NaverShoppingItem(
                "에어팟 프로", "250000", "350000", "애플스토어",
                "https://example.com/1", "1001", "https://img.example.com/1.jpg",
                "애플", "Apple", "디지털/가전", "이어폰", "무선이어폰", ""
        );
        NaverShoppingItem item2 = new NaverShoppingItem(
                "갤럭시 버즈", "120000", "180000", "삼성스토어",
                "https://example.com/2", "1002", "https://img.example.com/2.jpg",
                "삼성", "Samsung", "디지털/가전", "이어폰", "무선이어폰", ""
        );
        NaverSearchResponse wrappedResponse = new NaverSearchResponse(2, 1, 10, List.of(item1, item2));

        // WebClient Mock 체인 설정
        when(webClient.get()).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.uri(any(java.util.function.Function.class))).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(any(ParameterizedTypeReference.class))).thenReturn(Mono.just(wrappedResponse));

        // when
        List<SearchResponse> results = externalApiClient.searchNaverProducts("이어폰", 10);

        // then - 래퍼 응답에서 items가 SearchResponse로 매핑되었는지 확인
        assertThat(results).hasSize(2);
        assertThat(results.get(0).title()).isEqualTo("에어팟 프로");
        assertThat(results.get(0).lprice()).isEqualTo("250000");
        assertThat(results.get(0).hprice()).isEqualTo("350000");
        assertThat(results.get(0).mallName()).isEqualTo("애플스토어");
        assertThat(results.get(0).link()).isEqualTo("https://example.com/1");
        assertThat(results.get(1).title()).isEqualTo("갤럭시 버즈");
        verify(webClient, times(1)).get();
    }

    @Test
    @DisplayName("네이버 쇼핑 검색 - 빈 items 응답 시 빈 목록 반환")
    void searchNaverProducts_returnsEmptyList_whenItemsEmpty() {
        // given - 빈 items를 가진 래퍼 응답
        NaverSearchResponse emptyResponse = new NaverSearchResponse(0, 1, 10, List.of());

        when(webClient.get()).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.uri(any(java.util.function.Function.class))).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(any(ParameterizedTypeReference.class))).thenReturn(Mono.just(emptyResponse));

        // when
        List<SearchResponse> results = externalApiClient.searchNaverProducts("없는상품", 10);

        // then
        assertThat(results).isEmpty();
    }

    @Test
    @DisplayName("네이버 쇼핑 검색 - null 응답 시 빈 목록 반환")
    void searchNaverProducts_returnsEmptyList_whenResponseNull() {
        // given - null 응답
        when(webClient.get()).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.uri(any(java.util.function.Function.class))).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(any(ParameterizedTypeReference.class))).thenReturn(Mono.justOrEmpty(null));

        // when
        List<SearchResponse> results = externalApiClient.searchNaverProducts("이어폰", 10);

        // then
        assertThat(results).isEmpty();
    }

    @Test
    @DisplayName("네이버 쇼핑 검색 - WebClient 예외 발생 시 RuntimeException 래핑")
    void searchNaverProducts_throwsRuntimeException_onWebClientError() {
        // given
        when(webClient.get()).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.uri(any(java.util.function.Function.class))).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(any(ParameterizedTypeReference.class)))
                .thenReturn(Mono.error(new RuntimeException("연결 실패")));

        // when & then
        assertThatThrownBy(() -> externalApiClient.searchNaverProducts("이어폰", 10))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("external-api-service 네이버 쇼핑 API 호출 중 오류가 발생했습니다");
    }

    @Test
    @DisplayName("네이버 쇼핑 검색 - NaverShoppingItem의 추가 필드는 매핑에서 제외됨")
    void searchNaverProducts_mapsOnlySearchResponseFields() {
        // given - 추가 필드(productId, image 등)가 있는 NaverShoppingItem
        NaverShoppingItem item = new NaverShoppingItem(
                "상품명", "10000", "20000", "쇼핑몰",
                "https://example.com/p1", "P001", "https://img.example.com/p1.jpg",
                "제조사", "브랜드", "대분류", "중분류", "소분류", "세분류"
        );
        NaverSearchResponse response = new NaverSearchResponse(1, 1, 10, List.of(item));

        when(webClient.get()).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.uri(any(java.util.function.Function.class))).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(any(ParameterizedTypeReference.class))).thenReturn(Mono.just(response));

        // when
        List<SearchResponse> results = externalApiClient.searchNaverProducts("상품명", 10);

        // then - SearchResponse에는 title, lprice, hprice, mallName, link만 매핑
        assertThat(results).hasSize(1);
        SearchResponse result = results.get(0);
        assertThat(result.title()).isEqualTo("상품명");
        assertThat(result.lprice()).isEqualTo("10000");
        assertThat(result.hprice()).isEqualTo("20000");
        assertThat(result.mallName()).isEqualTo("쇼핑몰");
        assertThat(result.link()).isEqualTo("https://example.com/p1");
        // productId, image, maker, brand, category 필드는 SearchResponse에 없으므로 매핑되지 않음
    }
}