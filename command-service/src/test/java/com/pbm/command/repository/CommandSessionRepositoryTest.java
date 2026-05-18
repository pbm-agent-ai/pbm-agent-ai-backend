package com.pbm.command.repository;
import com.pbm.command.domain.CommandSession;
import com.pbm.command.domain.CommandSessionStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CommandSessionRepository 데이터 접근 테스트.
 * <p>
 * 역할: JPA 저장/조회가 정상 동작하는지 검증한다.
 * 동작: 세션 저장 후 ID와 commandId로 각각 조회하여 필드 값을 확인한다.
 * 연관: CommandSession, CommandSessionRepository.
 */
@DataJpaTest
class CommandSessionRepositoryTest {

    @Autowired
    private CommandSessionRepository commandSessionRepository;

    @Test
    @DisplayName("pre-search clarification 세션을 저장하고 ID로 조회한다")
    void saveAndFindById_returnsPersistedSession() {
        // given
        CommandSession session = CommandSession.createPreSearchClarification(
                1L,
                "나이키 조던 20만원 이하면 결제해줘",
                "[\"size\",\"platform\"]",
                "사이즈와 플랫폼을 알려주세요."
        );

        // when
        CommandSession saved = commandSessionRepository.save(session);

        // then
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCommandId()).isNotNull();
        assertThat(saved.getUserId()).isEqualTo(1L);
        assertThat(saved.getOriginalCommand()).contains("나이키 조던");
        assertThat(saved.getStatus()).isEqualTo(CommandSessionStatus.PRE_SEARCH_CLARIFICATION);
        assertThat(saved.getMissingFieldsJson()).isEqualTo("[\"size\",\"platform\"]");
        assertThat(saved.getClarificationMessage()).isEqualTo("사이즈와 플랫폼을 알려주세요.");
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("searching 세션을 저장하고 commandId로 조회한다")
    void saveAndFindByCommandId_returnsPersistedSession() {
        // given
        CommandSession session = CommandSession.createSearching(
                2L,
                "아이폰 15 프로 256GB 140만원 이하 가격 알려줘"
        );
        CommandSession saved = commandSessionRepository.save(session);

        // when
        Optional<CommandSession> found = commandSessionRepository.findByCommandId(saved.getCommandId());

        // then
        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(saved.getId());
        assertThat(found.get().getStatus()).isEqualTo(CommandSessionStatus.SEARCHING);
        assertThat(found.get().getOriginalCommand()).contains("아이폰 15 프로");
    }

    @Test
    @DisplayName("존재하지 않는 commandId로 조회하면 빈 Optional을 반환한다")
    void findByCommandId_withNonExistentId_returnsEmpty() {
        // when
        Optional<CommandSession> found = commandSessionRepository.findByCommandId("non-existent-uuid");

        // then
        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("product-selection-required로 상태를 업데이트하고 조회한다")
    void updateStatus_toProductSelectionRequired_persistsChanges() {
        // given
        CommandSession session = CommandSession.createSearching(1L, "맥북 프로 16인치");
        CommandSession saved = commandSessionRepository.save(session);

        // when
        saved.toProductSelectionRequired(
                "[\"model\"]",
                "모델명을 더 구체적으로 알려주세요.",
                "전자기기 > 노트북",
                "[]",
                1500000,
                "AUTO_PURCHASE"
        );
        CommandSession updated = commandSessionRepository.save(saved);

        // then
        assertThat(updated.getStatus()).isEqualTo(CommandSessionStatus.PRODUCT_SELECTION_REQUIRED);
        assertThat(updated.getMissingFieldsJson()).isEqualTo("[\"model\"]");
        assertThat(updated.getCategoryPath()).isEqualTo("전자기기 > 노트북");
        assertThat(updated.getClarificationMessage()).isEqualTo("모델명을 더 구체적으로 알려주세요.");
        assertThat(updated.getCandidatesJson()).isEqualTo("[]");
        assertThat(updated.getTargetPrice()).isEqualTo(1500000);
        assertThat(updated.getCommandIntent()).isEqualTo("AUTO_PURCHASE");
    }

    @Test
    @DisplayName("monitoring started로 상태를 업데이트하고 조회한다")
    void updateStatus_toMonitoringStarted_persistsChanges() {
        // given
        CommandSession session = CommandSession.createSearching(1L, "나이키 에어포스");
        CommandSession saved = commandSessionRepository.save(session);

        // when
        saved.toMonitoringStarted();
        CommandSession updated = commandSessionRepository.save(saved);

        // then
        assertThat(updated.getStatus()).isEqualTo(CommandSessionStatus.MONITORING_STARTED);
        assertThat(updated.getMissingFieldsJson()).isNull(); // 변경되지 않음
    }

    @Test
    @DisplayName("browser-purchase-in-progress 상태를 저장하고 조회한다")
    void saveAndFind_sessionWithBrowserPurchaseInProgress_persistsStatus() {
        // given
        CommandSession session = CommandSession.createSearching(1L, "브라우저 구매 진행 테스트");
        CommandSession saved = commandSessionRepository.save(session);

        // when
        saved.toBrowserPurchaseInProgress();
        CommandSession updated = commandSessionRepository.save(saved);

        // then
        assertThat(updated.getStatus()).isEqualTo(CommandSessionStatus.BROWSER_PURCHASE_IN_PROGRESS);
    }
}
