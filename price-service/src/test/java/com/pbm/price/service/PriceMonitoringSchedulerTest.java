package com.pbm.price.service;

import com.pbm.price.client.ExternalApiClient;
import com.pbm.price.domain.MonitorTarget;
import com.pbm.price.domain.Platform;
import com.pbm.price.dto.response.AliExpressShoppingItem;
import com.pbm.price.dto.response.AliexpressProductDetailResponse;
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
    @DisplayName("알리익스프레스 대상이 있으면 detail API 우선, 실패 시 search fallback")
    void collectDueTargets_aliExpressTarget_callsDetailApiFirst() {
        // given: 알리 수집 대상 1건
        MonitorTarget aliTarget = MonitorTarget.create(
                Platform.ALIEXPRESS, "ali-prod-456", "airpodspro", null, 10
        );
        aliTarget.markFetched(baseTime.minusSeconds(600));
        when(monitorTargetRepository.findByNextFetchAtBefore(any()))
                .thenReturn(List.of(aliTarget));

        // detail API 실패
        when(externalApiClient.getAliExpressProductDetail("ali-prod-456", "KRW", "KO", "KR"))
                .thenReturn(new AliexpressProductDetailResponse(null));

        // search fallback 성공
        AliExpressShoppingItem fallbackItem = new AliExpressShoppingItem(
                "Apple AirPods Pro", "199.00", "250000", "290000", "Apple Store",
                "https://aliexpress.com/item/ali-prod-456", "ali-prod-456",
                "https://img.example.com/airpods.jpg", "95", "1", "Electronics", "2", "Audio"
        );
        when(externalApiClient.searchAliExpressProductItems(
                anyString(), anyInt(), anyInt(), any(), anyString(), anyString(), anyString(), any(), any()
        )).thenReturn(List.of(fallbackItem));

        // when: 스케줄러 실행
        scheduler.collectDueTargets();

        // then: detail API 호출 후 search fallback → 매칭된 상품 저장
        verify(externalApiClient).getAliExpressProductDetail("ali-prod-456", "KRW", "KO", "KR");
        verify(productPersistenceService).saveRefreshedAliExpressProduct(eq(aliTarget), eq(fallbackItem), eq("KRW"), any());
    }

    @Test
    @DisplayName("한 대상 수집 실패해도 나머지 대상은 계속 수집한다")
    void collectDueTargets_partialFailure_continuesProcessing() {
        // given: 네이버 대상(실패) + 알리 대상(성공)
        MonitorTarget naverTarget = MonitorTarget.create(
                Platform.NAVER, "naver-fail", "아이폰15", null, 10
        );
        naverTarget.markFetched(baseTime.minusSeconds(600));

        MonitorTarget aliTarget = MonitorTarget.create(
                Platform.ALIEXPRESS, "ali-success", "airpods", null, 10
        );
        aliTarget.markFetched(baseTime.minusSeconds(600));

        when(monitorTargetRepository.findByNextFetchAtBefore(any()))
                .thenReturn(List.of(naverTarget, aliTarget));

        // 네이버 검색은 예외 발생
        when(externalApiClient.searchNaverProductItems(anyString(), anyInt(), eq(1)))
                .thenThrow(new RuntimeException("API 호출 실패"));

        // 알리 detail API 성공
        AliExpressShoppingItem aliItem = new AliExpressShoppingItem(
                "AirPods", "149.00", "200000", "230000", "Apple Store",
                "https://aliexpress.com/item/ali-success", "ali-success",
                "https://img.example.com/airpods.jpg", "95", "1", "Electronics", "2", "Audio"
        );
        when(externalApiClient.getAliExpressProductDetail("ali-success", "KRW", "KO", "KR"))
                .thenReturn(new AliexpressProductDetailResponse(aliItem));

        // when: 스케줄러 실행
        scheduler.collectDueTargets();

        // then: 네이버 실패 후에도 알리 API는 정상 호출됨
        verify(productPersistenceService).saveRefreshedAliExpressProduct(eq(aliTarget), eq(aliItem), eq("KRW"), any());
    }
}
