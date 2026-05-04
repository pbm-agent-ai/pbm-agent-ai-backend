package com.pbm.price.service;

import com.pbm.price.domain.MonitorTarget;
import com.pbm.price.domain.PriceHistory;
import com.pbm.price.domain.Product;
import com.pbm.price.domain.SourceType;
import com.pbm.price.dto.response.AliExpressShoppingItem;
import com.pbm.price.dto.response.NaverShoppingItem;
import com.pbm.price.repository.MonitorTargetRepository;
import com.pbm.price.repository.PriceHistoryRepository;
import com.pbm.price.repository.ProductRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ProductPersistenceService 저장 로직 테스트.
 *
 * 실제 JPA + H2 메모리 DB로 엔티티 저장/중복 방지/가격 이력 누적을 검증한다.
 */
@DataJpaTest
@Import(ProductPersistenceService.class)
@TestPropertySource(properties = {
        "app.monitoring.default-fetch-interval-minutes=10"
})
class ProductPersistenceServiceTest {

    @Autowired
    private ProductPersistenceService productPersistenceService;

    @Autowired
    private MonitorTargetRepository monitorTargetRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private PriceHistoryRepository priceHistoryRepository;

    @Test
    @DisplayName("네이버 검색 결과 저장 - 신규 상품이면 monitor target, product, price history가 생성된다")
    void saveNaverSearchResults_createsEntitiesForNewProduct() {
        // given
        NaverShoppingItem item = new NaverShoppingItem(
                "아이폰 15", "1000000", "1200000", "애플공식몰", "https://shopping.example.com/iphone15",
                "naver-1001", "https://img.example.com/iphone15.jpg", "애플", "Apple", "디지털", "휴대폰", "스마트폰", ""
        );

        // when
        productPersistenceService.saveNaverSearchResults("아이폰 15", List.of(item));

        // then
        assertThat(monitorTargetRepository.findAll()).hasSize(1);
        assertThat(productRepository.findAll()).hasSize(1);
        assertThat(priceHistoryRepository.findAll()).hasSize(1);

        Product product = productRepository.findAll().get(0);
        assertThat(product.getSourceType()).isEqualTo(SourceType.NAVER);
        assertThat(product.getExternalProductId()).isEqualTo("naver-1001");
        assertThat(product.getTitle()).isEqualTo("아이폰 15");

        PriceHistory priceHistory = priceHistoryRepository.findAll().get(0);
        assertThat(priceHistory.getCurrentPrice()).hasToString("1000000");
        assertThat(priceHistory.getOriginalPrice()).hasToString("1200000");
        assertThat(priceHistory.getProduct().getId()).isEqualTo(product.getId());
    }

    @Test
    @DisplayName("같은 상품 재조회 - Product는 재사용하고 PriceHistory는 누적 저장한다")
    void saveNaverSearchResults_reusesProductAndAccumulatesHistory() {
        // given
        NaverShoppingItem firstItem = new NaverShoppingItem(
                "아이폰 15", "1000000", "1200000", "애플공식몰", "https://shopping.example.com/iphone15",
                "naver-1001", "https://img.example.com/iphone15.jpg", "애플", "Apple", "디지털", "휴대폰", "스마트폰", ""
        );
        NaverShoppingItem secondItem = new NaverShoppingItem(
                "아이폰 15 최신", "980000", "1200000", "애플공식몰", "https://shopping.example.com/iphone15-new",
                "naver-1001", "https://img.example.com/iphone15-new.jpg", "애플", "Apple", "디지털", "휴대폰", "스마트폰", ""
        );

        // when
        productPersistenceService.saveNaverSearchResults("아이폰 15", List.of(firstItem));
        productPersistenceService.saveNaverSearchResults("아이폰 15", List.of(secondItem));

        // then
        assertThat(productRepository.findAll()).hasSize(1);
        assertThat(priceHistoryRepository.findAll()).hasSize(2);

        Product product = productRepository.findAll().get(0);
        assertThat(product.getTitle()).isEqualTo("아이폰 15 최신");
        assertThat(product.getProductUrl()).isEqualTo("https://shopping.example.com/iphone15-new");
    }

    @Test
    @DisplayName("같은 키워드의 서로 다른 상품 - MonitorTarget은 공유하고 Product는 각각 저장한다")
    void saveAliExpressSearchResults_sharesMonitorTargetForSameKeyword() {
        // given
        AliExpressShoppingItem item1 = new AliExpressShoppingItem(
                "아이폰 케이스", "5.00", "7000", "9000", "Store A", "https://aliexpress.com/item/1",
                "ali-1", "https://img.example.com/ali1.jpg", "95", "10", "케이스"
        );
        AliExpressShoppingItem item2 = new AliExpressShoppingItem(
                "아이폰 충전기", "8.00", "11000", "14000", "Store B", "https://aliexpress.com/item/2",
                "ali-2", "https://img.example.com/ali2.jpg", "92", "11", "충전기"
        );

        // when
        productPersistenceService.saveAliExpressSearchResults("아이폰", "KRW", List.of(item1, item2));

        // then
        assertThat(monitorTargetRepository.findAll()).hasSize(1);
        assertThat(productRepository.findAll()).hasSize(2);
        assertThat(priceHistoryRepository.findAll()).hasSize(2);

        MonitorTarget monitorTarget = monitorTargetRepository.findAll().get(0);
        assertThat(monitorTarget.getSourceType()).isEqualTo(SourceType.ALIEXPRESS);
        assertThat(monitorTarget.getNormalizedKeyword()).isEqualTo("아이폰");
    }
}
