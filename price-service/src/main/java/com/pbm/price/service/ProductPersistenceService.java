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

/**
 * 검색 결과를 DB에 영속화하는 서비스.
 *
 * 역할: 네이버/알리 검색 결과를 공통 수집 대상(MonitorTarget), 상품(Product), 가격 이력(PriceHistory)에
 *       맞게 저장한다.
 * 동작: 검색 결과가 들어오면 각 상품별로 monitor target(productId 단위)을 찾거나 생성하고,
 *       상품을 upsert한 뒤 가격 스냅샷을 누적 저장한다.
 *       (주의) 검색 결과 저장 시에는 공유 폴링을 예약하지 않는다 (nextFetchAt = null).
 *       공유 폴링 활성화는 모니터링 구독 생성/갱신 시 이루어진다.
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
     * 각 상품마다 별도의 MonitorTarget을 생성/재사용하며, 공유 폴링은 예약하지 않는다.
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

            // 각 상품별로 MonitorTarget 생성/재사용 (product-unique)
            MonitorTarget monitorTarget = prepareMonitorTarget(
                    Platform.NAVER, externalProductId, keyword, item.link()
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
            saveSnapshot(monitorTarget, snapshot, now);
        }
    }

    /**
     * AliExpress 검색 결과를 DB에 저장한다.
     * 각 상품마다 별도의 MonitorTarget을 생성/재사용하며, 공유 폴링은 예약하지 않는다.
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
            String externalProductId = resolveExternalProductId(item.product_id(), item.product_detail_url(), item.product_title());

            // 각 상품별로 MonitorTarget 생성/재사용 (product-unique)
            MonitorTarget monitorTarget = prepareMonitorTarget(
                    Platform.ALIEXPRESS, externalProductId, keyword, item.product_detail_url()
            );

            BigDecimal currentPrice = hasText(item.target_sale_price())
                    ? parsePrice(item.target_sale_price())
                    : parsePrice(item.sale_price());

            CurrencyType currency = hasText(item.target_sale_price())
                    ? resolveCurrency(targetCurrency)
                    : CurrencyType.USD;

            // AliExpressCategoryNormalizer를 DB lookup 방식으로 바꿨기 때문에 여기에도 적용해줌
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
            saveSnapshot(monitorTarget, snapshot, now);
        }
    }

    /**
     * 스케줄러가 단일 MonitorTarget에 대해 수집한 네이버 상품 정보를 반영한다.
     * Product를 upsert하고 PriceHistory를 추가한다.
     * (MonitorTarget은 이미 존재하므로 새로 생성하지 않음)
     *
     * @param target  수집 대상 MonitorTarget
     * @param item    네이버 API가 반환한 매칭된 상품
     * @param checkedAt 수집 완료 시각
     */
    @Transactional
    public void saveRefreshedNaverProduct(MonitorTarget target, NaverShoppingItem item, Instant checkedAt) {
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
        saveSnapshot(target, snapshot, checkedAt);
    }

    /**
     * 스케줄러가 단일 MonitorTarget에 대해 수집한 알리 상품 정보를 반영한다.
     * Product를 upsert하고 PriceHistory를 추가한다.
     *
     * @param target         수집 대상 MonitorTarget
     * @param item           알리 API가 반환한 매칭된 상품
     * @param targetCurrency 타겟 통화 (보통 "KRW")
     * @param checkedAt      수집 완료 시각
     */
    @Transactional
    public void saveRefreshedAliExpressProduct(MonitorTarget target, AliExpressShoppingItem item,
                                                String targetCurrency, Instant checkedAt) {
        String externalProductId = resolveExternalProductId(item.product_id(), item.product_detail_url(), item.product_title());
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
        saveSnapshot(target, snapshot, checkedAt);
    }

    /**
     * 공통 수집 대상(MonitorTarget)을 productId 기준으로 찾거나 생성한다.
     * nextFetchAt은 설정하지 않으므로(호출하지 않음) 공유 폴링이 예약되지 않는다.
     */
    private MonitorTarget prepareMonitorTarget(Platform platform, String productId, String searchKeyword, String productUrl) {
        MonitorTarget monitorTarget = monitorTargetRepository
                .findByPlatformAndProductId(platform, productId)
                .orElseGet(() -> MonitorTarget.create(platform, productId, searchKeyword, productUrl, defaultFetchIntervalMinutes));

        // 검색 결과 저장 시에는 공유 폴링을 예약하지 않음 (nextFetchAt = null 유지)
        // 대신 검색 컨텍스트(키워드, URL)만 갱신한다
        monitorTarget.updateSearchContext(searchKeyword, productUrl);
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
