package com.pbm.command.dto.response;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pbm.command.domain.CommandSession;
import com.pbm.command.domain.CommandSessionStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
@Schema(description = "명령 세션 상태 응답 DTO")
public record CommandSessionResponse(
        @Schema(description = "명령 세션 식별자", example = "97314885-3dfb-4bca-be21-742d2155b098")
        String commandId,
        @Schema(description = "명령을 생성한 사용자 ID", example = "6")
        Long userId,
        @Schema(description = "사용자가 입력한 원본 자연어 명령")
        String originalCommand,
        @Schema(description = "현재 명령 세션 상태", example = "PRODUCT_SELECTION_REQUIRED")
        CommandSessionStatus status,
        @Schema(description = "누락 또는 추가 확인이 필요한 필드 목록")
        List<String> missingFields,
        @Schema(description = "클라이언트에 보여줄 보완 안내 메시지")
        String clarificationMessage,
        @Schema(description = "추후 카테고리 경로 확장용 필드")
        String categoryPath,
        @Schema(description = "현재 페이지(page,size)에 해당하는 후보 상품 목록")
        List<ProductCandidateResponse> candidates,
        @Schema(description = "사용자가 선택한 상품 productId 목록")
        List<String> selectedProductIds,
        @Schema(description = "실시간 검증/구매 판단 결과")
        SelectionValidationResultResponse validationResult,
        @Schema(description = "명령 목표 가격", example = "100000")
        Integer targetPrice,
        @Schema(description = "명령 의도", example = "AUTO_PURCHASE")
        String commandIntent,
        @Schema(description = "세션에 저장된 목표 플랫폼", example = "NAVER")
        String platform,
        @Schema(description = "세션 생성 시각")
        LocalDateTime createdAt,
        @Schema(description = "세션 마지막 갱신 시각")
        LocalDateTime updatedAt
) {
    // Jackson ObjectMapper — JSON 파싱용 (spring-boot-starter-web 의존성으로 사용 가능)
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * platform 필드가 추가되기 전 기존 테스트/호출부와의 하위 호환 생성자.
     */
    public CommandSessionResponse(
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
        this(
                commandId,
                userId,
                originalCommand,
                status,
                missingFields,
                clarificationMessage,
                categoryPath,
                candidates,
                selectedProductIds,
                validationResult,
                targetPrice,
                commandIntent,
                null,
                createdAt,
                updatedAt
        );
    }

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
        // 플랫폼별로 번갈아 노출 (NAVER→ALIEXPRESS→NAVER→...) 후 페이지 자르기
        List<ProductCandidateResponse> interleavedCandidates = interleaveCandidatesByPlatform(allCandidates);
        List<ProductCandidateResponse> pagedCandidates = sliceCandidates(interleavedCandidates, page, size);

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
                session.getPlatform(),
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

    /**
     * 후보 상품 목록을 플랫폼별로 번갈아 정렬한다.
     * <p>
     * 예: [N1, N2, N3, A1, A2] → [N1, A1, N2, A2, N3]
     * 한 쪽 플랫폼이 먼저 소진되면 나머지 플랫폼 상품을 순서대로 이어 붙인다.
     * 플랫폼이 1종류뿐이면 원본 순서를 그대로 유지한다.
     *
     * @param candidates 원본 후보 목록
     * @return 플랫폼 인터리빙된 후보 목록
     */
    private static List<ProductCandidateResponse> interleaveCandidatesByPlatform(
            List<ProductCandidateResponse> candidates
    ) {
        if (candidates == null || candidates.size() <= 1) {
            return candidates;
        }

        // 플랫폼별 큐(순서 보장)를 구성한다. LinkedHashMap으로 최초 등장 순서를 유지한다.
        Map<String, List<ProductCandidateResponse>> buckets = new LinkedHashMap<>();
        for (ProductCandidateResponse candidate : candidates) {
            String key = candidate.platform() != null ? candidate.platform() : "UNKNOWN";
            buckets.computeIfAbsent(key, k -> new ArrayList<>()).add(candidate);
        }

        // 플랫폼이 하나뿐이면 원본 반환
        if (buckets.size() <= 1) {
            return candidates;
        }

        // 플랫폼 큐를 라운드로빈으로 꺼내 인터리빙 리스트를 생성한다.
        List<List<ProductCandidateResponse>> queues = new ArrayList<>(buckets.values());
        List<ProductCandidateResponse> result = new ArrayList<>(candidates.size());

        // 인덱스를 따로 관리해 각 큐에서 순서대로 꺼낸다.
        int[] indices = new int[queues.size()];
        boolean progress = true;
        while (progress) {
            progress = false;
            for (int q = 0; q < queues.size(); q++) {
                List<ProductCandidateResponse> queue = queues.get(q);
                if (indices[q] < queue.size()) {
                    result.add(queue.get(indices[q]++));
                    progress = true;
                }
            }
        }
        return result;
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
