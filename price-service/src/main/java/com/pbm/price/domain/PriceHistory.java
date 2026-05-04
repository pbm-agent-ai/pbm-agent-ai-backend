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
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 상품별 가격 이력 엔티티.
 *
 * 역할: 같은 상품이 여러 번 조회되었을 때 시점별 가격 스냅샷을 누적 저장한다.
 * 동작: 조회할 때마다 1건씩 insert되며, 나중에 가격 변동 추적과 알림 판단의 근거 데이터가 된다.
 * 연관: Product.
 */
@Getter
@Entity
@Table(name = "price_history")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PriceHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(name = "current_price", precision = 19, scale = 4)
    private BigDecimal currentPrice;

    @Column(name = "original_price", precision = 19, scale = 4)
    private BigDecimal originalPrice;

    @Enumerated(EnumType.STRING)
    @Column(name = "currency", nullable = false, length = 10)
    private CurrencyType currency;

    @Column(name = "checked_at", nullable = false)
    private Instant checkedAt;

    private PriceHistory(Product product,
                         BigDecimal currentPrice,
                         BigDecimal originalPrice,
                         CurrencyType currency,
                         Instant checkedAt) {
        this.product = product;
        this.currentPrice = currentPrice;
        this.originalPrice = originalPrice;
        this.currency = currency;
        this.checkedAt = checkedAt;
    }

    /**
     * 가격 스냅샷 1건을 생성한다.
     */
    public static PriceHistory create(Product product,
                                      BigDecimal currentPrice,
                                      BigDecimal originalPrice,
                                      CurrencyType currency,
                                      Instant checkedAt) {
        return new PriceHistory(product, currentPrice, originalPrice, currency, checkedAt);
    }
}
