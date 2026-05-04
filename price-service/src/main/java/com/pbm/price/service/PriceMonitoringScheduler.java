package com.pbm.price.service;

import com.pbm.price.domain.MonitorTarget;
import com.pbm.price.domain.SourceType;
import com.pbm.price.repository.MonitorTargetRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * 공유 수집 기반 가격 모니터링 스케줄러.
 *
 * 역할: 주기적으로 수집 예정 시각이 도래한 MonitorTarget을 찾아
 *       네이버/알리 API를 호출하고 결과를 DB에 영속화한다.
 * 동작: 스케줄러 실행 주기(scheduler-interval-ms, 기본 1분)마다 DB를 폴링하여
 *       nextFetchAt이 현재 시각보다 이전인 MonitorTarget만 선택적으로 수집한다.
 *       개별 대상의 수집 주기(fetch-interval-minutes, 기본 10분)는 MonitorTarget에 설정되며,
 *       스케줄러는 1분마다 확인만 하고 실제 API 호출은 수집 예정 시각이 도래한 대상에만 수행한다.
 *       하나의 대상 수집이 실패해도 전체 배치가 중단되지 않도록 try-catch로 격리한다.
 * 연관: MonitorTargetRepository, NaverShoppingService, AliExpressShoppingService.
 *
 * @author PBM Agent AI
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PriceMonitoringScheduler {

    private final MonitorTargetRepository monitorTargetRepository;
    private final NaverShoppingService naverShoppingService;
    private final AliExpressShoppingService aliExpressShoppingService;

    // 검색 시 한 번에 가져올 기본 결과 수
    @Value("${app.monitoring.default-display:10}")
    private int defaultDisplay;

    // 기본 AliExpress 검색 파라미터
    @Value("${app.monitoring.aliexpress-default-page-size:10}")
    private int aliExpressDefaultPageSize;

    /**
     * 주기적으로 수집 예정 시각이 도래한 모니터링 대상을 수집한다.
     *
     * 동작 흐름:
     * 1. nextFetchAt이 현재 시각 이전인 MonitorTarget 목록을 조회
     * 2. 각 대상의 sourceType에 따라 네이버 또는 알리 API 호출
     * 3. API 호출 결과는 서비스 내부에서 ProductPersistenceService를 통해 DB에 자동 저장
     * 4. 한 대상 실패가 전체 배치를 중단시키지 않도록 예외를 로깅하고 계속 진행
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
                log.error("수집 실패 - sourceType: {}, keyword: {}, 원인: {}",
                        target.getSourceType(), target.getNormalizedKeyword(), e.getMessage(), e);
            }
        }

        log.info("가격 모니터링 수집 완료 - 성공: {}, 실패: {}, 전체: {}", successCount, failCount, dueTargets.size());
    }

    /**
     * 단일 MonitorTarget에 대해 API를 호출하여 최신 가격 정보를 수집한다.
     *
     * @param target 수집할 모니터링 대상
     */
    private void collectSingleTarget(MonitorTarget target) {
        // 정규화 키워드를 원래 검색어로 사용 (DB에는 normalzied 형태로 저장되어 있으므로
        // 검색 품질을 위해 공백이 제거되지 않은 원문을 사용하는 것이 이상적이나,
        // 현재는 정규화된 키워드를 그대로 사용)
        String keyword = target.getNormalizedKeyword();

        if (target.getSourceType() == SourceType.NAVER) {
            log.info("네이버 수집 - 키워드: {}", keyword);
            naverShoppingService.searchProducts(keyword, defaultDisplay);
        } else if (target.getSourceType() == SourceType.ALIEXPRESS) {
            log.info("알리익스프레스 수집 - 키워드: {}", keyword);
            aliExpressShoppingService.searchProducts(
                    keyword, 1, aliExpressDefaultPageSize, null, "KRW", "KO", "KR", null
            );
        } else {
            log.warn("지원하지 않는 sourceType - {}", target.getSourceType());
        }
    }
}