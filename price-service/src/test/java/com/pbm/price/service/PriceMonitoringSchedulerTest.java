package com.pbm.price.service;

import com.pbm.price.domain.MonitorTarget;
import com.pbm.price.domain.SourceType;
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
 * PriceMonitoringScheduler 단위 테스트.
 *
 * 검증 내용:
 * - 수집 예정 시각이 도래한 대상만 선택적으로 수집하는지 확인
 * - 네이버/알리익스프레스 대상이 각각 올바른 서비스로 라우팅되는지 확인
 * - 한 대상 실패가 전체 배치를 중단시키지 않는지 확인
 * - 수집 대상이 없으면 API를 호출하지 않는지 확인
 */
@ExtendWith(MockitoExtension.class)
class PriceMonitoringSchedulerTest {

    @Mock
    private MonitorTargetRepository monitorTargetRepository;

    @Mock
    private NaverShoppingService naverShoppingService;

    @Mock
    private AliExpressShoppingService aliExpressShoppingService;

    @InjectMocks
    private PriceMonitoringScheduler scheduler;

    private Instant baseTime;

    @BeforeEach
    void setUp() {
        baseTime = Instant.now();
        // @Value 필드는 @InjectMocks로 주입되지 않으므로 ReflectionTestUtils로 직접 설정
        ReflectionTestUtils.setField(scheduler, "defaultDisplay", 10);
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
        verify(naverShoppingService, never()).searchProducts(anyString(), anyInt());
        verify(aliExpressShoppingService, never()).searchProducts(
                anyString(), anyInt(), anyInt(), anyString(), anyString(), anyString(), anyString(), anyString()
        );
    }

    @Test
    @DisplayName("네이버 대상이 있으면 네이버 API를 호출한다")
    void collectDueTargets_naverTarget_callsNaverApi() {
        // given: 네이버 수집 대상 1건
        MonitorTarget naverTarget = MonitorTarget.create(SourceType.NAVER, "갤럭시s24", 10);
        naverTarget.markFetched(baseTime.minusSeconds(600));
        when(monitorTargetRepository.findByNextFetchAtBefore(any()))
                .thenReturn(List.of(naverTarget));

        // when: 스케줄러 실행
        scheduler.collectDueTargets();

        // then: 네이버 API가 호출됨
        verify(naverShoppingService).searchProducts("갤럭시s24", 10);
    }

    @Test
    @DisplayName("알리익스프레스 대상이 있으면 알리 API를 호출한다")
    void collectDueTargets_aliExpressTarget_callsAliExpressApi() {
        // given: 알리 수집 대상 1건
        MonitorTarget aliTarget = MonitorTarget.create(SourceType.ALIEXPRESS, "airpodspro", 10);
        aliTarget.markFetched(baseTime.minusSeconds(600));
        when(monitorTargetRepository.findByNextFetchAtBefore(any()))
                .thenReturn(List.of(aliTarget));

        // when: 스케줄러 실행
        scheduler.collectDueTargets();

        // then: 알리 API가 호출됨
        verify(aliExpressShoppingService).searchProducts(
                eq("airpodspro"), eq(1), eq(10), eq(null), eq("KRW"), eq("KO"), eq("KR"), eq(null)
        );
    }

    @Test
    @DisplayName("한 대상 수집 실패해도 나머지 대상은 계속 수집한다")
    void collectDueTargets_partialFailure_continuesProcessing() {
        // given: 네이버 대상(실패) + 알리 대상(성공)
        MonitorTarget naverTarget = MonitorTarget.create(SourceType.NAVER, "아이폰15", 10);
        naverTarget.markFetched(baseTime.minusSeconds(600));
        MonitorTarget aliTarget = MonitorTarget.create(SourceType.ALIEXPRESS, "airpods", 10);
        aliTarget.markFetched(baseTime.minusSeconds(600));

        when(monitorTargetRepository.findByNextFetchAtBefore(any()))
                .thenReturn(List.of(naverTarget, aliTarget));
        // 네이버 API는 예외 발생
        when(naverShoppingService.searchProducts("아이폰15", 10))
                .thenThrow(new RuntimeException("API 호출 실패"));

        // when: 스케줄러 실행
        scheduler.collectDueTargets();

        // then: 네이버 실패 후에도 알리 API는 정상 호출됨
        verify(aliExpressShoppingService).searchProducts(
                eq("airpods"), eq(1), eq(10), eq(null), eq("KRW"), eq("KO"), eq("KR"), eq(null)
        );
    }
}