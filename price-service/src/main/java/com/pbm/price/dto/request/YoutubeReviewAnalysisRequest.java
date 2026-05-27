package com.pbm.price.dto.request;

import java.util.List;

/**
 * YouTube 리뷰 분석 요청 DTO
 *
 * 카테고리 고정 목록:
 *   대분류(categoryMain)    소분류(categorySub)
 *   ─────────────────────────────────────────
 *   HOME_APPLIANCE      →  FOOD_PROCESSOR, VACUUM, WASHER, REFRIGERATOR, AIR_PURIFIER
 *   ELECTRONICS         →  EARPHONE, HEADPHONE, SMARTPHONE, MONITOR, KEYBOARD, MOUSE, LAPTOP, TABLET
 *   LIVING              →  FURNITURE, BEDDING, LIGHTING
 *   BEAUTY              →  SKINCARE, MAKEUP, HAIR
 *   FOOD                →  SUPPLEMENT, COFFEE, SNACK
 *
 * @param videoUrl       YouTube 영상 URL 또는 ID
 * @param youtuberName   유튜버 이름 (예: 귀곰, 잇섭)
 * @param categoryMain   대분류 카테고리 (예: HOME_APPLIANCE)
 * @param categorySub    소분류 카테고리 (예: FOOD_PROCESSOR)
 * @param languages      선호 자막 언어 코드 목록 (기본: ko, ko-KR, en)
 * @param conclusionRatio 뒷부분 결론 구간 비율 (0.0~1.0, 기본: 0.3)
 */
public record YoutubeReviewAnalysisRequest(
        String videoUrl,
        String youtuberName,
        String categoryMain,
        String categorySub,
        List<String> languages,
        Double conclusionRatio
) {
    /** 기본값이 적용된 요청을 생성한다 */
    public static YoutubeReviewAnalysisRequest withDefaults(
            String videoUrl, String youtuberName,
            String categoryMain, String categorySub) {
        return new YoutubeReviewAnalysisRequest(
                videoUrl,
                youtuberName,
                categoryMain,
                categorySub,
                List.of("ko", "ko-KR", "en"),
                0.3
        );
    }
}
