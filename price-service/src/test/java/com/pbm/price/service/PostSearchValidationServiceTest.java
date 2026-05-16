package com.pbm.price.service;

import com.pbm.price.dto.event.ParsedCommandSnapshot;
import com.pbm.price.dto.response.SearchResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PostSearchValidationService 단위 테스트.
 *
 * 검증 규칙:
 *   1. null/빈 results → NO_MATCH
 *   2. AUTO_PURCHASE + results.size() > 1 → PRODUCT_SELECTION_REQUIRED
 *   3. AUTO_PURCHASE + SHOES + snapshot.size 부재 + title에 사이즈 정보 없음
 *      → PRODUCT_SELECTION_REQUIRED
 *   4. 그 외 → PROCEED
 */
class PostSearchValidationServiceTest {

    private final PostSearchValidationService service = new PostSearchValidationService();

    // ── 공통 테스트 픽스처 ──────────────────────────────────────────────

    private static SearchResponse searchResult(String title) {
        return new SearchResponse(title, "10000", "20000", "몰", "https://example.com", "https://img.example.com/product-1.jpg", "KRW", "product-1");
    }

    private static ParsedCommandSnapshot snapshot(String productCategory, String size) {
        return new ParsedCommandSnapshot(
                productCategory,   // productCategory
                "테스트상품",       // productName
                null,              // brand
                null,              // line
                null,              // model
                null,              // color
                size,              // size
                "NAVER",           // platform
                200000,            // maxPrice
                10000,             // minPrice
                "KRW",             // currency
                null,              // productUrl — Phase 1 준비
                null               // searchKeyword — Phase 1 준비
        );
    }

    // ── 규칙 1: 빈/널 results → NO_MATCH ────────────────────────────────

    @Test
    @DisplayName("results가 null이면 NO_MATCH 반환")
    void validate_nullResults_returnsNoMatch() {
        PostSearchValidationResult result = service.validate(null, "PRICE_CHECK", null);
        assertThat(result.status()).isEqualTo(PostSearchValidationResult.Status.NO_MATCH);
    }

    @Test
    @DisplayName("results가 비어있으면 NO_MATCH 반환")
    void validate_emptyResults_returnsNoMatch() {
        PostSearchValidationResult result = service.validate(List.of(), "PRICE_CHECK", null);
        assertThat(result.status()).isEqualTo(PostSearchValidationResult.Status.NO_MATCH);
    }

    // ── 규칙 2: AUTO_PURCHASE + 다중 결과 → PRODUCT_SELECTION_REQUIRED ───

    @Test
    @DisplayName("AUTO_PURCHASE + 결과 2개 이상 → PRODUCT_SELECTION_REQUIRED")
    void validate_autoPurchaseMultipleResults_returnsSelectionRequired() {
        // given
        List<SearchResponse> results = List.of(
                searchResult("나이키 에어포스"),
                searchResult("나이키 에어맥스")
        );

        // when
        PostSearchValidationResult result = service.validate(
                results, "AUTO_PURCHASE", null
        );

        // then
        assertThat(result.status()).isEqualTo(
                PostSearchValidationResult.Status.PRODUCT_SELECTION_REQUIRED);
        assertThat(result.missingFields()).contains("searchResultsCount");
    }

    // ── 규칙 3: AUTO_PURCHASE + SHOES + 사이즈 부재 → PRODUCT_SELECTION_REQUIRED

    @Test
    @DisplayName("AUTO_PURCHASE + SHOES + snapshot.size 없음 + title에 사이즈 없음 → PRODUCT_SELECTION_REQUIRED")
    void validate_autoPurchaseShoesNoSizeInSnapshotAndTitle_returnsSelectionRequired() {
        // given - title에 220~300 범위 숫자도 없고 mm/cm도 없음
        List<SearchResponse> results = List.of(
                searchResult("나이키 에어포스 1 화이트")
        );
        ParsedCommandSnapshot snapshot = snapshot("SHOES", null);

        // when
        PostSearchValidationResult result = service.validate(
                results, "AUTO_PURCHASE", snapshot
        );

        // then
        assertThat(result.status()).isEqualTo(
                PostSearchValidationResult.Status.PRODUCT_SELECTION_REQUIRED);
        assertThat(result.missingFields()).contains("size");
    }

    @Test
    @DisplayName("AUTO_PURCHASE + SHOES + title에 사이즈 포함 → PROCEED")
    void validate_autoPurchaseShoesSizeInTitle_returnsProceed() {
        // given - title에 "260" (220-300 범위의 한국 신발 사이즈)
        List<SearchResponse> results = List.of(
                searchResult("나이키 에어포스 1 260 화이트")
        );
        ParsedCommandSnapshot snapshot = snapshot("SHOES", null);

        // when
        PostSearchValidationResult result = service.validate(
                results, "AUTO_PURCHASE", snapshot
        );

        // then
        assertThat(result.status()).isEqualTo(PostSearchValidationResult.Status.PROCEED);
    }

    @Test
    @DisplayName("AUTO_PURCHASE + SHOES + snapshot.size 있음 → PROCEED")
    void validate_autoPurchaseShoesSizeInSnapshot_returnsProceed() {
        // given - snapshot.size가 "270"으로 명시됨
        List<SearchResponse> results = List.of(
                searchResult("나이키 에어포스 1 화이트")
        );
        ParsedCommandSnapshot snapshot = snapshot("SHOES", "270");

        // when
        PostSearchValidationResult result = service.validate(
                results, "AUTO_PURCHASE", snapshot
        );

        // then
        assertThat(result.status()).isEqualTo(PostSearchValidationResult.Status.PROCEED);
    }

    // ── 규칙 4: 그 외 → PROCEED ─────────────────────────────────────────

    @Test
    @DisplayName("PRICE_CHECK + 결과 1개 → PROCEED (자동구매 의도 아님)")
    void validate_priceCheckSingleResult_returnsProceed() {
        // given - 결과가 1개지만 intent가 PRICE_CHECK이므로 검증 통과
        List<SearchResponse> results = List.of(searchResult("테스트 상품"));

        // when
        PostSearchValidationResult result = service.validate(results, "PRICE_CHECK", null);

        // then
        assertThat(result.status()).isEqualTo(PostSearchValidationResult.Status.PROCEED);
    }

    @Test
    @DisplayName("PRICE_CHECK + 결과 2개 이상 → PROCEED (자동구매 의도 아님)")
    void validate_priceCheckMultipleResults_returnsProceed() {
        // given - 자동구매가 아니므로 다중 결과여도 문제 없음
        List<SearchResponse> results = List.of(
                searchResult("상품1"),
                searchResult("상품2")
        );

        // when
        PostSearchValidationResult result = service.validate(results, "PRICE_CHECK", null);

        // then
        assertThat(result.status()).isEqualTo(PostSearchValidationResult.Status.PROCEED);
    }

    @Test
    @DisplayName("null intent + 결과 1개 → PROCEED")
    void validate_nullIntentSingleResult_returnsProceed() {
        List<SearchResponse> results = List.of(searchResult("테스트"));

        PostSearchValidationResult result = service.validate(results, null, null);

        assertThat(result.status()).isEqualTo(PostSearchValidationResult.Status.PROCEED);
    }

    @Test
    @DisplayName("PRICE_TRACK + SHOES + 사이즈 없어도 PROCEED")
    void validate_priceTrackShoesNoSize_returnsProceed() {
        // given - PRICE_TRACK(단순 추적)이므로 사이즈 없어도 통과
        List<SearchResponse> results = List.of(
                searchResult("나이키 에어포스 1 화이트")
        );
        ParsedCommandSnapshot snapshot = snapshot("SHOES", null);

        // when
        PostSearchValidationResult result = service.validate(results, "PRICE_TRACK", snapshot);

        // then
        assertThat(result.status()).isEqualTo(PostSearchValidationResult.Status.PROCEED);
    }
}
