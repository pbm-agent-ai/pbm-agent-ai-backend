package com.pbm.price.service;

import com.pbm.price.domain.CategoryNode;
import com.pbm.price.domain.Platform;
import com.pbm.price.repository.CategoryNodeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AliExpressCategoryIdResolverTest {

    @Mock
    private CategoryNodeRepository categoryNodeRepository;

    private AliExpressCategoryIdResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new AliExpressCategoryIdResolver(categoryNodeRepository);
    }

    @Test
    @DisplayName("MOUSE 힌트면 mouse 경로의 가장 깊은 카테고리 ID를 반환한다")
    void resolveCategoryIds_returnsDeepestMatchedCategoryIds() {
        when(categoryNodeRepository.findAllByPlatform(Platform.ALIEXPRESS)).thenReturn(List.of(
                CategoryNode.create(Platform.ALIEXPRESS, "10", "Computer Accessories", null, 0, "Computer Accessories"),
                CategoryNode.create(Platform.ALIEXPRESS, "20", "Keyboards & Mice", "10", 1, "Computer Accessories/Keyboards & Mice"),
                CategoryNode.create(Platform.ALIEXPRESS, "30", "Mouse", "20", 2, "Computer Accessories/Keyboards & Mice/Mouse")
        ));

        assertThat(resolver.resolveCategoryIds("MOUSE")).contains("30");
    }

    @Test
    @DisplayName("알 수 없는 힌트면 빈 값을 반환한다")
    void resolveCategoryIds_returnsEmptyWhenHintUnknown() {
        assertThat(resolver.resolveCategoryIds("UNKNOWN_HINT")).isEmpty();
    }
}
