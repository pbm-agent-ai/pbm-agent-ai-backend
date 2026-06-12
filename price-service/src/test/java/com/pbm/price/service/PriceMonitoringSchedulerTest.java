package com.pbm.price.service;

import com.pbm.price.client.ExternalApiClient;
import com.pbm.price.domain.MonitorTarget;
import com.pbm.price.domain.Platform;
import com.pbm.price.dto.response.NaverShoppingItem;
import com.pbm.price.repository.MonitorTargetRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PriceMonitoringScheduler 단위 테스트 (product 기반).
 *
 * 검증 내용:
 * - 수집 예정 시각이 도래한 대상만 선택적으로 수집하는지 확인
 * - 네이버 대상: searchKeyword로 검색 후 productId 매칭하여 단건 저장
 * - 알리익스프레스 대상: detail API 우선, search fallback 후 저장
 * - 한 대상 실패가 전체 배치를 중단시키지 않는지 확인
 * - 수집 대상이 없으면 API를 호출하지 않는지 확인
 */
@ExtendWith(MockitoExtension.class)
class PriceMonitoringSchedulerTest {

    @Mock
    private MonitorTargetRepository monitorTargetRepository;

    @Mock
    private ExternalApiClient externalApiClient;

    @Mock
    private ProductPersistenceService productPersistenceService;

    @Mock
    private SubscriptionMonitoringService subscriptionMonitoringService;

    @InjectMocks
    private PriceMonitoringScheduler scheduler;

    private Instant baseTime;

    @BeforeEach
    void setUp() {
        baseTime = Instant.now();
        // @Value 필드는 @InjectMocks로 주입되지 않으므로 ReflectionTestUtils로 직접 설정
        ReflectionTestUtils.setField(scheduler, "aliExpressDefaultPageSize", 10);
    }

    @Test
    @DisplayName("수집 예정 대상이 없으면 API를 호출하지 않는다")
    void collectDueTargets_noTargets_doesNotCallApi() {
        // given: 수집 예정 대상이 없음
        when(monitorTargetRepository.findByNextFetchAtBefore(any()))
                .thenReturn(List.of());

        // when: 스케줄러 실행
        scheduler.collectDueTargets();

        // then: 어떤 API도 호출하지 않음
        verify(externalApiClient, never()).searchNaverProductItems(anyString(), anyInt(), anyInt());
        verify(externalApiClient, never()).getAliExpressProductDetail(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("네이버 대상이 있으면 searchKeyword로 검색 후 productId 매칭하여 저장한다")
    void collectDueTargets_naverTarget_callsNaverApi() {
        // given: 네이버 수집 대상 1건 (product 단위)
        MonitorTarget naverTarget = MonitorTarget.create(
                Platform.NAVER, "naver-prod-123", "갤럭시s24", "https://shopping.naver.com/galaxy24", 10
        );
        naverTarget.markFetched(baseTime.minusSeconds(600));
        when(monitorTargetRepository.findByNextFetchAtBefore(any()))
                .thenReturn(List.of(naverTarget));

        // 네이버 API: 1차 조회 시 매칭되는 상품 반환
        NaverShoppingItem matchedItem = new NaverShoppingItem(
                "갤럭시 S24", "1200000", "1400000", "삼성공식몰", "https://shopping.naver.com/galaxy24",
                "naver-prod-123", "https://img.example.com/galaxy24.jpg", "삼성", "전자", "디지털", "휴대폰", "", ""
        );
        when(externalApiClient.searchNaverProductItems(anyString(), anyInt(), eq(1)))
                .thenReturn(List.of(matchedItem));

        // when: 스케줄러 실행
        scheduler.collectDueTargets();

        // then: 네이버 API가 호출되고 매칭된 상품이 저장됨
        verify(externalApiClient).searchNaverProductItems("갤럭시s24", 100, 1);
        verify(productPersistenceService).saveRefreshedNaverProduct(eq(naverTarget), eq(matchedItem), any());
    }

    @Test
    @DisplayName("알리익스프레스 대상은 URL 모니터링 전환으로 API 호출 없이 건너뛴다")
    void collectDueTargets_aliExpressTarget_skipsApiCall() {
        // given: 알리 수집 대상 1건 (URL 모니터링 전환 후 스케줄러에서 스킵)
        MonitorTarget aliTarget = MonitorTarget.create(
                Platform.ALIEXPRESS, "ali-prod-456", "airpodspro", null, 10
        );
        aliTarget.markFetched(baseTime.minusSeconds(600));
        when(monitorTargetRepository.findByNextFetchAtBefore(any()))
                .thenReturn(List.of(aliTarget));

        // when: 스케줄러 실행
        scheduler.collectDueTargets();

        // then: AliExpress API는 호출하지 않고, 수집 시각만 갱신하여 반복 폴링 방지
        verify(externalApiClient, never()).getAliExpressProductDetail(anyString(), anyString(), anyString(), anyString());
        verify(externalApiClient, never()).searchAliExpressProductItems(
                anyString(), anyInt(), anyInt(), any(), anyString(), anyString(), anyString(), any(), any()
        );
        verify(monitorTargetRepository).save(aliTarget);
    }

    @Test
    @DisplayName("한 대상 수집 실패해도 나머지 대상은 계속 수집한다")
    void collectDueTargets_partialFailure_continuesProcessing() {
        // given: 네이버 대상(실패) + 네이버 대상(성공)
        MonitorTarget failTarget = MonitorTarget.create(
                Platform.NAVER, "naver-fail", "아이폰15", null, 10
        );
        failTarget.markFetched(baseTime.minusSeconds(600));

        MonitorTarget successTarget = MonitorTarget.create(
                Platform.NAVER, "naver-success", "갤럭시탭", "https://shopping.naver.com/tab", 10
        );
        successTarget.markFetched(baseTime.minusSeconds(600));

        when(monitorTargetRepository.findByNextFetchAtBefore(any()))
                .thenReturn(List.of(failTarget, successTarget));

        // 첫 번째 네이버 검색은 예외 발생 (아이폰15 키워드)
        when(externalApiClient.searchNaverProductItems(eq("아이폰15"), anyInt(), eq(1)))
                .thenThrow(new RuntimeException("API 호출 실패"));

        // 두 번째 네이버 검색은 성공 (갤럭시탭 키워드)
        NaverShoppingItem matchedItem = new NaverShoppingItem(
                "갤럭시 탭 S9", "800000", "900000", "삼성공식몰", "https://shopping.naver.com/tab",
                "naver-success", "https://img.example.com/tab.jpg", "삼성", "전자", "디지털", "태블릿", "", ""
        );
        when(externalApiClient.searchNaverProductItems(eq("갤럭시탭"), anyInt(), eq(1)))
                .thenReturn(List.of(matchedItem));

        // when: 스케줄러 실행
        scheduler.collectDueTargets();

        // then: 첫 번째 실패 후에도 두 번째 네이버 대상은 정상 수집됨
        verify(productPersistenceService).saveRefreshedNaverProduct(eq(successTarget), eq(matchedItem), any());
    }
}
