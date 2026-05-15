package com.pbm.command.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pbm.command.domain.CommandSession;
import com.pbm.command.domain.CommandSessionStatus;
import com.pbm.command.dto.event.ProductCandidateDto;
import com.pbm.command.dto.response.ProductCandidateResponse;
import com.pbm.command.dto.response.CommandSessionResponse;
import com.pbm.command.dto.response.SelectionValidationResultResponse;
import com.pbm.command.exception.CommandSessionNotFoundException;
import com.pbm.command.repository.CommandSessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 명령 세션 관리 서비스.
 * <p>
 * 역할: 사용자 명령의 생명주기(pre-search → searching → product-selection → monitoring)를
 *       CommandSession 엔티티로 관리하고 상태 전환을 실행한다.
 * 동작: 세션 생성/조회/상태 변경을 Repository에 위임하며,
 *       missingFields 리스트는 ObjectMapper로 JSON 문자열로 직렬화하여 저장한다.
 * 연관: CommandSession, CommandSessionRepository, CommandSessionResponse.
 */
@Service
public class CommandSessionService {

    // Jackson ObjectMapper는 spring-boot-starter-web이 자동 설정한다
    private final CommandSessionRepository commandSessionRepository;
    private final ObjectMapper objectMapper;

    public CommandSessionService(
            CommandSessionRepository commandSessionRepository,
            ObjectMapper objectMapper
    ) {
        this.commandSessionRepository = commandSessionRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * pre-search 보완 단계의 세션을 생성한다.
     * <p>
     * 역할: GPT 파싱 결과 필수 필드가 누락되어 사용자에게 추가 입력을 요청할 때 호출한다.
     *
     * @param userId              사용자 ID
     * @param originalCommand     원본 명령문
     * @param missingFields       누락된 필드명 목록 (예: ["size", "platform"])
     * @param clarificationMessage 사용자에게 보여줄 보완 요청 문구
     * @return 생성된 세션의 응답 DTO
     */
    @Transactional
    public CommandSessionResponse createPreSearchClarificationSession(
            Long userId,
            String originalCommand,
            List<String> missingFields,
            String clarificationMessage
    ) {
        String missingFieldsJson = serializeMissingFields(missingFields);

        // 아직 DB에 저장되지 않고 메모리에만 존재하는 객체
        CommandSession session = CommandSession.createPreSearchClarification(
                userId, originalCommand, missingFieldsJson, clarificationMessage
        );

        // session: save 전이므로 id가 null
        // saved: save 후이므로 DB가 id 자동 할당해줌 (auto increment)
        CommandSession saved = commandSessionRepository.save(session);
        // 따라서 saved를 받고 진행해야 한다.
        return CommandSessionResponse.from(saved);
    }

    /**
     * 검색 단계로 바로 진입하는 세션을 생성한다.
     * <p>
     * 역할: 파싱 결과가 완전하여 pre-search 보완 없이 바로 검색을 시작할 때 호출한다.
     *
     * @param userId          사용자 ID
     * @param originalCommand 원본 명령문
     * @return 생성된 세션의 응답 DTO
     */
    @Transactional
    public CommandSessionResponse createSearchingSession(
            Long userId,
            String originalCommand
    ) {
        CommandSession session = CommandSession.createSearching(userId, originalCommand);

        CommandSession saved = commandSessionRepository.save(session);
        return CommandSessionResponse.from(saved);
    }

    /**
     * commandId로 세션을 조회한다.
     *
     * @param commandId UUID 문자열
     * @return 조회된 세션의 응답 DTO
     * @throws CommandSessionNotFoundException 해당 commandId의 세션이 없을 때
     */
    @Transactional(readOnly = true)
    public CommandSessionResponse getByCommandId(String commandId) {
        return getByCommandId(commandId, 0, 10);
    }

    /**
     * commandId로 세션을 조회하되 후보 상품 목록은 페이지 단위로 반환한다.
     *
     * @param commandId UUID 문자열
     * @param page      0부터 시작하는 후보 페이지 번호
     * @param size      페이지당 후보 개수
     * @return 조회된 세션의 응답 DTO
     * @throws CommandSessionNotFoundException 해당 commandId의 세션이 없을 때
     */
    @Transactional(readOnly = true)
    public CommandSessionResponse getByCommandId(String commandId, int page, int size) {
        CommandSession session = commandSessionRepository.findByCommandId(commandId)
                .orElseThrow(() -> new CommandSessionNotFoundException(
                        "세션을 찾을 수 없습니다. commandId: " + commandId));

        return CommandSessionResponse.from(session, page, size);
    }

    /**
     * 세션을 상품 선택 필요(PRODUCT_SELECTION_REQUIRED) 상태로 전환한다.
     * <p>
     * 역할: price-service가 검색한 상위 후보 상품 목록을 세션에 저장하고,
     *       사용자가 후보를 직접 선택할 수 있도록 PRODUCT_SELECTION_REQUIRED 상태로 전환한다.
     *       현재 흐름에서는 missingFields는 대부분 비어 있고 categoryPath는 null일 수 있으며,
     *       핵심 데이터는 candidates, targetPrice, intent이다.
     *
     * @param commandId           대상 세션의 commandId
     * @param missingFields       새롭게 누락된 필드명 목록
     * @param clarificationMessage 사용자에게 보여줄 보완 요청 문구
     * @param categoryPath        검색을 통해 확정된 카테고리 경로
     * @param candidates          후보 상품 목록 (null 가능)
     * @param targetPrice         사용자 목표 가격 (원, null 가능)
     * @param intent              사용자 의도 문자열 (null 가능)
     * @return 업데이트된 세션의 응답 DTO
     * @throws CommandSessionNotFoundException 해당 commandId의 세션이 없을 때
     */
    @Transactional
    public CommandSessionResponse updateToProductSelectionRequired(
            String commandId,
            List<String> missingFields,
            String clarificationMessage,
            String categoryPath,
            List<ProductCandidateDto> candidates,
            Integer targetPrice,
            String intent
    ) {
        CommandSession session = commandSessionRepository.findByCommandId(commandId)
                .orElseThrow(() -> new CommandSessionNotFoundException(
                        "세션을 찾을 수 없습니다. commandId: " + commandId));

        // 현재 product-selection 단계의 핵심 저장 대상은 후보 상품 목록이다.
        // missingFields/categoryPath는 기존 메서드 시그니처를 유지하기 위한 호환 필드로 남겨둔다.
        String missingFieldsJson = serializeMissingFields(missingFields);
        String candidatesJson = serializeCandidates(candidates);
        session.toProductSelectionRequired(missingFieldsJson, clarificationMessage, categoryPath,
                candidatesJson, targetPrice, intent);

        return CommandSessionResponse.from(session);
    }

    /**
     * 세션을 모니터링 시작 상태로 전환한다.
     * <p>
     * 역할: 모든 사전/사후 확인 과정이 끝나고 실제 가격 모니터링이 시작될 때 호출한다.
     *
     * @param commandId 대상 세션의 commandId
     * @return 업데이트된 세션의 응답 DTO
     * @throws CommandSessionNotFoundException 해당 commandId의 세션이 없을 때
     */
    @Transactional
    public CommandSessionResponse updateToMonitoringStarted(String commandId) {
        CommandSession session = commandSessionRepository.findByCommandId(commandId)
                // get()으로 값을 가져오면 null이면 터지게 됨
                // orElseThrow로 안전하게 값을 꺼내오고 없으면 예외를 던짐
                // () -> 람다식을 통해 값이 없을 경우 실행하는 코드임
                .orElseThrow(() -> new CommandSessionNotFoundException(
                        "세션을 찾을 수 없습니다. commandId: " + commandId));

        session.toMonitoringStarted();  // Entity의 상태 변경 메서드 호출
        return CommandSessionResponse.from(session);    // Entity를 그대로 반환하면 DB 내부 구조가 외부에 노출되므로 DTO로 변환해서 반환
    }

    /**
     * 세션을 PRICE_VALIDATING 상태로 전환한다.
     * <p>
     * 역할: 사용자가 여러 상품을 선택한 뒤 price-service의 단건 재조회 검증이 진행 중임을 저장한다.
     *
     * @param commandId           대상 세션의 commandId
     * @param selectedProductIds  사용자가 선택한 productId 목록
     * @return 업데이트된 세션 응답 DTO
     */
    @Transactional
    public CommandSessionResponse updateToPriceValidating(
            String commandId,
            List<String> selectedProductIds
    ) {
        CommandSession session = getSessionEntityByCommandId(commandId);
        session.toPriceValidating(serializeStringList(selectedProductIds, "selectedProductIds"));
        return CommandSessionResponse.from(session);
    }

    /**
     * 세션을 기존 구독 갱신/재시작 확인 상태로 전환한다.
     * <p>
     * 역할: price-service가 동일 상품의 기존 구독을 발견했지만 사용자의 명시적 확인이
     *       필요하다고 판단한 경우, polling 응답에서 확인 메시지를 노출할 수 있도록 한다.
     *
     * @param commandId          대상 세션의 commandId
     * @param validationResult   중복 감지 결과 응답 DTO
     * @return 업데이트된 세션 응답 DTO
     */
    @Transactional
    public CommandSessionResponse updateToResubscribeConfirmationRequired(
            String commandId,
            SelectionValidationResultResponse validationResult
    ) {
        CommandSession session = getSessionEntityByCommandId(commandId);
        session.toResubscribeConfirmationRequired(serializeValidationResult(validationResult));
        return CommandSessionResponse.from(session);
    }

    /**
     * price-service 검증 결과를 세션에 반영한다.
     *
     * @param commandId         대상 세션의 commandId
     * @param nextStatus        검증 완료 후 세션 상태
     * @param validationResult  triggered / monitoring 결과 응답 DTO
     * @return 업데이트된 세션 응답 DTO
     */
    @Transactional
    public CommandSessionResponse completeValidation(
            String commandId,
            CommandSessionStatus nextStatus,
            SelectionValidationResultResponse validationResult
    ) {
        CommandSession session = getSessionEntityByCommandId(commandId);
        session.completeValidation(nextStatus, serializeValidationResult(validationResult));
        return CommandSessionResponse.from(session);
    }

    /**
     * 세션 엔티티를 직접 반환한다.
     * <p>
     * package-private으로 선언하여 같은 서비스 패키지의 오케스트레이션 서비스가
     * 엔티티를 직접 조작할 수 있도록 허용한다.
     *
     * @param commandId UUID 문자열
     * @return CommandSession 엔티티
     * @throws CommandSessionNotFoundException 해당 commandId의 세션이 없을 때
     */
    CommandSession getSessionEntityByCommandId(String commandId) {
        return commandSessionRepository.findByCommandId(commandId)
                .orElseThrow(() -> new CommandSessionNotFoundException(
                        "세션을 찾을 수 없습니다. commandId: " + commandId));
    }

    /**
     * 세션을 pre-search 보완 상태로 전환한다.
     * <p>
     * 역할: 재파싱 결과 여전히 누락 필드가 있어 사용자 보완이 다시 필요할 때 호출한다.
     *
     * @param commandId           대상 세션의 commandId
     * @param missingFields       새롭게 누락된 필드명 목록
     * @param clarificationMessage 사용자에게 보여줄 보완 요청 문구
     * @return 업데이트된 세션의 응답 DTO
     * @throws CommandSessionNotFoundException 해당 commandId의 세션이 없을 때
     */
    @Transactional
    public CommandSessionResponse updateToPreSearchClarification(
            String commandId,
            List<String> missingFields,
            String clarificationMessage
    ) {
        CommandSession session = getSessionEntityByCommandId(commandId);
        String missingFieldsJson = serializeMissingFields(missingFields);
        session.toPreSearchClarification(missingFieldsJson, clarificationMessage);
        return CommandSessionResponse.from(session);
    }

    /**
     * 세션을 검색(SEARCHING) 상태로 전환한다.
     * <p>
     * 역할: 모든 보완이 완료되어 실제 검색을 시작할 때 호출한다.
     *
     * @param commandId 대상 세션의 commandId
     * @return 업데이트된 세션의 응답 DTO
     * @throws CommandSessionNotFoundException 해당 commandId의 세션이 없을 때
     */
    @Transactional
    public CommandSessionResponse updateToSearching(String commandId) {
        CommandSession session = getSessionEntityByCommandId(commandId);
        session.toSearching();
        return CommandSessionResponse.from(session);
    }

    // =========================================================================
    // private 헬퍼
    // =========================================================================

    /**
     * missingFields 리스트를 JSON 배열 문자열로 직렬화한다.
     * <p>
     * ObjectMapper.writeValueAsString은 checked exception(JsonProcessingException)을
     * 던지므로 RuntimeException으로 감싸서 서비스 레이어를 깔끔하게 유지한다.
     */
    private String serializeMissingFields(List<String> missingFields) {
        if (missingFields == null || missingFields.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(missingFields);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("missingFields JSON 직렬화 실패", e);
        }
    }

    /**
     * ProductCandidateDto 리스트를 JSON 문자열로 직렬화한다.
     * <p>
     * null 또는 빈 리스트는 null을 반환한다.
     */
    private String serializeCandidates(List<ProductCandidateDto> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(candidates);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("candidates JSON 직렬화 실패", e);
        }
    }

    /**
     * 문자열 리스트를 JSON 문자열로 직렬화한다.
     */
    private String serializeStringList(List<String> values, String fieldName) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(fieldName + " JSON 직렬화 실패", e);
        }
    }

    /**
     * 검증 결과 DTO를 JSON 문자열로 직렬화한다.
     */
    private String serializeValidationResult(SelectionValidationResultResponse validationResult) {
        if (validationResult == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(validationResult);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("validationResult JSON 직렬화 실패", e);
        }
    }
}
