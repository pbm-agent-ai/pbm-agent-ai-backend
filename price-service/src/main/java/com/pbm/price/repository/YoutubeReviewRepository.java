package com.pbm.price.repository;

import com.pbm.price.domain.YoutubeReview;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * YouTube 리뷰 분석 결과 레포지토리
 */
public interface YoutubeReviewRepository extends JpaRepository<YoutubeReview, Long> {

    /** video_id로 기존 분석 결과를 조회한다 (재분석 시 중복 방지) */
    Optional<YoutubeReview> findByVideoId(String videoId);

    /** 유튜버 이름으로 분석 결과 목록을 조회한다 (최신순) */
    List<YoutubeReview> findByYoutuberNameOrderByCreatedAtDesc(String youtuberName);

    /** 대분류 카테고리로 분석 결과 목록을 조회한다 (최신순) */
    List<YoutubeReview> findByCategoryMainOrderByCreatedAtDesc(String categoryMain);

    /** 소분류 카테고리로 분석 결과 목록을 조회한다 (최신순) */
    List<YoutubeReview> findByCategorySubOrderByCreatedAtDesc(String categorySub);

    /** 대분류 + 소분류로 분석 결과 목록을 조회한다 (최신순) */
    List<YoutubeReview> findByCategoryMainAndCategorySubOrderByCreatedAtDesc(
            String categoryMain, String categorySub);

    /** 상품 목록을 함께 페치하여 N+1 문제를 방지한다 */
    @Query("SELECT r FROM YoutubeReview r LEFT JOIN FETCH r.products WHERE r.id = :id")
    Optional<YoutubeReview> findByIdWithProducts(@Param("id") Long id);

    /** video_id로 상품 목록을 함께 페치한다 */
    @Query("SELECT r FROM YoutubeReview r LEFT JOIN FETCH r.products WHERE r.videoId = :videoId")
    Optional<YoutubeReview> findByVideoIdWithProducts(@Param("videoId") String videoId);
}
