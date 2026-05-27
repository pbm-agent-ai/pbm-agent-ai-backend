package com.pbm.price.controller;

import com.pbm.price.common.ApiResponse;
import com.pbm.price.dto.request.YoutubeReviewAnalysisRequest;
import com.pbm.price.dto.response.YoutubeReviewResponse;
import com.pbm.price.service.YoutubeReviewService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * YouTube 리뷰 분석 API 컨트롤러.
 * <p>
 * 엔드포인트:
 * - POST   /api/v1/youtube/reviews                             : 영상 분석 + DB 저장
 * - GET    /api/v1/youtube/reviews                             : 전체 목록
 * - GET    /api/v1/youtube/reviews/{videoId}                   : video_id 단건 조회
 * - GET    /api/v1/youtube/reviews?categoryMain=HOME_APPLIANCE : 대분류별 조회
 * - GET    /api/v1/youtube/reviews?categorySub=FOOD_PROCESSOR  : 소분류별 조회
 * - GET    /api/v1/youtube/reviews?categoryMain=X&categorySub=Y: 대+소분류 조회
 * - GET    /api/v1/youtube/reviews?youtuber=귀곰               : 유튜버별 조회
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/youtube/reviews")
@RequiredArgsConstructor
public class YoutubeReviewController {

    private final YoutubeReviewService youtubeReviewService;

    /**
     * YouTube 리뷰 영상을 분석하고 결과를 DB에 저장한다.
     *
     * <pre>
     * POST /api/v1/youtube/reviews
     * {
     *   "videoUrl": "https://www.youtube.com/watch?v=UfOcIFZvWRY",
     *   "youtuberName": "귀곰",
     *   "categoryMain": "HOME_APPLIANCE",
     *   "categorySub": "FOOD_PROCESSOR",
     *   "conclusionRatio": 0.3
     * }
     * </pre>
     */
    @PostMapping
    public ResponseEntity<ApiResponse<YoutubeReviewResponse>> analyzeAndSave(
            @RequestBody YoutubeReviewAnalysisRequest request
    ) {
        log.info("YouTube 리뷰 분석 요청 - videoUrl: {}, category: {}/{}",
                request.videoUrl(), request.categoryMain(), request.categorySub());
        YoutubeReviewResponse response = youtubeReviewService.analyzeAndSave(request);
        return ResponseEntity.ok(ApiResponse.success(response, "YouTube 리뷰 분석 완료"));
    }

    /**
     * 저장된 YouTube 리뷰 목록을 조회한다.
     *
     * <pre>
     * GET /api/v1/youtube/reviews
     * GET /api/v1/youtube/reviews?categoryMain=HOME_APPLIANCE
     * GET /api/v1/youtube/reviews?categorySub=FOOD_PROCESSOR
     * GET /api/v1/youtube/reviews?categoryMain=HOME_APPLIANCE&categorySub=FOOD_PROCESSOR
     * GET /api/v1/youtube/reviews?youtuber=귀곰
     * </pre>
     */
    @GetMapping
    public ResponseEntity<ApiResponse<List<YoutubeReviewResponse>>> findAll(
            @RequestParam(required = false) String categoryMain,
            @RequestParam(required = false) String categorySub,
            @RequestParam(required = false) String youtuber
    ) {
        List<YoutubeReviewResponse> responses;

        if (categoryMain != null && categorySub != null) {
            log.info("대+소분류 YouTube 리뷰 조회 - {}/{}", categoryMain, categorySub);
            responses = youtubeReviewService.findByCategory(categoryMain, categorySub);
        } else if (categoryMain != null) {
            log.info("대분류별 YouTube 리뷰 조회 - {}", categoryMain);
            responses = youtubeReviewService.findByCategoryMain(categoryMain);
        } else if (categorySub != null) {
            log.info("소분류별 YouTube 리뷰 조회 - {}", categorySub);
            responses = youtubeReviewService.findByCategorySub(categorySub);
        } else if (youtuber != null && !youtuber.isBlank()) {
            log.info("유튜버별 YouTube 리뷰 조회 - {}", youtuber);
            responses = youtubeReviewService.findByYoutuberName(youtuber);
        } else {
            log.info("전체 YouTube 리뷰 조회");
            responses = youtubeReviewService.findAll();
        }

        return ResponseEntity.ok(ApiResponse.success(responses, "조회 완료"));
    }

    /**
     * video_id로 저장된 리뷰 단건을 조회한다.
     *
     * <pre>
     * GET /api/v1/youtube/reviews/UfOcIFZvWRY
     * </pre>
     */
    @GetMapping("/{videoId}")
    public ResponseEntity<ApiResponse<YoutubeReviewResponse>> findByVideoId(
            @PathVariable String videoId
    ) {
        log.info("YouTube 리뷰 단건 조회 - videoId: {}", videoId);
        YoutubeReviewResponse response = youtubeReviewService.findByVideoId(videoId);
        return ResponseEntity.ok(ApiResponse.success(response, "조회 완료"));
    }
}
