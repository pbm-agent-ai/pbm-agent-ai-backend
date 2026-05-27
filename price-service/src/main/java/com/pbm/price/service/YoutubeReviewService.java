package com.pbm.price.service;

import com.pbm.price.client.ExternalApiClient;
import com.pbm.price.domain.YoutubeReview;
import com.pbm.price.domain.YoutubeReviewProduct;
import com.pbm.price.dto.request.YoutubeReviewAnalysisRequest;
import com.pbm.price.dto.response.YoutubeReviewAnalysisExternalResponse;
import com.pbm.price.dto.response.YoutubeReviewResponse;
import com.pbm.price.repository.YoutubeReviewRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * YouTube 리뷰 분석 서비스.
 * <p>
 * 역할:
 * 1. external-api-service에 자막 수집 + GPT 분석 요청
 * 2. 분석 결과를 DB에 저장 (동일 video_id 재분석 시 갱신)
 * 3. 저장된 리뷰 조회 기능 제공
 * <p>
 * 카테고리: 사용자가 요청 시 직접 지정 (대분류 + 소분류)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class YoutubeReviewService {

    private final ExternalApiClient externalApiClient;
    private final YoutubeReviewRepository youtubeReviewRepository;

    /**
     * YouTube 리뷰 영상을 분석하고 결과를 DB에 저장한다.
     * <p>
     * 동일 video_id가 이미 존재하면 기존 데이터를 갱신하고,
     * 없으면 새로 생성한다.
     *
     * @param request 영상 URL, 유튜버 이름, 카테고리(대/소분류), 분석 옵션
     * @return 저장된 분석 결과
     */
    @Transactional
    public YoutubeReviewResponse analyzeAndSave(YoutubeReviewAnalysisRequest request) {
        log.info("YouTube 리뷰 분석 시작 - videoUrl: {}, youtuber: {}, category: {}/{}",
                request.videoUrl(), request.youtuberName(),
                request.categoryMain(), request.categorySub());

        // 1. external-api-service에 분석 요청 (GPT 자막 분석)
        YoutubeReviewAnalysisExternalResponse external = externalApiClient.analyzeYoutubeReview(request);

        // 2. 카테고리는 요청에서 받은 값 사용 (고정 목록)
        String categoryMain = request.categoryMain().toUpperCase();
        String categorySub = request.categorySub().toUpperCase();

        // 3. 기존 분석 결과 있으면 갱신, 없으면 새로 생성
        YoutubeReview review = youtubeReviewRepository
                .findByVideoIdWithProducts(external.videoId())
                .map(existing -> {
                    log.info("기존 분석 결과 갱신 - videoId: {}", external.videoId());
                    existing.update(
                            external.youtuberName(),
                            external.language(),
                            external.isGenerated(),
                            external.totalDuration(),
                            categoryMain,
                            categorySub,
                            external.analyzedTextStartTime(),
                            external.rawConclusionText()
                    );
                    return existing;
                })
                .orElseGet(() -> {
                    log.info("새 분석 결과 저장 - videoId: {}", external.videoId());
                    return YoutubeReview.create(
                            external.videoId(),
                            request.videoUrl(),
                            external.youtuberName(),
                            external.language(),
                            external.isGenerated(),
                            external.totalDuration(),
                            categoryMain,
                            categorySub,
                            external.analyzedTextStartTime(),
                            external.rawConclusionText()
                    );
                });

        // 4. 상품 목록 추가
        for (YoutubeReviewAnalysisExternalResponse.ExternalProduct p : external.products()) {
            YoutubeReviewProduct product = YoutubeReviewProduct.create(
                    p.rank(),
                    p.productName(),
                    p.brand(),
                    p.pros(),
                    p.cons(),
                    p.verdict(),
                    p.recommendedFor()
            );
            review.addProduct(product);
        }

        YoutubeReview saved = youtubeReviewRepository.save(review);

        log.info("YouTube 리뷰 저장 완료 - id: {}, videoId: {}, category: {}/{}, products: {}건",
                saved.getId(), saved.getVideoId(),
                saved.getCategoryMain(), saved.getCategorySub(),
                saved.getProducts().size());

        return YoutubeReviewResponse.from(saved);
    }

    /**
     * 저장된 모든 YouTube 리뷰 목록을 조회한다.
     */
    @Transactional(readOnly = true)
    public List<YoutubeReviewResponse> findAll() {
        return youtubeReviewRepository.findAll().stream()
                .map(YoutubeReviewResponse::from)
                .toList();
    }

    /**
     * video_id로 저장된 리뷰를 조회한다.
     */
    @Transactional(readOnly = true)
    public YoutubeReviewResponse findByVideoId(String videoId) {
        return youtubeReviewRepository.findByVideoIdWithProducts(videoId)
                .map(YoutubeReviewResponse::from)
                .orElseThrow(() -> new IllegalArgumentException(
                        "분석된 리뷰가 없습니다. videoId=" + videoId));
    }

    /**
     * 대분류 카테고리로 리뷰 목록을 조회한다.
     */
    @Transactional(readOnly = true)
    public List<YoutubeReviewResponse> findByCategoryMain(String categoryMain) {
        return youtubeReviewRepository
                .findByCategoryMainOrderByCreatedAtDesc(categoryMain.toUpperCase())
                .stream()
                .map(YoutubeReviewResponse::from)
                .toList();
    }

    /**
     * 소분류 카테고리로 리뷰 목록을 조회한다.
     */
    @Transactional(readOnly = true)
    public List<YoutubeReviewResponse> findByCategorySub(String categorySub) {
        return youtubeReviewRepository
                .findByCategorySubOrderByCreatedAtDesc(categorySub.toUpperCase())
                .stream()
                .map(YoutubeReviewResponse::from)
                .toList();
    }

    /**
     * 대분류 + 소분류로 리뷰 목록을 조회한다.
     */
    @Transactional(readOnly = true)
    public List<YoutubeReviewResponse> findByCategory(String categoryMain, String categorySub) {
        return youtubeReviewRepository
                .findByCategoryMainAndCategorySubOrderByCreatedAtDesc(
                        categoryMain.toUpperCase(), categorySub.toUpperCase())
                .stream()
                .map(YoutubeReviewResponse::from)
                .toList();
    }

    /**
     * 유튜버 이름으로 리뷰 목록을 조회한다.
     */
    @Transactional(readOnly = true)
    public List<YoutubeReviewResponse> findByYoutuberName(String youtuberName) {
        return youtubeReviewRepository
                .findByYoutuberNameOrderByCreatedAtDesc(youtuberName)
                .stream()
                .map(YoutubeReviewResponse::from)
                .toList();
    }
}
