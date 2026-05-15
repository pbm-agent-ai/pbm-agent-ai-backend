package com.pbm.price.service;

import com.pbm.price.domain.CurrencyType;
import com.pbm.price.domain.MonitorTarget;
import com.pbm.price.domain.PriceHistory;
import com.pbm.price.domain.Product;
import com.pbm.price.domain.Platform;
import com.pbm.price.dto.response.AliExpressShoppingItem;
import com.pbm.price.dto.response.NaverShoppingItem;
import com.pbm.price.repository.MonitorTargetRepository;
import com.pbm.price.repository.PriceHistoryRepository;
import com.pbm.price.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * 검색 결과를 DB에 영속화하는 서비스.
 *
 * 역할: 네이버/알리 검색 결과를 공통 수집 대상(MonitorTarget), 상품(Product), 가격 이력(PriceHistory)에
 *       맞게 저장한다.
 * 동작: 검색 결과가 들어오면 monitor target을 찾거나 생성하고, 상품을 upsert한 뒤 가격 스냅샷을 누적 저장한다.
 * 연관: MonitorTargetRepository, ProductRepository, PriceHistoryRepository.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductPersistenceService {

    private final MonitorTargetRepository monitorTargetRepository;
    private final ProductRepository productRepository;
    private final PriceHistoryRepository priceHistoryRepository;
    private final NaverCategoryNormalizer naverCategoryNormalizer;
    private final AliExpressCategoryNormalizer aliExpressCategoryNormalizer;

    @Value("${app.monitoring.default-fetch-interval-minutes:10}")
    private int defaultFetchIntervalMinutes;

    /**
     * 네이버 검색 결과를 DB에 저장한다.
     *
     * @param keyword 검색 키워드
     * @param items   외부 API가 반환한 네이버 상품 목록
     */
    @Transactional
    public void saveNaverSearchResults(String keyword, List<NaverShoppingItem> items) {
        if (items == null || items.isEmpty()) {
            return;
        }

        MonitorTarget monitorTarget = prepareMonitorTarget(Platform.NAVER, keyword);
        Instant now = Instant.now();

        for (NaverShoppingItem item : items) {
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
            saveSnapshot(monitorTarget, snapshot, now);
        }
    }

    /**
     * AliExpress 검색 결과를 DB에 저장한다.
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

        MonitorTarget monitorTarget = prepareMonitorTarget(Platform.ALIEXPRESS, keyword);
        Instant now = Instant.now();

        for (AliExpressShoppingItem item : items) {
            BigDecimal currentPrice = hasText(item.target_sale_price())
                    ? parsePrice(item.target_sale_price())
                    : parsePrice(item.sale_price());

            CurrencyType currency = hasText(item.target_sale_price())
                    ? resolveCurrency(targetCurrency)
                    : CurrencyType.USD;

            // AliExpressCategoryNormalizer를  DB lookup 방식으로 바꿨기 때문에 여기에도 적용해줌
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
            saveSnapshot(monitorTarget, snapshot, now);
        }
    }

    /**
     * 공통 수집 대상(MonitorTarget)을 찾거나 생성하고 최신 수집 시각을 갱신한다.
     */
    private MonitorTarget prepareMonitorTarget(Platform platform, String keyword) {
        String normalizedKeyword = normalizeKeyword(keyword);

        MonitorTarget monitorTarget = monitorTargetRepository
                .findByPlatformAndNormalizedKeyword(platform, normalizedKeyword)
                .orElseGet(() -> MonitorTarget.create(platform, normalizedKeyword, defaultFetchIntervalMinutes));

        monitorTarget.markFetched(Instant.now());
        return monitorTargetRepository.save(monitorTarget);
    }

    /**
     * 상품 upsert 후 가격 히스토리 1건을 저장한다.
     */
    private void saveSnapshot(MonitorTarget monitorTarget, CollectedProductSnapshot snapshot, Instant checkedAt) {
        Product product = productRepository
                .findByPlatformAndExternalProductId(snapshot.platform(), snapshot.externalProductId())
                .orElseGet(() -> Product.create(
                        monitorTarget,
                        snapshot.platform(),
                        snapshot.externalProductId(),
                        snapshot.title(),
                        snapshot.productUrl(),
                        snapshot.imageUrl(),
                        snapshot.mallName(),
                        snapshot.categoryPath(),
                        checkedAt
                ));

        product.updateSnapshot(
                monitorTarget,
                snapshot.title(),
                snapshot.productUrl(),
                snapshot.imageUrl(),
                snapshot.mallName(),
                snapshot.categoryPath(),
                checkedAt
        );

        Product savedProduct = productRepository.save(product);

        // 현재가를 읽지 못한 경우에는 의미 있는 가격 히스토리를 만들 수 없으므로 스냅샷 저장을 생략한다.
        if (snapshot.currentPrice() == null) {
            log.warn("가격 저장 생략 - platform: {}, externalProductId: {}, currentPrice가 null임",
                    snapshot.platform(), snapshot.externalProductId());
            return;
        }

        PriceHistory priceHistory = PriceHistory.create(
                savedProduct,
                snapshot.currentPrice(),
                snapshot.originalPrice(),
                snapshot.currency(),
                checkedAt
        );
        priceHistoryRepository.save(priceHistory);
    }

    /**
     * 공유 수집 대상 기준 키워드를 정규화한다.
     *
     * 구현 이유:
     * - "아이폰 15", "아이폰15"처럼 공백 차이만 있는 입력을 같은 수집 대상으로 묶기 위함이다.
     * - 영문 검색어는 대소문자 차이도 제거한다.
     */
    private String normalizeKeyword(String keyword) {
        if (keyword == null) {
            return "";
        }
        return keyword.trim()
                .replaceAll("\\s+", "")
                .toLowerCase(Locale.ROOT);
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
        return CurrencyType.valueOf(currency.trim().toUpperCase(Locale.ROOT));
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * 외부 검색 결과를 내부 영속화 구조로 잠시 옮겨 담는 내부 record.
     */
    private record CollectedProductSnapshot(
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
