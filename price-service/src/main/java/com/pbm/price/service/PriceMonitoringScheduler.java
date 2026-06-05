package com.pbm.price.service;

import com.pbm.price.client.ExternalApiClient;
import com.pbm.price.domain.MonitorTarget;
import com.pbm.price.domain.Platform;
import com.pbm.price.dto.response.AliExpressShoppingItem;
import com.pbm.price.dto.response.AliexpressProductDetailResponse;
import com.pbm.price.dto.response.NaverShoppingItem;
import com.pbm.price.repository.MonitorTargetRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * 공유 수집 기반 가격 모니터링 스케줄러 (product 단위).
 *
 * 역할: 주기적으로 수집 예정 시각이 도래한 MonitorTarget을 찾아
 *       해당 상품의 최신 가격 정보를 수집하고 DB에 영속화한다.
 * 동작: 스케줄러 실행 주기(scheduler-interval-ms, 기본 1분)마다 DB를 폴링하여
 *       nextFetchAt이 현재 시각보다 이전인 MonitorTarget만 선택적으로 수집한다.
 *       개별 대상의 수집 주기(fetch-interval-minutes, 기본 10분)는 MonitorTarget에 설정되며,
 *       스케줄러는 1분마다 확인만 하고 실제 API 호출은 수집 예정 시각이 도래한 대상에만 수행한다.
 *       하나의 대상 수집이 실패해도 전체 배치가 중단되지 않도록 try-catch로 격리한다.
 * 변경: keyword 기반 수집 → productId 기반 수집으로 전환.
 *       NAVER: searchKeyword로 검색 후 productId/productUrl 매칭.
 *       ALIEXPRESS: detail API 우선, searchKeyword fallback.
 * 연관: MonitorTargetRepository, ExternalApiClient, ProductPersistenceService.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PriceMonitoringScheduler {

    private final MonitorTargetRepository monitorTargetRepository;
    private final ExternalApiClient externalApiClient;
    private final ProductPersistenceService productPersistenceService;

    // 검색 시 한 번에 가져올 기본 결과 수
    @Value("${app.monitoring.default-display:10}")
    private int defaultDisplay;

    // 기본 AliExpress 검색 파라미터
    @Value("${app.monitoring.aliexpress-default-page-size:10}")
    private int aliExpressDefaultPageSize;

    // 네이버 매칭용 2차 조회 임계값
    private static final int NAVER_MAX_PAGE_SIZE = 100;

    /**
     * 주기적으로 수집 예정 시각이 도래한 모니터링 대상을 수집한다.
     *
     * 동작 흐름:
     * 1. nextFetchAt이 현재 시각 이전인 MonitorTarget 목록을 조회
     * 2. 각 대상의 platform에 따라 네이버 또는 알리 API 호출
     * 3. 매칭된 상품만 Product/PriceHistory에 반영
     * 4. 대상의 lastFetchedAt/nextFetchAt 갱신
     * 5. 한 대상 실패가 전체 배치를 중단시키지 않도록 예외를 로깅하고 계속 진행
     */
    @Scheduled(fixedDelayString = "${app.monitoring.scheduler-interval-ms:600000}")
    public void collectDueTargets() {
        Instant now = Instant.now();
        List<MonitorTarget> dueTargets = monitorTargetRepository.findByNextFetchAtBefore(now);

        if (dueTargets.isEmpty()) {
            log.debug("수집 예정 대상 없음 - 기준 시각: {}", now);
            return;
        }

        log.info("가격 모니터링 수집 시작 - 대상 {}건, 기준 시각: {}", dueTargets.size(), now);

        int successCount = 0;
        int failCount = 0;

        for (MonitorTarget target : dueTargets) {
            try {
                collectSingleTarget(target);
                successCount++;
            } catch (Exception e) {
                failCount++;
                log.error("수집 실패 - platform: {}, productId: {}, 원인: {}",
                        target.getPlatform(), target.getProductId(), e.getMessage(), e);
            }
        }

        log.info("가격 모니터링 수집 완료 - 성공: {}, 실패: {}, 전체: {}", successCount, failCount, dueTargets.size());
    }

    /**
     * 단일 MonitorTarget에 대해 API를 호출하여 최신 가격 정보를 수집한다.
     * product 단위로 매칭하여 해당 상품만 영속화한다.
     *
     * @param target 수집할 모니터링 대상
     */
    private void collectSingleTarget(MonitorTarget target) {
        String productId = target.getProductId();
        String searchKeyword = target.getSearchKeyword();
        String productUrl = target.getProductUrl();
        Instant now = Instant.now();

        if (target.getPlatform() == Platform.NAVER) {
            collectNaverTarget(target, productId, searchKeyword, productUrl, now);
        } else if (target.getPlatform() == Platform.ALIEXPRESS) {
            collectAliExpressTarget(target, productId, searchKeyword, now);
        } else {
            log.warn("지원하지 않는 platform - {}", target.getPlatform());
            // 지원하지 않는 platform이어도 schedule은 갱신해서 계속 시도하지 않도록 함
            target.markFetched(now);
            monitorTargetRepository.save(target);
        }
    }

    /**
     * NAVER 대상 수집: searchKeyword로 검색 후 productId/productUrl 매칭.
     */
    private void collectNaverTarget(MonitorTarget target, String productId, String searchKeyword,
                                     String productUrl, Instant now) {
        log.info("네이버 수집 - productId: {}, searchKeyword: {}", productId, searchKeyword);

        // 1차 조회: display=100, start=1
        List<NaverShoppingItem> itemsPage1 = externalApiClient.searchNaverProductItems(
                searchKeyword, NAVER_MAX_PAGE_SIZE, 1
        );

        NaverShoppingItem matched = matchNaverItem(itemsPage1, productId, productUrl);

        // 1차 조회에서 못 찾았고 100건이 모두 채워졌으면 2차 조회
        if (matched == null && itemsPage1.size() >= NAVER_MAX_PAGE_SIZE) {
            log.debug("1차 조회에서 미매칭, 2차 조회 시도 - productId: {}", productId);
            List<NaverShoppingItem> itemsPage2 = externalApiClient.searchNaverProductItems(
                    searchKeyword, NAVER_MAX_PAGE_SIZE, 101
            );
            matched = matchNaverItem(itemsPage2, productId, productUrl);
        }

        if (matched != null) {
            log.info("네이버 상품 매칭 성공 - productId: {}, title: {}", productId, matched.title());
            productPersistenceService.saveRefreshedNaverProduct(target, matched, now);
        } else {
            log.warn("네이버 상품 매칭 실패 - productId: {}, searchKeyword: {}", productId, searchKeyword);
        }

        // 수집 완료 시각을 갱신하여 다음 주기에 다시 수집할 수 있도록 함 (실패해도 interval 유지)
        target.markFetched(now);
        monitorTargetRepository.save(target);
    }

    /**
     * ALIEXPRESS 대상 수집: productId detail API 우선, searchKeyword fallback.
     */
    private void collectAliExpressTarget(MonitorTarget target, String productId, String searchKeyword, Instant now) {
        log.info("알리익스프레스 수집 - productId: {}", productId);

        // detail API 우선 시도
        AliexpressProductDetailResponse response = externalApiClient.getAliExpressProductDetail(
                productId, "KRW", "KO", "KR"
        );
        AliExpressShoppingItem product = response.product();

        // detail API 실패 시 search fallback
        if (product == null) {
            log.debug("AliExpress detail API 실패, search fallback 시도 - productId: {}", productId);
            List<AliExpressShoppingItem> items = externalApiClient.searchAliExpressProductItems(
                    searchKeyword, 1, aliExpressDefaultPageSize,
                    null, "KRW", "KO", "KR", null, null
            );
            product = matchAliExpressItem(items, productId, target.getProductUrl());
        }

        if (product != null) {
            log.info("알리익스프레스 상품 매칭 성공 - productId: {}, title: {}",
                    productId, product.product_title());
            productPersistenceService.saveRefreshedAliExpressProduct(target, product, "KRW", now);
        } else {
            log.warn("알리익스프레스 상품 매칭 실패 - productId: {}, searchKeyword: {}", productId, searchKeyword);
        }

        // 수집 완료 시각 갱신
        target.markFetched(now);
        monitorTargetRepository.save(target);
    }

    /**
     * 네이버 상품 목록에서 targetProductId 또는 targetProductUrl로 매칭한다.
     */
    private NaverShoppingItem matchNaverItem(List<NaverShoppingItem> items,
                                              String targetProductId,
                                              String targetProductUrl) {
        if (items == null || items.isEmpty()) {
            return null;
        }

        // productId 우선 매칭
        if (targetProductId != null && !targetProductId.isBlank()) {
            for (NaverShoppingItem item : items) {
                if (targetProductId.equals(item.productId())) {
                    return item;
                }
            }
        }

        // productUrl fallback 매칭 (link 필드와 비교)
        if (targetProductUrl != null && !targetProductUrl.isBlank()) {
            for (NaverShoppingItem item : items) {
                if (targetProductUrl.equals(item.link())) {
                    return item;
                }
            }
        }

        return null;
    }

    /**
     * 알리 상품 목록에서 targetProductId 또는 targetProductUrl로 매칭한다.
     */
    private AliExpressShoppingItem matchAliExpressItem(List<AliExpressShoppingItem> items,
                                                        String targetProductId,
                                                        String targetProductUrl) {
        if (items == null || items.isEmpty()) {
            return null;
        }

        if (targetProductId != null && !targetProductId.isBlank()) {
            for (AliExpressShoppingItem item : items) {
                if (targetProductId.equals(item.product_id())) {
                    return item;
                }
            }
        }

        if (targetProductUrl != null && !targetProductUrl.isBlank()) {
            for (AliExpressShoppingItem item : items) {
                if (targetProductUrl.equals(item.product_detail_url())) {
                    return item;
                }
            }
        }

        // URL에 productId가 포함된 경우 매칭
        if (targetProductId != null && !targetProductId.isBlank()) {
            String productIdToken = "/item/" + targetProductId + ".html";
            for (AliExpressShoppingItem item : items) {
                if (item.product_detail_url() != null && item.product_detail_url().contains(productIdToken)) {
                    return item;
                }
            }
        }

        return null;
    }
}
