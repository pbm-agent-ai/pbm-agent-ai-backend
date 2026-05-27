package com.pbm.price.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * external-api-service /api/v1/youtube/analyze-review 응답을 매핑하는 DTO.
 * Python FastAPI 응답의 snake_case 필드를 Jackson으로 역직렬화한다.
 */
public record YoutubeReviewAnalysisExternalResponse(
        @JsonProperty("video_id") String videoId,
        @JsonProperty("youtuber_name") String youtuberName,
        String language,
        @JsonProperty("is_generated") boolean isGenerated,
        @JsonProperty("total_duration") Double totalDuration,
        String category,
        @JsonProperty("analyzed_text_start_time") Double analyzedTextStartTime,
        List<ExternalProduct> products,
        @JsonProperty("raw_conclusion_text") String rawConclusionText
) {
    public record ExternalProduct(
            Integer rank,
            @JsonProperty("product_name") String productName,
            String brand,
            List<String> pros,
            List<String> cons,
            String verdict,
            @JsonProperty("recommended_for") String recommendedFor
    ) {}
}
