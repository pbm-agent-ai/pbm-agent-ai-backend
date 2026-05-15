package com.pbm.price.service;

import com.pbm.price.domain.CurrencyType;
import com.pbm.price.domain.MonitoringSubscription;
import com.pbm.price.domain.MonitoringSubscriptionStatus;
import com.pbm.price.domain.Platform;
import com.pbm.price.dto.event.ProductCandidateDto;
import com.pbm.price.repository.MonitoringSubscriptionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MonitoringSubscriptionService의 createOrUpdateFromSelection 로직을 검증하는 테스트.
 *
 * 역할: 개별 후보 상품 DTO로부터 구독 생성/갱신/파싱 실패를 검증한다.
 * 동작: @DataJpaTest + @Import로 Service와 Repository를 함께 로드하여
 *       실제 JPA 저장/조회 흐름까지 통합 검증한다.
 * 연관: MonitoringSubscriptionService, MonitoringSubscriptionRepository, ProductCandidateDto.
 */
@DataJpaTest
@Import(MonitoringSubscriptionService.class)
class MonitoringSubscriptionServiceTest {

    @Autowired
    private MonitoringSubscriptionService monitoringSubscriptionService;

    @Autowired
    private MonitoringSubscriptionRepository monitoringSubscriptionRepository;

    private static final long USER_ID = 1L;
    private static final String COMMAND_ID = UUID.randomUUID().toString();
    private static final int TARGET_PRICE = 45000;
    private static final String INTENT = "PRICE_TRACK";

    /**
     * 테스트용 ProductCandidateDto를 생성한다.
     */
    private ProductCandidateDto createCandidate(String platform, String currency, String lprice) {
        return new ProductCandidateDto(
                "prod-1",                                     // productId
                "테스트 상품",                                  // title
                lprice,                                       // lprice
                "테스트몰",                                    // mallName
                "https://example.com/product/prod-1",         // productUrl
                currency,                                     // currency
                platform,                                     // platform
                "테스트 키워드"                                // searchKeyword
        );
    }

    @Test
    @DisplayName("신규 구독 생성: 존재하지 않는 userId+platform+productId 조합이면 새 구독을 생성한다")
    void createOrUpdateFromSelection_createsNewSubscription_whenNotExists() {
        // given
        ProductCandidateDto candidate = createCandidate("NAVER", "KRW", "50000");

        // when
        MonitoringSubscription result = monitoringSubscriptionService.createOrUpdateFromSelection(
                USER_ID, COMMAND_ID, TARGET_PRICE, INTENT, candidate);

        // then: 새 구독이 생성되었는지 확인
        assertThat(result.getId()).isNotNull();
        assertThat(result.getUserId()).isEqualTo(USER_ID);
        assertThat(result.getPlatform()).isEqualTo(Platform.NAVER);
        assertThat(result.getProductId()).isEqualTo("prod-1");
        assertThat(result.getCommandId()).isEqualTo(COMMAND_ID);
        assertThat(result.getProductUrl()).isEqualTo("https://example.com/product/prod-1");
        assertThat(result.getSnapshotTitle()).isEqualTo("테스트 상품");
        assertThat(result.getSnapshotPrice()).isEqualByComparingTo(BigDecimal.valueOf(50000));
        assertThat(result.getSearchKeyword()).isEqualTo("테스트 키워드");
        assertThat(result.getTargetPrice()).isEqualByComparingTo(BigDecimal.valueOf(TARGET_PRICE));
        assertThat(result.getCurrency()).isEqualTo(CurrencyType.KRW);
        assertThat(result.getIntent()).isEqualTo(INTENT);
        assertThat(result.getStatus()).isEqualTo(MonitoringSubscriptionStatus.ACTIVE);
        assertThat(result.getConsecutiveMissCount()).isZero();
        assertThat(result.getCheckIntervalMinutes()).isEqualTo(10); // 기본 interval
        assertThat(result.getLastCheckedAt()).isNotNull();
        assertThat(result.getNextCheckAt()).isAfter(result.getLastCheckedAt());
    }

    @Test
    @DisplayName("기존 구독 갱신: 동일 userId+platform+productId가 존재하면 최신 정보로 갱신된다")
    void createOrUpdateFromSelection_updatesExistingSubscription_whenAlreadyExists() {
        // given: 첫 번째 후보로 신규 구독 생성
        ProductCandidateDto firstCandidate = createCandidate("NAVER", "KRW", "50000");
        MonitoringSubscription firstSubscription = monitoringSubscriptionService.createOrUpdateFromSelection(
                USER_ID, COMMAND_ID, TARGET_PRICE, INTENT, firstCandidate);
        Long originalId = firstSubscription.getId();

        // 두 번째 후보는 같은 userId, platform, productId지만 다른 정보로 요청
        ProductCandidateDto secondCandidate = new ProductCandidateDto(
                "prod-1",                                      // 동일 productId
                "갱신된 상품명",                                // 변경된 title
                "48000",                                       // 변경된 lprice
                "갱신된몰",                                    // 변경된 mallName
                "https://example.com/product/prod-1-updated",  // 변경된 URL
                "KRW",                                         // 동일 currency
                "NAVER",                                       // 동일 platform
                "갱신된 키워드"                                 // 변경된 searchKeyword
        );

        // when
        MonitoringSubscription result = monitoringSubscriptionService.createOrUpdateFromSelection(
                USER_ID, UUID.randomUUID().toString(), 40000, "AUTO_PURCHASE", secondCandidate);

        // then: ID는 동일하고, 정보는 최신 candidate 기준으로 갱신되어야 함
        assertThat(result.getId()).isEqualTo(originalId);
        assertThat(result.getProductUrl()).isEqualTo("https://example.com/product/prod-1-updated");
        assertThat(result.getSnapshotTitle()).isEqualTo("갱신된 상품명");
        assertThat(result.getSnapshotPrice()).isEqualByComparingTo(BigDecimal.valueOf(48000));
        assertThat(result.getSearchKeyword()).isEqualTo("갱신된 키워드");
        assertThat(result.getTargetPrice()).isEqualByComparingTo(BigDecimal.valueOf(40000));
        assertThat(result.getIntent()).isEqualTo("AUTO_PURCHASE");
        assertThat(result.getCurrency()).isEqualTo(CurrencyType.KRW);
        assertThat(result.getStatus()).isEqualTo(MonitoringSubscriptionStatus.ACTIVE);
        assertThat(result.getConsecutiveMissCount()).isZero();
    }

    @Test
    @DisplayName("기존 구독이 TRIGGERED여도 새 요청이 오면 ACTIVE로 재시작한다")
    void createOrUpdateFromSelection_reactivatesExistingSubscription_whenTriggered() {
        // given
        ProductCandidateDto candidate = createCandidate("NAVER", "KRW", "50000");
        MonitoringSubscription existing = monitoringSubscriptionService.createOrUpdateFromSelection(
                USER_ID, COMMAND_ID, TARGET_PRICE, INTENT, candidate);
        existing.changeStatus(MonitoringSubscriptionStatus.TRIGGERED);
        existing.markChecked(Instant.now());
        monitoringSubscriptionRepository.save(existing);

        // when
        MonitoringSubscription result = monitoringSubscriptionService.createOrUpdateFromSelection(
                USER_ID, UUID.randomUUID().toString(), 40000, "AUTO_PURCHASE", candidate);

        // then
        assertThat(result.getId()).isEqualTo(existing.getId());
        assertThat(result.getStatus()).isEqualTo(MonitoringSubscriptionStatus.ACTIVE);
        assertThat(result.getTargetPrice()).isEqualByComparingTo(BigDecimal.valueOf(40000));
        assertThat(result.getConsecutiveMissCount()).isZero();
    }

    @Test
    @DisplayName("동일 사용자+플랫폼+상품 구독이 있으면 중복 후보로 반환한다")
    void findDuplicateSelections_returnsExistingProductCandidates() {
        // given
        ProductCandidateDto candidate = createCandidate("NAVER", "KRW", "50000");
        monitoringSubscriptionService.createOrUpdateFromSelection(USER_ID, COMMAND_ID, TARGET_PRICE, INTENT, candidate);

        ProductCandidateDto otherCandidate = new ProductCandidateDto(
                "prod-2", "다른 상품", "51000", "다른몰", "https://example.com/product/prod-2", "KRW", "NAVER", "다른 키워드"
        );

        // when
        var duplicates = monitoringSubscriptionService.findDuplicateSelections(USER_ID, java.util.List.of(candidate, otherCandidate));

        // then
        assertThat(duplicates).containsExactly(candidate);
    }

    @Test
    @DisplayName("잘못된 platform 값: 지원하지 않는 플랫폼 문자열이면 IllegalArgumentException이 발생한다")
    void createOrUpdateFromSelection_throwsException_forInvalidPlatform() {
        // given: 잘못된 platform 값
        ProductCandidateDto candidate = createCandidate("INVALID_PLATFORM", "KRW", "50000");

        // when & then
        assertThatThrownBy(() -> monitoringSubscriptionService.createOrUpdateFromSelection(
                USER_ID, COMMAND_ID, TARGET_PRICE, INTENT, candidate))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("지원하지 않는 플랫폼값");
    }

    @Test
    @DisplayName("잘못된 currency 값: 지원하지 않는 통화 문자열이면 IllegalArgumentException이 발생한다")
    void createOrUpdateFromSelection_throwsException_forInvalidCurrency() {
        // given: 잘못된 currency 값
        ProductCandidateDto candidate = createCandidate("NAVER", "INVALID_CURRENCY", "50000");

        // when & then
        assertThatThrownBy(() -> monitoringSubscriptionService.createOrUpdateFromSelection(
                USER_ID, COMMAND_ID, TARGET_PRICE, INTENT, candidate))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("지원하지 않는 currency 값");
    }

    @Test
    @DisplayName("잘못된 lprice 값: 숫자로 파싱할 수 없는 가격 문자열이면 IllegalArgumentException이 발생한다")
    void createOrUpdateFromSelection_throwsException_forInvalidLprice() {
        // given: 숫자가 아닌 lprice 값
        ProductCandidateDto candidate = createCandidate("NAVER", "KRW", "not-a-number");

        // when & then
        assertThatThrownBy(() -> monitoringSubscriptionService.createOrUpdateFromSelection(
                USER_ID, COMMAND_ID, TARGET_PRICE, INTENT, candidate))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("유효하지 않은 lprice 값");
    }
}
