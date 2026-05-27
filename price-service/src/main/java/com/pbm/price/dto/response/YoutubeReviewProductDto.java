package com.pbm.price.dto.response;

import com.pbm.price.domain.YoutubeReviewProduct;

import java.util.List;

/**
 * YouTube 리뷰 분석 상품 1건 응답 DTO
 *
 * @param rank            순위 (1위=1, 언급 없으면 null)
 * @param productName     상품명
 * @param brand           브랜드명
 * @param pros            장점 목록
 * @param cons            단점 목록
 * @param verdict         유튜버 총평
 * @param recommendedFor  추천 대상
 */
public record YoutubeReviewProductDto(
        Integer rank,
        String productName,
        String brand,
        List<String> pros,
        List<String> cons,
        String verdict,
        String recommendedFor
) {
    public static YoutubeReviewProductDto from(YoutubeReviewProduct product) {
        return new YoutubeReviewProductDto(
                product.getRank(),
                product.getProductName(),
                product.getBrand(),
                List.copyOf(product.getPros()),
                List.copyOf(product.getCons()),
                product.getVerdict(),
                product.getRecommendedFor()
        );
    }
}
