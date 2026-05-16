package com.pbm.command.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pbm.command.domain.CommandSession;
import com.pbm.command.domain.CommandSessionStatus;
import com.pbm.command.dto.response.CommandSessionResponse;
import com.pbm.command.dto.response.ProductCandidateResponse;
import com.pbm.command.dto.response.SelectionValidationResultResponse;
import com.pbm.command.exception.CommandSessionNotFoundException;
import com.pbm.command.repository.CommandSessionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * CommandSessionService 단위 테스트.
 * <p>
 * 역할: 서비스의 상태 전환 로직과 예외 처리를 검증한다.
 * 동작: Mock Repository를 사용하여 서비스 계층의 비즈니스 로직만 집중 테스트한다.
 * 연관: CommandSessionService, CommandSessionRepository.
 */
@ExtendWith(MockitoExtension.class)
class CommandSessionServiceTest {

    @Mock
    private CommandSessionRepository commandSessionRepository;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private CommandSessionService commandSessionService;

    @Captor
    private ArgumentCaptor<CommandSession> sessionCaptor;

    private static final String TEST_COMMAND_ID = "test-uuid-1234";

    @Test
    @DisplayName("pre-search clarification 세션을 생성한다")
    void createPreSearchClarificationSession_createsSessionWithCorrectStatus() throws Exception {
        // given
        List<String> missingFields = List.of("size", "platform");
        String missingFieldsJson = "[\"size\",\"platform\"]";

        given(objectMapper.writeValueAsString(missingFields)).willReturn(missingFieldsJson);
        given(commandSessionRepository.save(any(CommandSession.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        // when
        CommandSessionResponse response = commandSessionService.createPreSearchClarificationSession(
                1L,
                "나이키 조던 20만원 이하면 결제해줘",
                missingFields,
                "사이즈와 플랫폼을 알려주세요."
        );

        // then
        verify(commandSessionRepository).save(sessionCaptor.capture());
        CommandSession savedSession = sessionCaptor.getValue();

        assertThat(savedSession.getCommandId()).isNotNull();
        assertThat(savedSession.getStatus()).isEqualTo(CommandSessionStatus.PRE_SEARCH_CLARIFICATION);
        assertThat(savedSession.getMissingFieldsJson()).isEqualTo(missingFieldsJson);

        assertThat(response.commandId()).isNotNull();
        assertThat(response.status()).isEqualTo(CommandSessionStatus.PRE_SEARCH_CLARIFICATION);
        assertThat(response.missingFields()).containsExactly("size", "platform");
    }

    @Test
    @DisplayName("searching 세션을 생성한다")
    void createSearchingSession_createsSessionWithCorrectStatus() {
        // given
        given(commandSessionRepository.save(any(CommandSession.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        // when
        CommandSessionResponse response = commandSessionService.createSearchingSession(
                2L,
                "아이폰 15 프로 256GB 140만원 이하 가격 알려줘"
        );

        // then
        verify(commandSessionRepository).save(sessionCaptor.capture());
        CommandSession savedSession = sessionCaptor.getValue();

        assertThat(savedSession.getStatus()).isEqualTo(CommandSessionStatus.SEARCHING);
        assertThat(savedSession.getOriginalCommand()).contains("아이폰 15 프로");
        assertThat(response.status()).isEqualTo(CommandSessionStatus.SEARCHING);
    }

    @Test
    @DisplayName("commandId로 세션을 조회한다")
    void getByCommandId_returnsSessionResponse() {
        // given
        CommandSession session = CommandSession.createSearching(1L, "테스트 명령");
        given(commandSessionRepository.findByCommandId(TEST_COMMAND_ID))
                .willReturn(Optional.of(session));

        // when
        CommandSessionResponse response = commandSessionService.getByCommandId(TEST_COMMAND_ID);

        // then
        assertThat(response.commandId()).isEqualTo(session.getCommandId());
        assertThat(response.status()).isEqualTo(CommandSessionStatus.SEARCHING);
        assertThat(response.originalCommand()).isEqualTo("테스트 명령");
    }

    @Test
    @DisplayName("commandId 조회 시 후보 상품을 페이지 단위로 잘라서 반환한다")
    void getByCommandId_withPaging_returnsSlicedCandidates() {
        CommandSession session = CommandSession.createSearching(1L, "테스트 명령");
        session.toProductSelectionRequired(
                "[]",
                "검색 결과를 확인하고 상품을 선택해주세요.",
                null,
                "[" +
                        "{\"productId\":\"naver-1\",\"title\":\"상품 1\",\"lprice\":\"1000\",\"mallName\":\"스토어1\",\"productUrl\":\"https://example.com/1\",\"currency\":\"KRW\",\"platform\":\"NAVER\",\"searchKeyword\":\"키보드\"}," +
                        "{\"productId\":\"naver-2\",\"title\":\"상품 2\",\"lprice\":\"2000\",\"mallName\":\"스토어2\",\"productUrl\":\"https://example.com/2\",\"currency\":\"KRW\",\"platform\":\"NAVER\",\"searchKeyword\":\"키보드\"}," +
                        "{\"productId\":\"naver-3\",\"title\":\"상품 3\",\"lprice\":\"3000\",\"mallName\":\"스토어3\",\"productUrl\":\"https://example.com/3\",\"currency\":\"KRW\",\"platform\":\"NAVER\",\"searchKeyword\":\"키보드\"}" +
                        "]",
                100000,
                "AUTO_PURCHASE"
        );
        given(commandSessionRepository.findByCommandId(TEST_COMMAND_ID))
                .willReturn(Optional.of(session));

        CommandSessionResponse response = commandSessionService.getByCommandId(TEST_COMMAND_ID, 1, 2);

        assertThat(response.candidates()).hasSize(1);
        assertThat(response.candidates().get(0).productId()).isEqualTo("naver-3");
    }

    @Test
    @DisplayName("존재하지 않는 commandId로 조회하면 예외를 던진다")
    void getByCommandId_withNonExistentId_throwsException() {
        // given
        given(commandSessionRepository.findByCommandId("invalid-id"))
                .willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> commandSessionService.getByCommandId("invalid-id"))
                .isInstanceOf(CommandSessionNotFoundException.class)
                .hasMessageContaining("세션을 찾을 수 없습니다");
    }

    @Test
    @DisplayName("product-selection-required로 상태를 전환한다 (candidates/targetPrice/intent 포함)")
    void updateToProductSelectionRequired_updatesStatusAndFields() throws Exception {
        // given
        CommandSession session = CommandSession.createSearching(1L, "맥북 프로");
        List<String> missingFields = List.of("model");
        String missingFieldsJson = "[\"model\"]";
        List<com.pbm.command.dto.event.ProductCandidateDto> candidates = List.of(
                new com.pbm.command.dto.event.ProductCandidateDto(
                        "naver-macbook-1", "맥북 프로 14", "2500000",
                        "애플스토어", "https://example.com/macbook", null, "KRW", null, "맥북 프로 14"
                )
        );
        String candidatesJson =
                "[{\"productId\":\"naver-macbook-1\",\"title\":\"맥북 프로 14\"," +
                "\"lprice\":\"2500000\",\"mallName\":\"애플스토어\"," +
                "\"productUrl\":\"https://example.com/macbook\",\"currency\":\"KRW\",\"searchKeyword\":\"맥북 프로 14\"}]";

        given(commandSessionRepository.findByCommandId(TEST_COMMAND_ID))
                .willReturn(Optional.of(session));
        given(objectMapper.writeValueAsString(missingFields)).willReturn(missingFieldsJson);
        given(objectMapper.writeValueAsString(candidates)).willReturn(candidatesJson);

        // when
        CommandSessionResponse response = commandSessionService.updateToProductSelectionRequired(
                TEST_COMMAND_ID,
                missingFields,
                "모델명을 더 구체적으로 알려주세요.",
                "전자기기 > 노트북",
                candidates,
                3000000,
                "AUTO_PURCHASE"
        );

        // then
        assertThat(response.status()).isEqualTo(CommandSessionStatus.PRODUCT_SELECTION_REQUIRED);
        assertThat(response.missingFields()).containsExactly("model");
        assertThat(response.categoryPath()).isEqualTo("전자기기 > 노트북");
        assertThat(response.clarificationMessage()).isEqualTo("모델명을 더 구체적으로 알려주세요.");
        assertThat(response.candidates()).isNotNull();
        assertThat(response.candidates()).hasSize(1);
        assertThat(response.candidates().get(0).productId()).isEqualTo("naver-macbook-1");
        assertThat(response.candidates().get(0).title()).isEqualTo("맥북 프로 14");
        assertThat(response.candidates().get(0).lprice()).isEqualTo("2500000");
        assertThat(response.candidates().get(0).searchKeyword()).isEqualTo("맥북 프로 14");
        assertThat(response.targetPrice()).isEqualTo(3000000);
        assertThat(response.commandIntent()).isEqualTo("AUTO_PURCHASE");
    }

    @Test
    @DisplayName("monitoring started로 상태를 전환한다")
    void updateToMonitoringStarted_updatesStatus() {
        // given
        CommandSession session = CommandSession.createSearching(1L, "나이키 에어포스");
        given(commandSessionRepository.findByCommandId(TEST_COMMAND_ID))
                .willReturn(Optional.of(session));

        // when
        CommandSessionResponse response = commandSessionService.updateToMonitoringStarted(TEST_COMMAND_ID);

        // then
        assertThat(response.status()).isEqualTo(CommandSessionStatus.MONITORING_STARTED);
    }

    @Test
    @DisplayName("존재하지 않는 세션에 product-selection-required 전환을 시도하면 예외를 던진다")
    void updateToProductSelectionRequired_withNonExistentId_throwsException() {
        // given
        given(commandSessionRepository.findByCommandId("invalid-id"))
                .willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() ->
                commandSessionService.updateToProductSelectionRequired(
                        "invalid-id", List.of("size"), "메시지", "경로",
                        null, null, null
                ))
                .isInstanceOf(CommandSessionNotFoundException.class)
                .hasMessageContaining("세션을 찾을 수 없습니다");
    }

    @Test
    @DisplayName("price-validating으로 상태를 전환한다 (selectedProductIds 포함)")
    void updateToPriceValidating_updatesStatusAndSelectedIds() throws Exception {
        // given
        CommandSession session = CommandSession.createSearching(1L, "테스트 상품");
        List<String> selectedProductIds = List.of("naver-1", "naver-2");
        String selectedProductIdsJson = "[\"naver-1\",\"naver-2\"]";

        given(commandSessionRepository.findByCommandId(TEST_COMMAND_ID))
                .willReturn(Optional.of(session));
        given(objectMapper.writeValueAsString(selectedProductIds)).willReturn(selectedProductIdsJson);

        // when
        CommandSessionResponse response = commandSessionService.updateToPriceValidating(
                TEST_COMMAND_ID, selectedProductIds);

        // then
        assertThat(response.status()).isEqualTo(CommandSessionStatus.PRICE_VALIDATING);
        assertThat(response.selectedProductIds()).containsExactly("naver-1", "naver-2");
    }

    @Test
    @DisplayName("재구독 확인 필요 상태로 전환한다")
    void updateToResubscribeConfirmationRequired_updatesStatusAndValidationResult() throws Exception {
        // given
        CommandSession session = CommandSession.createSearching(1L, "테스트 상품");
        SelectionValidationResultResponse validationResult = new SelectionValidationResultResponse(
                List.of(),
                List.of(),
                null,
                "중복 모니터링 확인 필요",
                true,
                List.of(new ProductCandidateResponse(
                        "naver-1", "테스트 상품", "250000", "테스트몰",
                        "https://example.com/product-1", null, "KRW", "NAVER", "테스트 키워드"
                )),
                "이미 이 상품을 모니터링한 이력이 있습니다."
        );
        String validationResultJson = "{\"confirmationRequired\":true}";

        given(commandSessionRepository.findByCommandId(TEST_COMMAND_ID))
                .willReturn(Optional.of(session));
        given(objectMapper.writeValueAsString(validationResult)).willReturn(validationResultJson);

        // when
        CommandSessionResponse response = commandSessionService.updateToResubscribeConfirmationRequired(
                TEST_COMMAND_ID,
                validationResult
        );

        // then
        assertThat(response.status()).isEqualTo(CommandSessionStatus.RESUBSCRIBE_CONFIRMATION_REQUIRED);
        assertThat(response.validationResult()).isNotNull();
    }

    @Test
    @DisplayName("존재하지 않는 세션에 monitoring started 전환을 시도하면 예외를 던진다")
    void updateToMonitoringStarted_withNonExistentId_throwsException() {
        // given
        given(commandSessionRepository.findByCommandId("invalid-id"))
                .willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() ->
                commandSessionService.updateToMonitoringStarted("invalid-id"))
                .isInstanceOf(CommandSessionNotFoundException.class)
                .hasMessageContaining("세션을 찾을 수 없습니다");
    }
}
