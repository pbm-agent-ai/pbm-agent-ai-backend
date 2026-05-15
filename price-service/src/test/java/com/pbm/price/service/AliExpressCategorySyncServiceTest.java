package com.pbm.price.service;

import com.pbm.price.client.ExternalApiClient;
import com.pbm.price.domain.CategoryNode;
import com.pbm.price.domain.Platform;
import com.pbm.price.dto.response.AliExpressCategoryItem;
import com.pbm.price.repository.CategoryNodeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * AliExpressCategorySyncService 단위 테스트
 *
 * ExternalApiClient와 CategoryNodeRepository를 Mock하여
 * depth/categoryPath 계산 및 upsert 로직을 집중 검증한다.
 *
 * 주의: @Transactional은 Spring 컨텍스트가 없으므로 동작하지 않는다.
 * 트랜잭션 동작은 별도 통합 테스트에서 검증해야 한다.
 */
@ExtendWith(MockitoExtension.class)
class AliExpressCategorySyncServiceTest {

    @Mock
    private ExternalApiClient externalApiClient;

    @Mock
    private CategoryNodeRepository categoryNodeRepository;

    @Captor
    private ArgumentCaptor<List<CategoryNode>> nodeListCaptor;

    private AliExpressCategorySyncService syncService;

    @BeforeEach
    void setUp() {
        syncService = new AliExpressCategorySyncService(externalApiClient, categoryNodeRepository);
    }

    @Test
    @DisplayName("syncCategories - 정상적인 3단계 카테고리 depth/path 계산 및 upsert")
    void syncCategories_createsNodesWithCorrectDepthAndPath() {
        // given - 3단계 카테고리 계층 구조
        AliExpressCategoryItem root = new AliExpressCategoryItem("100", "전자기기", null);
        AliExpressCategoryItem child = new AliExpressCategoryItem("101", "오디오", "100");
        AliExpressCategoryItem grandchild = new AliExpressCategoryItem("102", "스피커", "101");
        List<AliExpressCategoryItem> items = List.of(root, child, grandchild);

        // 기존 노드는 없음 (신규 생성)
        when(externalApiClient.fetchAliExpressCategories()).thenReturn(items);
        when(categoryNodeRepository.findAllByPlatform(Platform.ALIEXPRESS)).thenReturn(List.of());

        // when
        syncService.syncCategories();

        // then - saveAll이 3개의 노드로 호출되었는지 확인
        verify(categoryNodeRepository).saveAll(nodeListCaptor.capture());
        List<CategoryNode> savedNodes = nodeListCaptor.getValue();

        assertThat(savedNodes).hasSize(3);

        // root 검증: depth=0, path=카테고리명
        CategoryNode rootNode = findNode(savedNodes, "100");
        assertThat(rootNode.getPlatform()).isEqualTo(Platform.ALIEXPRESS);
        assertThat(rootNode.getSourceCategoryId()).isEqualTo("100");
        assertThat(rootNode.getSourceCategoryName()).isEqualTo("전자기기");
        assertThat(rootNode.getParentSourceCategoryId()).isNull();
        assertThat(rootNode.getDepth()).isEqualTo(0);
        assertThat(rootNode.getCategoryPath()).isEqualTo("전자기기");

        // child 검증: depth=1, path=전자기기/오디오
        CategoryNode childNode = findNode(savedNodes, "101");
        assertThat(childNode.getSourceCategoryId()).isEqualTo("101");
        assertThat(childNode.getSourceCategoryName()).isEqualTo("오디오");
        assertThat(childNode.getParentSourceCategoryId()).isEqualTo("100");
        assertThat(childNode.getDepth()).isEqualTo(1);
        assertThat(childNode.getCategoryPath()).isEqualTo("전자기기/오디오");

        // grandchild 검증: depth=2, path=전자기기/오디오/스피커
        CategoryNode grandchildNode = findNode(savedNodes, "102");
        assertThat(grandchildNode.getSourceCategoryId()).isEqualTo("102");
        assertThat(grandchildNode.getSourceCategoryName()).isEqualTo("스피커");
        assertThat(grandchildNode.getParentSourceCategoryId()).isEqualTo("101");
        assertThat(grandchildNode.getDepth()).isEqualTo(2);
        assertThat(grandchildNode.getCategoryPath()).isEqualTo("전자기기/오디오/스피커");
    }

    @Test
    @DisplayName("syncCategories - root parent가 null/blank/'0'인 경우 depth=0")
    void syncCategories_handlesAllRootParentIndicators() {
        // given - 다양한 root parent 표현
        AliExpressCategoryItem nullParent = new AliExpressCategoryItem("1", "RootNull", null);
        AliExpressCategoryItem blankParent = new AliExpressCategoryItem("2", "RootBlank", "");
        AliExpressCategoryItem zeroParent = new AliExpressCategoryItem("3", "RootZero", "0");
        List<AliExpressCategoryItem> items = List.of(nullParent, blankParent, zeroParent);

        when(externalApiClient.fetchAliExpressCategories()).thenReturn(items);
        when(categoryNodeRepository.findAllByPlatform(Platform.ALIEXPRESS)).thenReturn(List.of());

        // when
        syncService.syncCategories();

        // then - 모두 depth=0, path=자기 이름
        verify(categoryNodeRepository).saveAll(nodeListCaptor.capture());
        List<CategoryNode> savedNodes = nodeListCaptor.getValue();

        assertThat(savedNodes).hasSize(3);
        for (CategoryNode node : savedNodes) {
            assertThat(node.getDepth()).isEqualTo(0);
            assertThat(node.getCategoryPath()).isEqualTo(node.getSourceCategoryName());
            // parentSourceCategoryId는 null로 정규화됨
            assertThat(node.getParentSourceCategoryId()).isNull();
        }
    }

    @Test
    @DisplayName("syncCategories - 빈 카테고리 목록이면 동기화 생략")
    void syncCategories_skipsSync_whenItemsEmpty() {
        // given
        when(externalApiClient.fetchAliExpressCategories()).thenReturn(List.of());

        // when
        syncService.syncCategories();

        // then - repository 호출 없음 (saveAll, findAllByPlatform 모두 호출 안 됨)
        verify(categoryNodeRepository, never()).findAllByPlatform(any());
        verify(categoryNodeRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("syncCategories - 기존 노드가 있으면 updateSnapshot으로 갱신")
    void syncCategories_updatesExistingNodes() {
        // given - 기존 노드 1건 존재
        CategoryNode existingNode = CategoryNode.create(
                Platform.ALIEXPRESS, "100", "옛날이름", null, 0, "옛날이름"
        );
        // 기존 createdAt을 고정하기 위해 리플렉션 등을 사용하지 않고, updateSnapshot으로 갱신되는지만 검증

        AliExpressCategoryItem item = new AliExpressCategoryItem("100", "새이름", null);
        List<AliExpressCategoryItem> items = List.of(item);

        when(externalApiClient.fetchAliExpressCategories()).thenReturn(items);
        when(categoryNodeRepository.findAllByPlatform(Platform.ALIEXPRESS))
                .thenReturn(List.of(existingNode));

        // when
        syncService.syncCategories();

        // then - saveAll이 호출되고, 기존 노드의 값이 갱신됨
        verify(categoryNodeRepository).saveAll(nodeListCaptor.capture());
        List<CategoryNode> savedNodes = nodeListCaptor.getValue();

        assertThat(savedNodes).hasSize(1);
        CategoryNode saved = savedNodes.get(0);
        // updateSnapshot으로 갱신되었는지 검증
        assertThat(saved.getSourceCategoryName()).isEqualTo("새이름");
        assertThat(saved.getDepth()).isEqualTo(0);
        assertThat(saved.getCategoryPath()).isEqualTo("새이름");
        // id가 1L이 아닐 수 있지만, 같은 객체 참조인지 확인
        assertThat(saved).isSameAs(existingNode);
    }

    @Test
    @DisplayName("syncCategories - 부모가 없는 카테고리는 경고 로그를 남기고 현재까지를 최상위로 간주")
    void syncCategories_usesPartialPath_whenParentNotFound() {
        // given - 부모 ID("999")가 items 목록에 없음
        AliExpressCategoryItem orphan = new AliExpressCategoryItem("200", "고아카테고리", "999");
        List<AliExpressCategoryItem> items = List.of(orphan);

        when(externalApiClient.fetchAliExpressCategories()).thenReturn(items);
        when(categoryNodeRepository.findAllByPlatform(Platform.ALIEXPRESS)).thenReturn(List.of());

        // when
        syncService.syncCategories();

        // then - 부모를 찾을 수 없으면 path는 자기 이름만 남고,
        // 실제로는 부모를 1단계 타고 올라가려 했으므로 depth는 1로 계산된다.
        verify(categoryNodeRepository).saveAll(nodeListCaptor.capture());
        List<CategoryNode> savedNodes = nodeListCaptor.getValue();

        assertThat(savedNodes).hasSize(1);
        assertThat(savedNodes.get(0).getDepth()).isEqualTo(1);
        assertThat(savedNodes.get(0).getCategoryPath()).isEqualTo("고아카테고리");
    }

    @Test
    @DisplayName("syncCategories - 순환 참조 데이터는 IllegalStateException 발생")
    void syncCategories_throwsException_onCyclicData() {
        // given - A → B → C → A (순환 참조)
        AliExpressCategoryItem itemA = new AliExpressCategoryItem("1", "A", "3");
        AliExpressCategoryItem itemB = new AliExpressCategoryItem("2", "B", "1");
        AliExpressCategoryItem itemC = new AliExpressCategoryItem("3", "C", "2");
        List<AliExpressCategoryItem> items = List.of(itemA, itemB, itemC);

        when(externalApiClient.fetchAliExpressCategories()).thenReturn(items);
        when(categoryNodeRepository.findAllByPlatform(Platform.ALIEXPRESS)).thenReturn(List.of());

        // when & then
        assertThatThrownBy(() -> syncService.syncCategories())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("순환 참조 감지");
    }

    @Test
    @DisplayName("computeDepthAndPath - 내부 단위 메서드: root parent는 depth=0")
    void computeDepthAndPath_returnsZeroDepth_forRootParent() {
        // given
        AliExpressCategoryItem item = new AliExpressCategoryItem("100", "루트", null);
        Map<String, AliExpressCategoryItem> itemMap = Map.of("100", item);

        // when
        var result = syncService.computeDepthAndPath(item, itemMap);

        // then
        assertThat(readDepth(result)).isEqualTo(0);
        assertThat(readCategoryPath(result)).isEqualTo("루트");
    }

    @Test
    @DisplayName("computeDepthAndPath - 내부 단위 메서드: 2단계 깊이 정상 계산")
    void computeDepthAndPath_returnsCorrectDepthAndPath() {
        // given
        AliExpressCategoryItem parent = new AliExpressCategoryItem("10", "패션", null);
        AliExpressCategoryItem child = new AliExpressCategoryItem("20", "신발", "10");
        Map<String, AliExpressCategoryItem> itemMap = Map.of("10", parent, "20", child);

        // when
        var result = syncService.computeDepthAndPath(child, itemMap);

        // then
        assertThat(readDepth(result)).isEqualTo(1);
        assertThat(readCategoryPath(result)).isEqualTo("패션/신발");
    }

    /**
     * private record로 선언된 DepthAndPath의 depth 값을 reflection으로 읽는다.
     */
    private int readDepth(Object result) {
        try {
            var method = result.getClass().getDeclaredMethod("depth");
            method.setAccessible(true);
            return (int) method.invoke(result);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("DepthAndPath.depth() 값을 읽지 못했습니다.", e);
        }
    }

    /**
     * private record로 선언된 DepthAndPath의 categoryPath 값을 reflection으로 읽는다.
     */
    private String readCategoryPath(Object result) {
        try {
            var method = result.getClass().getDeclaredMethod("categoryPath");
            method.setAccessible(true);
            return (String) method.invoke(result);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("DepthAndPath.categoryPath() 값을 읽지 못했습니다.", e);
        }
    }

    /**
     * 저장된 노드 목록에서 sourceCategoryId로 특정 노드를 찾는다.
     */
    private CategoryNode findNode(List<CategoryNode> nodes, String sourceCategoryId) {
        return nodes.stream()
                .filter(n -> sourceCategoryId.equals(n.getSourceCategoryId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("노드를 찾을 수 없음: " + sourceCategoryId));
    }
}
