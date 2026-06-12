package com.pbm.price.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * YouTube 리뷰 영상에서 AI가 분석한 상품 1건 엔티티.
 * <p>
 * 역할: 유튜버가 리뷰한 개별 상품의 순위·장단점·총평을 저장한다.
 * 연관: YoutubeReview (N:1)
 */
@Getter
@Entity
@Table(
        name = "youtube_review_products",
        indexes = {
                @Index(name = "idx_yrp_review_id", columnList = "review_id"),
                @Index(name = "idx_yrp_product_name", columnList = "product_name")
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class YoutubeReviewProduct {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 소속 리뷰 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "review_id", nullable = false)
    private YoutubeReview review;

    /** 순위 (1위=1). 유튜버가 순위를 언급하지 않은 경우 null */
    @Column(name = "rank")
    private Integer rank;

    /** 상품명 */
    @Column(name = "product_name", nullable = false, length = 300)
    private String productName;

    /** 상품 이미지 URL 배열 (PostgreSQL text[]) */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "image_urls", columnDefinition = "text[]")
    private String[] imageUrls = new String[0];

    /** 브랜드명 */
    @Column(name = "brand", length = 100)
    private String brand;

    /** 유튜버가 언급한 장점 목록 */
    @ElementCollection
    @CollectionTable(name = "youtube_review_product_pros",
            joinColumns = @JoinColumn(name = "product_id"))
    @Column(name = "pro_text", length = 500)
    private List<String> pros = new ArrayList<>();

    /** 유튜버가 언급한 단점 목록 */
    @ElementCollection
    @CollectionTable(name = "youtube_review_product_cons",
            joinColumns = @JoinColumn(name = "product_id"))
    @Column(name = "con_text", length = 500)
    private List<String> cons = new ArrayList<>();

    /** 유튜버의 총평 한 줄 */
    @Column(name = "verdict", length = 500)
    private String verdict;

    /** 어떤 사용자에게 추천하는지 */
    @Column(name = "recommended_for", length = 500)
    private String recommendedFor;

    private YoutubeReviewProduct(Integer rank, String productName, String brand,
                                 List<String> pros, List<String> cons,
                                 String verdict, String recommendedFor) {
        this(rank, productName, brand, pros, cons, verdict, recommendedFor, null);
    }

    private YoutubeReviewProduct(Integer rank, String productName, String brand,
                                 List<String> pros, List<String> cons,
                                 String verdict, String recommendedFor,
                                 List<String> imageUrls) {
        this.rank = rank;
        this.productName = productName;
        this.imageUrls = imageUrls != null ? imageUrls.toArray(new String[0]) : new String[0];
        this.brand = brand;
        this.pros = pros != null ? new ArrayList<>(pros) : new ArrayList<>();
        this.cons = cons != null ? new ArrayList<>(cons) : new ArrayList<>();
        this.verdict = verdict;
        this.recommendedFor = recommendedFor;
    }

    public static YoutubeReviewProduct create(Integer rank, String productName, String brand,
                                              List<String> pros, List<String> cons,
                                              String verdict, String recommendedFor) {
        return new YoutubeReviewProduct(rank, productName, brand, pros, cons, verdict, recommendedFor);
    }

    public static YoutubeReviewProduct create(Integer rank, String productName, String brand,
                                              List<String> pros, List<String> cons,
                                              String verdict, String recommendedFor,
                                              List<String> imageUrls) {
        return new YoutubeReviewProduct(rank, productName, brand, pros, cons, verdict, recommendedFor, imageUrls);
    }

    /** 소속 리뷰를 설정한다 (YoutubeReview.addProduct()에서 호출). */
    void assignReview(YoutubeReview review) {
        this.review = review;
    }
}
