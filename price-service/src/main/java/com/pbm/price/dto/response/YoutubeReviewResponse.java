package com.pbm.price.dto.response;

import com.pbm.price.domain.YoutubeReview;

import java.time.Instant;
import java.util.List;

/**
 * YouTube 리뷰 분석 결과 응답 DTO (DB 저장 후 반환)
 *
 * @param id                      DB PK
 * @param videoId                 YouTube 영상 ID
 * @param videoUrl                YouTube 영상 URL
 * @param youtuberName            유튜버 이름
 * @param language                자막 언어
 * @param isGenerated             자동 생성 자막 여부
 * @param totalDuration           영상 전체 길이 (초)
 * @param categoryMain            대분류 카테고리 (예: HOME_APPLIANCE)
 * @param categorySub             소분류 카테고리 (예: FOOD_PROCESSOR)
 * @param analyzedTextStartTime   분석 시작 시각 (초)
 * @param products                분석된 상품 목록 (순위순)
 * @param createdAt               최초 분석 시각
 * @param updatedAt               최근 갱신 시각
 */
public record YoutubeReviewResponse(
        Long id,
        String videoId,
        String videoUrl,
        String youtuberName,
        String language,
        boolean isGenerated,
        Double totalDuration,
        String categoryMain,
        String categorySub,
        Double analyzedTextStartTime,
        List<YoutubeReviewProductDto> products,
        Instant createdAt,
        Instant updatedAt
) {
    public static YoutubeReviewResponse from(YoutubeReview review) {
        return new YoutubeReviewResponse(
                review.getId(),
                review.getVideoId(),
                review.getVideoUrl(),
                review.getYoutuberName(),
                review.getLanguage(),
                review.isGenerated(),
                review.getTotalDuration(),
                review.getCategoryMain(),
                review.getCategorySub(),
                review.getAnalyzedTextStartTime(),
                review.getProducts().stream()
                        .map(YoutubeReviewProductDto::from)
                        .toList(),
                review.getCreatedAt(),
                review.getUpdatedAt()
        );
    }
}
