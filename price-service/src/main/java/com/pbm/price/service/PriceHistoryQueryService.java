package com.pbm.price.service;

import com.pbm.price.domain.MonitorTarget;
import com.pbm.price.domain.MonitoringSubscription;
import com.pbm.price.domain.Platform;
import com.pbm.price.domain.PriceHistory;
import com.pbm.price.dto.response.PriceHistoryResponse;
import com.pbm.price.dto.response.PriceHistoryResponse.PriceHistoryEntryResponse;
import com.pbm.price.exception.SubscriptionNotFoundException;
import com.pbm.price.repository.MonitorTargetRepository;
import com.pbm.price.repository.MonitoringSubscriptionRepository;
import com.pbm.price.repository.PriceHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 가격 히스토리 조회 서비스.
 *
 * 역할: 프론트엔드 차트 렌더링에 필요한 가격 이력 + 통계를 조합하여 반환한다.
 * 동작: subscription → monitor_target → price_history 순서로 데이터를 조회하고,
 *       기간 필터링 + 통계 계산(최저/최고/평균) + 최저가 마킹을 수행한다.
 * 연관: PriceHistoryController, MonitoringSubscriptionRepository,
 *       MonitorTargetRepository, PriceHistoryRepository.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PriceHistoryQueryService {

    private final MonitoringSubscriptionRepository monitoringSubscriptionRepository;
    private final MonitorTargetRepository monitorTargetRepository;
    private final PriceHistoryRepository priceHistoryRepository;

    /**
     * 구독 ID와 기간을 기준으로 가격 히스토리를 조회한다.
     *
     * @param subscriptionId 모니터링 구독 ID
     * @param period         조회 기간 ("7d", "30d", null=전체)
     * @return 가격 히스토리 응답 DTO
     */
    public PriceHistoryResponse getPriceHistory(Long subscriptionId, String period) {
        // 1. 구독 조회
        MonitoringSubscription subscription = monitoringSubscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> new SubscriptionNotFoundException(subscriptionId));

        // 2. MonitorTarget 조회
        MonitorTarget target = monitorTargetRepository
                .findByPlatformAndProductId(subscription.getPlatform(), subscription.getProductId())
                .orElse(null);

        if (target == null) {
            log.info("MonitorTarget이 없어 빈 히스토리 반환 - subscriptionId: {}", subscriptionId);
            return buildEmptyResponse(subscription);
        }

        // 3. 기간 필터링하여 price_history 조회
        List<PriceHistory> histories = queryByPeriod(target, period);

        if (histories.isEmpty()) {
            return buildEmptyResponse(subscription);
        }

        // 4. 통계 계산
        BigDecimal lowestPrice = histories.stream()
                .map(PriceHistory::getCurrentPrice)
                .min(BigDecimal::compareTo)
                .orElse(BigDecimal.ZERO);

        BigDecimal highestPrice = histories.stream()
                .map(PriceHistory::getCurrentPrice)
                .max(BigDecimal::compareTo)
                .orElse(BigDecimal.ZERO);

        BigDecimal averagePrice = histories.stream()
                .map(PriceHistory::getCurrentPrice)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(histories.size()), 0, RoundingMode.HALF_UP);

        BigDecimal currentPrice = histories.get(0).getCurrentPrice(); // 최신순 정렬이므로 첫 번째가 현재가

        // 5. 응답 조립
        String productUrl = target.getProductUrl() != null ? target.getProductUrl() : "";
        String source = subscription.getPlatform() == Platform.URL ? "EXTENSION" : "API";

        List<PriceHistoryEntryResponse> entries = histories.stream()
                .map(h -> new PriceHistoryEntryResponse(
                        h.getCurrentPrice().intValue(),
                        h.getOriginalPrice() != null ? h.getOriginalPrice().intValue() : h.getCurrentPrice().intValue(),
                        h.getCurrency().name(),
                        h.getCurrentPrice().compareTo(lowestPrice) == 0,
                        source,
                        productUrl,
                        h.getCheckedAt().toString()
                ))
                .toList();

        String keyword = subscription.getSnapshotTitle() != null
                ? subscription.getSnapshotTitle()
                : subscription.getSearchKeyword();

        return new PriceHistoryResponse(
                subscription.getId(),
                keyword != null ? keyword : "",
                subscription.getPlatform().name().toLowerCase(),
                subscription.getTargetPrice() != null ? subscription.getTargetPrice().intValue() : 0,
                currentPrice.intValue(),
                lowestPrice.intValue(),
                highestPrice.intValue(),
                averagePrice.intValue(),
                entries,
                histories.size()
        );
    }

    /**
     * 기간에 따라 price_history를 조회한다.
     */
    private List<PriceHistory> queryByPeriod(MonitorTarget target, String period) {
        if (period == null || period.isBlank()) {
            return priceHistoryRepository.findByMonitorTargetOrderByCheckedAtDesc(target);
        }

        Instant since = switch (period) {
            case "7d" -> Instant.now().minus(7, ChronoUnit.DAYS);
            case "30d" -> Instant.now().minus(30, ChronoUnit.DAYS);
            case "90d" -> Instant.now().minus(90, ChronoUnit.DAYS);
            default -> Instant.now().minus(7, ChronoUnit.DAYS);
        };

        return priceHistoryRepository.findByMonitorTargetAndCheckedAtAfterOrderByCheckedAtDesc(target, since);
    }

    /**
     * 이력이 없을 때 빈 응답을 반환한다.
     */
    private PriceHistoryResponse buildEmptyResponse(MonitoringSubscription subscription) {
        String keyword = subscription.getSnapshotTitle() != null
                ? subscription.getSnapshotTitle()
                : subscription.getSearchKeyword();

        int snapshotPrice = subscription.getSnapshotPrice() != null
                ? subscription.getSnapshotPrice().intValue() : 0;

        return new PriceHistoryResponse(
                subscription.getId(),
                keyword != null ? keyword : "",
                subscription.getPlatform().name().toLowerCase(),
                subscription.getTargetPrice() != null ? subscription.getTargetPrice().intValue() : 0,
                snapshotPrice,
                0, 0, 0,
                List.of(),
                0
        );
    }
}
