package com.pbm.command.dto.response;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pbm.command.domain.CommandSession;
import com.pbm.command.domain.CommandSessionStatus;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 명령 세션 응답 DTO.
 * <p>
 * 역할: CommandSession 엔티티를 외부 응답용으로 변환하여 노출한다.
 * 동작: from(CommandSession) 정적 메서드로 엔티티 → DTO 변환을 수행한다.
 *       missingFields는 엔티티에 JSON 문자열로 저장되어 있으나,
 *       응답에서는 클라이언트가 바로 사용할 수 있도록 List&lt;String&gt;으로 변환하여 노출한다.
 *       candidates(후보 상품 목록), targetPrice(목표 가격), commandIntent(사용자 의도)도
 *       함께 노출하여 post-search clarification 시 프론트가 후보 선택 UI를 구성할 수 있도록 한다.
 * 연관: CommandSession, CommandSessionService, ProductCandidateResponse.
 */
public record CommandSessionResponse(
        String commandId,
        Long userId,
        String originalCommand,
        CommandSessionStatus status,
        List<String> missingFields,
        String clarificationMessage,
        String categoryPath,
        List<ProductCandidateResponse> candidates,
        List<String> selectedProductIds,
        SelectionValidationResultResponse validationResult,
        Integer targetPrice,
        String commandIntent,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    // Jackson ObjectMapper — JSON 파싱용 (spring-boot-starter-web 의존성으로 사용 가능)
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 엔티티를 응답 DTO로 변환한다.
     *
     * @param session 변환할 CommandSession 엔티티
     * @return 응답용 CommandSessionResponse
     */
    public static CommandSessionResponse from(CommandSession session) {
        return from(session, 0, Integer.MAX_VALUE);
    }

    /**
     * 엔티티를 응답 DTO로 변환하되 후보 상품 목록은 페이지 단위로 잘라서 반환한다.
     *
     * @param session 변환할 CommandSession 엔티티
     * @param page    0부터 시작하는 후보 페이지 번호
     * @param size    페이지당 후보 개수
     * @return 응답용 CommandSessionResponse
     */
    public static CommandSessionResponse from(CommandSession session, int page, int size) {
        List<ProductCandidateResponse> allCandidates = parseCandidates(session.getCandidatesJson());
        List<ProductCandidateResponse> pagedCandidates = sliceCandidates(allCandidates, page, size);

        return new CommandSessionResponse(
                session.getCommandId(),
                session.getUserId(),
                session.getOriginalCommand(),
                session.getStatus(),
                parseMissingFields(session.getMissingFieldsJson()),
                session.getClarificationMessage(),
                session.getCategoryPath(),
                pagedCandidates,
                parseSelectedProductIds(session.getSelectedProductIdsJson()),
                parseValidationResult(session.getValidationResultJson()),
                session.getTargetPrice(),
                session.getCommandIntent(),
                session.getCreatedAt(),
                session.getUpdatedAt()
        );
    }

    /**
     * 엔티티에 저장된 JSON 배열 문자열을 List&lt;String&gt;으로 파싱한다.
     * <p>
     * null 또는 빈 문자열이 들어오면 빈 리스트를 반환한다.
     */
    private static List<String> parseMissingFields(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<List<String>>() {});
        } catch (JsonProcessingException e) {
            // 파싱 실패 시 빈 리스트 반환 (클라이언트 응답이 깨지지 않도록 안전하게 처리)
            return List.of();
        }
    }

    /**
     * 엔티티에 저장된 candidates JSON 문자열을 List&lt;ProductCandidateResponse&gt;로 파싱한다.
     * <p>
     * null 또는 빈 문자열이 들어오면 빈 리스트를 반환한다.
     * 파싱 실패 시에도 빈 리스트를 반환하여 클라이언트 응답이 깨지지 않도록 안전하게 처리한다.
     */
    private static List<ProductCandidateResponse> parseCandidates(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<List<ProductCandidateResponse>>() {});
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    private static List<ProductCandidateResponse> sliceCandidates(List<ProductCandidateResponse> candidates, int page, int size) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        int safePage = Math.max(page, 0);
        int safeSize = size <= 0 ? 10 : size;
        int fromIndex = safePage * safeSize;
        if (fromIndex >= candidates.size()) {
            return List.of();
        }

        int toIndex = Math.min(fromIndex + safeSize, candidates.size());
        return candidates.subList(fromIndex, toIndex);
    }

    /**
     * 엔티티에 저장된 selectedProductIds JSON 문자열을 List<String>으로 파싱한다.
     */
    private static List<String> parseSelectedProductIds(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<List<String>>() {});
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    /**
     * 엔티티에 저장된 검증 결과 JSON 문자열을 SelectionValidationResultResponse로 파싱한다.
     */
    private static SelectionValidationResultResponse parseValidationResult(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, SelectionValidationResultResponse.class);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
