package com.pbm.price.service;

import com.pbm.price.domain.CurrencyType;
import com.pbm.price.domain.MonitorTarget;
import com.pbm.price.domain.PriceHistory;
import com.pbm.price.domain.Platform;
import com.pbm.price.dto.response.AliExpressShoppingItem;
import com.pbm.price.dto.response.NaverShoppingItem;
import com.pbm.price.repository.MonitorTargetRepository;
import com.pbm.price.repository.PriceHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 검색 결과를 DB에 영속화하는 서비스.
 *
 * 역할: 네이버/알리 검색 결과를 MonitorTarget(상품 메타데이터 + 폴링 스케줄)과
 *       PriceHistory(가격 이력)에 저장한다.
 * 동작: 검색 결과가 들어오면 각 상품별로 MonitorTarget을 찾거나 생성하고,
 *       메타데이터를 갱신한 뒤 가격 스냅샷을 누적 저장한다.
 *       검색 결과 저장 시에는 nextFetchAt을 설정하지 않는다 (폴링 비활성 상태 유지).
 *       공유 폴링 활성화는 모니터링 구독 생성/갱신 시 이루어진다.
 * 연관: MonitorTargetRepository, PriceHistoryRepository.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductPersistenceService {

    private final MonitorTargetRepository monitorTargetRepository;
    private final PriceHistoryRepository priceHistoryRepository;
    private final NaverCategoryNormalizer naverCategoryNormalizer;
    private final AliExpressCategoryNormalizer aliExpressCategoryNormalizer;

    @Value("${app.monitoring.default-fetch-interval-minutes:10}")
    private int defaultFetchIntervalMinutes;

    /**
     * 네이버 검색 결과를 DB에 저장한다.
     * 각 상품마다 MonitorTarget을 생성/재사용하며, 공유 폴링은 예약하지 않는다.
     *
     * @param keyword 검색 키워드
     * @param items   외부 API가 반환한 네이버 상품 목록
     */
    @Transactional
    public void saveNaverSearchResults(String keyword, List<NaverShoppingItem> items) {
        if (items == null || items.isEmpty()) {
            return;
        }

        Instant now = Instant.now();

        for (NaverShoppingItem item : items) {
            String externalProductId = resolveExternalProductId(item.productId(), item.link(), item.title());
            String categoryPath = naverCategoryNormalizer.normalize(
                    item.category1(), item.category2(), item.category3(), item.category4()
            );

            CollectedProductSnapshot snapshot = new CollectedProductSnapshot(
                    Platform.NAVER,
                    externalProductId,
                    item.title(),
                    item.link(),
                    item.image(),
                    item.mallName(),
                    categoryPath,
                    parsePrice(item.lprice()),
                    parsePrice(item.hprice()),
                    CurrencyType.KRW
            );
            saveSnapshot(keyword, snapshot, now);
        }
    }

    /**
     * AliExpress 검색 결과를 DB에 저장한다.
     * 각 상품마다 MonitorTarget을 생성/재사용하며, 공유 폴링은 예약하지 않는다.
     *
     * @param keyword        검색 키워드
     * @param targetCurrency API 호출 시 요청한 목표 통화
     * @param items          외부 API가 반환한 AliExpress 상품 목록
     */
    @Transactional
    public void saveAliExpressSearchResults(String keyword, String targetCurrency, List<AliExpressShoppingItem> items) {
        if (items == null || items.isEmpty()) {
            return;
        }

        Instant now = Instant.now();

        for (AliExpressShoppingItem item : items) {
            String externalProductId = resolveExternalProductId(
                    item.product_id(), item.product_detail_url(), item.product_title()
            );

            BigDecimal currentPrice = hasText(item.target_sale_price())
                    ? parsePrice(item.target_sale_price())
                    : parsePrice(item.sale_price());

            CurrencyType currency = hasText(item.target_sale_price())
                    ? resolveCurrency(targetCurrency)
                    : CurrencyType.USD;

            String categoryPath = aliExpressCategoryNormalizer.normalize(
                    item.first_level_category_name(),
                    item.second_level_category_id(),
                    item.second_level_category_name()
            );

            CollectedProductSnapshot snapshot = new CollectedProductSnapshot(
                    Platform.ALIEXPRESS,
                    externalProductId,
                    item.product_title(),
                    item.product_detail_url(),
                    item.product_main_image_url(),
                    item.shop_name(),
                    categoryPath,
                    currentPrice,
                    parsePrice(item.target_original_price()),
                    currency
            );
            saveSnapshot(keyword, snapshot, now);
        }
    }

    /**
     * 스케줄러가 단일 MonitorTarget에 대해 수집한 네이버 상품 정보를 반영한다.
     * MonitorTarget 메타데이터를 갱신하고 PriceHistory를 추가한다.
     *
     * @param target    수집 대상 MonitorTarget
     * @param item      네이버 API가 반환한 매칭된 상품
     * @param checkedAt 수집 완료 시각
     */
    @Transactional
    public void saveRefreshedNaverProduct(MonitorTarget target, NaverShoppingItem item, Instant checkedAt) {
        String categoryPath = naverCategoryNormalizer.normalize(
                item.category1(), item.category2(), item.category3(), item.category4()
        );
        CollectedProductSnapshot snapshot = new CollectedProductSnapshot(
                Platform.NAVER,
                resolveExternalProductId(item.productId(), item.link(), item.title()),
                item.title(),
                item.link(),
                item.image(),
                item.mallName(),
                categoryPath,
                parsePrice(item.lprice()),
                parsePrice(item.hprice()),
                CurrencyType.KRW
        );
        refreshSnapshot(target, snapshot, checkedAt);
    }

    /**
     * 스케줄러가 단일 MonitorTarget에 대해 수집한 알리 상품 정보를 반영한다.
     * MonitorTarget 메타데이터를 갱신하고 PriceHistory를 추가한다.
     *
     * @param target         수집 대상 MonitorTarget
     * @param item           알리 API가 반환한 매칭된 상품
     * @param targetCurrency 타겟 통화 (보통 "KRW")
     * @param checkedAt      수집 완료 시각
     */
    @Transactional
    public void saveRefreshedAliExpressProduct(MonitorTarget target, AliExpressShoppingItem item,
                                               String targetCurrency, Instant checkedAt) {
        BigDecimal currentPrice = hasText(item.target_sale_price())
                ? parsePrice(item.target_sale_price())
                : parsePrice(item.sale_price());
        CurrencyType currency = hasText(item.target_sale_price())
                ? resolveCurrency(targetCurrency)
                : CurrencyType.USD;

        String categoryPath = aliExpressCategoryNormalizer.normalize(
                item.first_level_category_name(),
                item.second_level_category_id(),
                item.second_level_category_name()
        );

        CollectedProductSnapshot snapshot = new CollectedProductSnapshot(
                Platform.ALIEXPRESS,
                resolveExternalProductId(item.product_id(), item.product_detail_url(), item.product_title()),
                item.product_title(),
                item.product_detail_url(),
                item.product_main_image_url(),
                item.shop_name(),
                categoryPath,
                currentPrice,
                parsePrice(item.target_original_price()),
                currency
        );
        refreshSnapshot(target, snapshot, checkedAt);
    }

    /**
     * URL 모니터링 가격 보고를 price_history에 저장한다.
     * 가격 변동이 있을 때만 INSERT하여 불필요한 중복 데이터를 방지한다.
     *
     * @param target       URL MonitorTarget
     * @param currentPrice 현재 판매가 (KRW 기준)
     * @param checkedAt    수집 시각
     */
    @Transactional
    public void saveUrlPriceReport(MonitorTarget target, BigDecimal currentPrice, Instant checkedAt) {
        if (currentPrice == null || currentPrice.compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("URL 가격 저장 생략 - productId: {}, currentPrice가 null이거나 0 이하",
                    target.getProductId());
            return;
        }

        // 직전 가격과 동일하면 저장 생략 (변동 시에만 기록)
        if (isPriceSameAsLatest(target, currentPrice)) {
            log.debug("URL 가격 변동 없음, 저장 생략 - productId: {}, price: {} KRW",
                    target.getProductId(), currentPrice);
            return;
        }

        PriceHistory priceHistory = PriceHistory.create(
                target,
                currentPrice,
                null,  // originalPrice: URL 크롤링에서는 할인 전 가격을 수집하지 않음
                CurrencyType.KRW,
                checkedAt
        );
        priceHistoryRepository.save(priceHistory);
        log.info("URL 가격 변동 감지, 이력 저장 - productId: {}, price: {} KRW", target.getProductId(), currentPrice);
    }

    // ────────────────────────────────────────────────────────────────────────
    // private helpers
    // ────────────────────────────────────────────────────────────────────────

    /**
     * 검색 결과 저장용: MonitorTarget upsert + PriceHistory 추가.
     * nextFetchAt은 변경하지 않는다 (폴링 비활성 상태 유지).
     */
    private void saveSnapshot(String keyword, CollectedProductSnapshot snapshot, Instant checkedAt) {
        MonitorTarget target = monitorTargetRepository
                .findByPlatformAndProductId(snapshot.platform(), snapshot.externalProductId())
                .orElseGet(() -> MonitorTarget.createWithMetadata(
                        snapshot.platform(),
                        snapshot.externalProductId(),
                        keyword,
                        snapshot.productUrl(),
                        snapshot.title(),
                        snapshot.imageUrl(),
                        snapshot.mallName(),
                        snapshot.categoryPath(),
                        checkedAt,
                        defaultFetchIntervalMinutes
                ));

        // 기존 레코드면 메타데이터만 갱신 (nextFetchAt 유지)
        target.updateSearchContext(keyword, snapshot.productUrl());
        target.updateProductMetadata(
                snapshot.title(), snapshot.imageUrl(), snapshot.mallName(),
                snapshot.categoryPath(), checkedAt
        );
        MonitorTarget saved = monitorTargetRepository.save(target);

        appendPriceHistory(saved, snapshot, checkedAt);
    }

    /**
     * 스케줄러 수집 결과 저장용: MonitorTarget 메타데이터 갱신 + PriceHistory 추가.
     * MonitorTarget은 이미 존재하므로 새로 생성하지 않는다.
     */
    private void refreshSnapshot(MonitorTarget target, CollectedProductSnapshot snapshot, Instant checkedAt) {
        target.updateProductMetadata(
                snapshot.title(), snapshot.imageUrl(), snapshot.mallName(),
                snapshot.categoryPath(), checkedAt
        );
        MonitorTarget saved = monitorTargetRepository.save(target);

        appendPriceHistory(saved, snapshot, checkedAt);
    }

    /**
     * 가격 변동이 있을 때만 이력 1건을 추가한다.
     * currentPrice가 null이거나 직전 가격과 동일하면 저장을 생략한다.
     */
    private void appendPriceHistory(MonitorTarget target, CollectedProductSnapshot snapshot, Instant checkedAt) {
        if (snapshot.currentPrice() == null) {
            log.warn("가격 저장 생략 - platform: {}, productId: {}, currentPrice가 null임",
                    snapshot.platform(), snapshot.externalProductId());
            return;
        }

        // 직전 가격과 동일하면 저장 생략 (변동 시에만 기록)
        if (isPriceSameAsLatest(target, snapshot.currentPrice())) {
            log.debug("가격 변동 없음, 저장 생략 - platform: {}, productId: {}, price: {}",
                    snapshot.platform(), snapshot.externalProductId(), snapshot.currentPrice());
            return;
        }

        PriceHistory priceHistory = PriceHistory.create(
                target,
                snapshot.currentPrice(),
                snapshot.originalPrice(),
                snapshot.currency(),
                checkedAt
        );
        priceHistoryRepository.save(priceHistory);
        log.info("가격 변동 감지, 이력 저장 - platform: {}, productId: {}, price: {}",
                snapshot.platform(), snapshot.externalProductId(), snapshot.currentPrice());
    }

    /**
     * 직전 기록된 가격과 현재 가격이 동일한지 비교한다.
     * 이전 이력이 없으면 false를 반환하여 최초 기록을 허용한다.
     *
     * @param target       수집 대상
     * @param currentPrice 현재 수집된 가격
     * @return 동일하면 true (INSERT 불필요), 다르거나 이력 없으면 false (INSERT 필요)
     */
    private boolean isPriceSameAsLatest(MonitorTarget target, BigDecimal currentPrice) {
        return priceHistoryRepository.findTopByMonitorTargetOrderByCheckedAtDesc(target)
                .map(latest -> latest.getCurrentPrice().compareTo(currentPrice) == 0)
                .orElse(false);
    }

    /**
     * 외부 상품 ID가 없을 때 URL 또는 제목으로 대체 식별자를 만든다.
     */
    private String resolveExternalProductId(String externalProductId, String productUrl, String title) {
        if (hasText(externalProductId)) {
            return externalProductId.trim();
        }
        if (hasText(productUrl)) {
            return productUrl.trim();
        }
        return title == null ? "unknown-product" : title.trim();
    }

    /**
     * 문자열 가격을 BigDecimal로 변환한다.
     */
    private BigDecimal parsePrice(String value) {
        if (!hasText(value)) {
            return null;
        }
        try {
            return new BigDecimal(value.replace(",", "").trim());
        } catch (NumberFormatException e) {
            log.warn("가격 파싱 실패 - value: {}", value);
            return null;
        }
    }

    /**
     * 현재 MVP 범위에서는 KRW, USD만 처리한다.
     */
    private CurrencyType resolveCurrency(String currency) {
        if (!hasText(currency)) {
            return CurrencyType.KRW;
        }
        return CurrencyType.valueOf(currency.trim().toUpperCase(java.util.Locale.ROOT));
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * 외부 검색 결과를 내부 영속화 구조로 잠시 옮겨 담는 내부 record.
     */
    public record CollectedProductSnapshot(
            Platform platform,
            String externalProductId,
            String title,
            String productUrl,
            String imageUrl,
            String mallName,
            String categoryPath,
            BigDecimal currentPrice,
            BigDecimal originalPrice,
            CurrencyType currency
    ) {
    }
}
