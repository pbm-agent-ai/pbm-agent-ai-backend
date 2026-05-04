package com.pbm.price.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 실제 검색 결과에 포함된 개별 상품 엔티티.
 *
 * 역할: 외부 API가 반환한 상품 메타데이터를 저장하고, 같은 상품을 중복 생성하지 않도록
 *       platform + externalProductId 기준으로 upsert한다.
 * 동작: 검색 결과에 다시 등장하면 title, url, 이미지, 쇼핑몰명, 마지막 노출 시각을 갱신한다.
 * 연관: MonitorTarget, PriceHistory.
 */
@Getter
@Entity
@Table(
        name = "products",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_products_source_external_id",
                columnNames = {"source_type", "external_product_id"}
        )
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 여러 상품이 하나의 공통 수집 대상에 속할 수 있으므로 ManyToOne 관계를 사용한다.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "monitor_target_id", nullable = false)
    private MonitorTarget monitorTarget;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 30)
    private SourceType sourceType;

    @Column(name = "external_product_id", nullable = false, length = 120)
    private String externalProductId;

    @Column(name = "title", length = 255)
    private String title;

    @Column(name = "product_url", length = 500)
    private String productUrl;

    @Column(name = "image_url", length = 500)
    private String imageUrl;

    @Column(name = "mall_name", length = 120)
    private String mallName;

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    private Product(MonitorTarget monitorTarget,
                    SourceType sourceType,
                    String externalProductId,
                    String title,
                    String productUrl,
                    String imageUrl,
                    String mallName,
                    Instant lastSeenAt) {
        this.monitorTarget = monitorTarget;
        this.sourceType = sourceType;
        this.externalProductId = externalProductId;
        this.title = title;
        this.productUrl = productUrl;
        this.imageUrl = imageUrl;
        this.mallName = mallName;
        this.lastSeenAt = lastSeenAt;
    }

    /**
     * 새 상품 엔티티를 생성한다.
     */
    public static Product create(MonitorTarget monitorTarget,
                                 SourceType sourceType,
                                 String externalProductId,
                                 String title,
                                 String productUrl,
                                 String imageUrl,
                                 String mallName,
                                 Instant lastSeenAt) {
        return new Product(monitorTarget, sourceType, externalProductId, title, productUrl, imageUrl, mallName, lastSeenAt);
    }

    /**
     * 동일 상품이 다시 검색되었을 때 최신 메타데이터를 갱신한다.
     */
    public void updateSnapshot(MonitorTarget monitorTarget,
                               String title,
                               String productUrl,
                               String imageUrl,
                               String mallName,
                               Instant lastSeenAt) {
        this.monitorTarget = monitorTarget;
        this.title = title;
        this.productUrl = productUrl;
        this.imageUrl = imageUrl;
        this.mallName = mallName;
        this.lastSeenAt = lastSeenAt;
    }

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        this.updatedAt = Instant.now();
    }
}
