package com.pbm.price.controller;

import com.pbm.price.common.ApiResponse;
import com.pbm.price.dto.response.SearchResponse;
import com.pbm.price.service.AliExpressShoppingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * AliExpress 쇼핑 검색 API 컨트롤러
 * GET /api/prices/aliexpress/search?keyword={keyword}&page_no=1&page_size=10&sort=...
 *
 * AliExpress 상품 검색 결과를 공통 SearchResponse 형식으로 반환한다.
 * 모든 선택 파라미터는 기본값이 있어 keyword만 필수이다.
 */
@Slf4j
@RestController
@RequestMapping("/api/prices/aliexpress")
@RequiredArgsConstructor
public class AliExpressShoppingController {

    private final AliExpressShoppingService aliExpressShoppingService;

    /**
     * 키워드로 AliExpress 상품 검색
     *
     * @param keyword        검색 키워드 (필수)
     * @param pageNo         페이지 번호 (선택, 기본 1)
     * @param pageSize       페이지당 결과 수 (선택, 기본 10)
     * @param sort           정렬 기준 (선택, 예: SALE_PRICE_ASC)
     * @param targetCurrency 통화 (선택, 기본 KRW)
     * @param targetLanguage 언어 (선택, 기본 KO)
     * @param shipToCountry  배송 국가 (선택, 기본 KR)
     * @param trackingId     트래킹 ID (선택)
     * @return 검색된 상품 목록 (공통 SearchResponse 형식)
     */
    @GetMapping("/search")
    public ApiResponse<List<SearchResponse>> searchProducts(
            @RequestParam String keyword,
            @RequestParam(defaultValue = "1") int pageNo,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "KRW") String targetCurrency,
            @RequestParam(defaultValue = "KO") String targetLanguage,
            @RequestParam(defaultValue = "KR") String shipToCountry,
            @RequestParam(required = false) String trackingId
    ) {
        log.info("AliExpress 상품 검색 요청 - 키워드: {}, 페이지: {}/{}, 정렬: {}, 통화: {}",
                keyword, pageNo, pageSize, sort, targetCurrency);

        List<SearchResponse> results = aliExpressShoppingService.searchProducts(
                keyword, pageNo, pageSize, sort, targetCurrency, targetLanguage, shipToCountry, trackingId
        );

        return ApiResponse.success(results, "AliExpress 검색 성공");
    }
}