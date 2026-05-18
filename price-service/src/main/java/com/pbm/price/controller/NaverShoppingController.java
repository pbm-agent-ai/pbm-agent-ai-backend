package com.pbm.price.controller;

import com.pbm.price.common.ApiResponse;
import com.pbm.price.dto.response.SearchResponse;
import com.pbm.price.service.NaverShoppingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Price-Naver", description = "네이버 쇼핑 검색 API")
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
    @Operation(summary = "네이버 상품 검색", description = "키워드로 네이버 쇼핑 상품을 조회하고 공통 SearchResponse 형식으로 반환합니다.")
    @GetMapping("/search")
    public ApiResponse<List<SearchResponse>> searchProducts(
            @Parameter(description = "검색 키워드", example = "아이폰 15 케이스")
            @RequestParam String keyword,
            @Parameter(description = "반환 개수", example = "10")
            @RequestParam(defaultValue = "10") int display
    ) {
        log.info("상품 검색 요청 - 키워드: {}, 개수: {}", keyword, display);

        List<SearchResponse> results = naverShoppingService.searchProducts(keyword, display);

        return ApiResponse.success(results, "검색 성공");
    }
}
