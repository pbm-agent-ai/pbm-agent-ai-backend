package com.pbm.price.controller;

import com.pbm.price.common.ApiResponse;
import com.pbm.price.dto.response.SearchResponse;
import com.pbm.price.service.NaverShoppingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 네이버 쇼핑 검색 API 컨트롤러
 * GET /api/v1/naver/search?keyword={keyword}&display={display}
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/naver")
@RequiredArgsConstructor
public class NaverShoppingController {

    private final NaverShoppingService naverShoppingService;

    /**
     * 키워드로 상품 검색
     *
     * @param keyword 검색 키워드 (필수)
     * @param display 검색 결과 개수 (선택, 기본 10)
     * @return 검색된 상품 목록
     */
    @GetMapping("/search")
    public ApiResponse<List<SearchResponse>> searchProducts(
            @RequestParam String keyword,
            @RequestParam(defaultValue = "10") int display
    ) {
        log.info("상품 검색 요청 - 키워드: {}, 개수: {}", keyword, display);

        List<SearchResponse> results = naverShoppingService.searchProducts(keyword, display);

        return ApiResponse.success(results, "검색 성공");
    }
}
