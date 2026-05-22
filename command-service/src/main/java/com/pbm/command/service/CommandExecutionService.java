package com.pbm.command.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pbm.command.domain.CommandSession;
import com.pbm.command.domain.CommandSessionStatus;
import com.pbm.command.dto.event.ProductCandidateDto;
import com.pbm.command.dto.event.ProductSelectionEvent;
import com.pbm.command.dto.event.ProductSelectionEventPayload;
import com.pbm.command.dto.request.CommandClarificationRequest;
import com.pbm.command.dto.request.CommandParseRequest;
import com.pbm.command.dto.request.ProductUrlSubmitRequest;
import com.pbm.command.dto.request.ProductSelectionRequest;
import com.pbm.command.dto.response.CommandParseResponse;
import com.pbm.command.dto.response.CommandSessionResponse;
import com.pbm.command.exception.InvalidProductSelectionException;
import com.pbm.command.publisher.ProductSelectionEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;
import java.util.UUID;

/**
 * 자연어 명령 파싱 후 조건에 따라 Kafka 가격 요청을 발행하는 오케스트레이션 서비스.
 *
 * 역할: CommandParsingService와 PriceRequestService를 묶어,
 *       파싱 결과에 추가 확인이 필요 없다면 곧바로 price-topic으로 가격 요청을 발행한다.
 *       또한 PRODUCT_SELECTION_REQUIRED 상태에서 사용자가 여러 후보 상품을 선택하면
 *       batch 이벤트를 발행하여 price-service가 실시간 단건 재조회 검증을 수행하게 한다.
 * 동작:
 *   1. commandParsingService.parse() 호출로 자연어를 구조화한다.
 *   2. needsClarification이 false이면 priceRequestService.publishParsedCommandRequest()를 호출한다.
 *   3. PRODUCT_SELECTION_REQUIRED + selectedProductIds → 재파싱 없이 바로 ProductSelectionEvent 발행
 *   4. 최종 CommandParseResponse를 그대로 반환한다.
 * 연관: CommandParsingService, PriceRequestService, CommandParseController, CommandSessionService,
 *       ProductSelectionEventPublisher.
 */
@Service
public class CommandExecutionService {

    private static final Logger log = LoggerFactory.getLogger(CommandExecutionService.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final CommandParsingService commandParsingService;
    private final PriceRequestService priceRequestService;
    private final CommandSessionService commandSessionService;
    private final ProductSelectionEventPublisher productSelectionEventPublisher;

    public CommandExecutionService(
            CommandParsingService commandParsingService,
            PriceRequestService priceRequestService,
            CommandSessionService commandSessionService,
            ProductSelectionEventPublisher productSelectionEventPublisher
    ) {
        this.commandParsingService = commandParsingService;
        this.priceRequestService = priceRequestService;
        this.commandSessionService = commandSessionService;
        this.productSelectionEventPublisher = productSelectionEventPublisher;
    }

    /**
     * 자연어 명령을 파싱하고, 추가 확인이 필요 없으면 즉시 가격 요청을 발행한다.
     * <p>
     * 파싑 결과에 따라 세션을 생성하고 commandId를 응답에 포함시킨다.
     *
     * @param request 사용자 자연어 명령 요청
     * @param userId  인증 기준 사용자 ID
     * @return 파싱 결과와 모달 보완 정보, commandId가 포함된 응답 DTO
     */
    public CommandParseResponse parseAndPublishIfReady(CommandParseRequest request, Long userId) {
        CommandParseResponse response = commandParsingService.parse(request);

        if (!response.needsClarification()) {
            log.info("파싱 결과 추가 확인 불필요 - userId: {} 즉시 가격 요청 발행", userId);

            // SEARCHING 세션 생성 및 commandId 확보
            CommandSessionResponse sessionResponse = commandSessionService.createSearchingSession(
                    userId,
                    request.commandText(),
                    response.parsedCommand() == null || response.parsedCommand().platform() == null
                            ? null
                            : response.parsedCommand().platform().name());
            String commandId = sessionResponse.commandId();

            // 확보한 commandId를 Kafka 이벤트에 포함시켜 발행
            priceRequestService.publishParsedCommandRequest(
                    userId,
                    response.intent().name(),
                    response.parsedCommand(),
                    commandId
            );

            return new CommandParseResponse(
                    response.intent(),
                    response.parsedCommand(),
                    response.missingRequiredFields(),
                    response.ambiguousFields(),
                    response.needsClarification(),
                    response.confidence(),
                    commandId
            );
        } else {
            log.info("파싱 결과 추가 확인 필요 - userId: {} 발행 보류, missing: {}, ambiguous: {}",
                    userId, response.missingRequiredFields(), response.ambiguousFields());

            // PRE_SEARCH_CLARIFICATION 세션 생성 및 commandId 확보
            CommandSessionResponse sessionResponse = commandSessionService.createPreSearchClarificationSession(
                    userId,
                    request.commandText(),
                    response.missingRequiredFields(),
                    buildClarificationMessage(response),
                    response.parsedCommand() == null || response.parsedCommand().platform() == null
                            ? null
                            : response.parsedCommand().platform().name()
            );
            String commandId = sessionResponse.commandId();

            return new CommandParseResponse(
                    response.intent(),
                    response.parsedCommand(),
                    response.missingRequiredFields(),
                    response.ambiguousFields(),
                    response.needsClarification(),
                    response.confidence(),
                    commandId
            );
        }
    }

    /**
     * 사용자 보완 입력을 제출받아 재파싱한다.
     * <p>
     * 역할: PRE_SEARCH_CLARIFICATION 상태에서 사용자가 보완 입력을 제출했을 때 호출된다.
     *       free-text / structured answers를 받아 originalCommand와 병합한 뒤 재파싱한다.
     * <p>
     * 동작 (일반 경로):
     *   1. commandId로 세션 로드
     *   2. request.toMergedText()로 보완 텍스트를 확보
     *   3. mergedText = originalCommand + " " + 보완 텍스트
     *   4. mergedText로 재파싱 (기존 commandParsingService.parse() 재사용)
     *   5. 재파싱 결과 판별:
     *      a) 여전히 보완 필요 → PRE_SEARCH_CLARIFICATION 상태로 업데이트 (missingFields 갱신)
     *      b) 보완 완료 → SEARCHING 상태로 전환 + 기존 priceRequestService로 Kafka 발행
     * <p>
     * @param commandId 대상 세션의 commandId
     * @param request   사용자가 입력한 보완 요청 DTO (free-text + structured answers)
     * @return 재파싱 결과 응답 DTO
     * @throws com.pbm.command.exception.CommandSessionNotFoundException 세션이 없을 때
     */
    @Transactional
    public CommandParseResponse handleClarification(String commandId, CommandClarificationRequest request) {
        // 1. 세션 로드 (같은 트랜잭션 내에서 관리되는 영속 엔티티)
        CommandSession session = commandSessionService.getSessionEntityByCommandId(commandId);

        if (session.getStatus() != CommandSessionStatus.PRE_SEARCH_CLARIFICATION) {
            throw new InvalidProductSelectionException(
                    "clarifications API는 PRE_SEARCH_CLARIFICATION 상태에서만 사용할 수 있습니다. 현재 상태: "
                            + session.getStatus()
            );
        }

        // ── 일반 재파싱 경로: PRE_SEARCH_CLARIFICATION 단계 처리 ───────────────────
        // 2b. 병합된 명령어 텍스트 구성
        //     "원래 명령어" + " " + "사용자 보완 텍스트 (free-text + structured answers)"
        String mergedText = session.getOriginalCommand().trim() + " " + request.toMergedText();

        // 3b. 병합된 텍스트로 기존 파싱 플로우 재실행
        CommandParseRequest parseRequest = new CommandParseRequest(mergedText);
        CommandParseResponse response = commandParsingService.parse(parseRequest);

        if (response.needsClarification()) {
            // 4b-1. 재파싱 결과가 여전히 보완이 필요한 경우
            //       → 세션을 PRE_SEARCH_CLARIFICATION으로 업데이트하고 새 missingFields 반영
            log.info("clarification 재파싱 후에도 추가 정보 필요 - commandId: {}, missing: {}, ambiguous: {}",
                    commandId, response.missingRequiredFields(), response.ambiguousFields());

            commandSessionService.updateToPreSearchClarification(
                    commandId,
                    response.missingRequiredFields(),
                    buildClarificationMessage(response),
                    response.parsedCommand() == null || response.parsedCommand().platform() == null
                            ? null
                            : response.parsedCommand().platform().name()
            );

            return new CommandParseResponse(
                    response.intent(),
                    response.parsedCommand(),
                    response.missingRequiredFields(),
                    response.ambiguousFields(),
                    response.needsClarification(),
                    response.confidence(),
                    commandId
            );
        } else {
            // 4b-2. 재파싱 결과가 보완을 더 이상 필요로 하지 않는 경우
            //       → 세션을 SEARCHING으로 전환하고 기존 발행 플로우로 Kafka 전송
            log.info("clarification 재파싱 성공 - commandId: {} SEARCHING 전환 및 발행", commandId);

            // 세션의 originalCommand를 병합된 텍스트로 갱신
            session.updateOriginalCommand(mergedText);
            // 상태를 SEARCHING으로 전환하면서 parse 결과 플랫폼도 세션에 저장한다.
            commandSessionService.updateToSearching(
                    commandId,
                    response.parsedCommand() == null || response.parsedCommand().platform() == null
                            ? null
                            : response.parsedCommand().platform().name()
            );

            // 기존 발행 플로우 재사용 (세션의 commandId를 그대로 재사용)
            priceRequestService.publishParsedCommandRequest(
                    session.getUserId(),
                    response.intent().name(),
                    response.parsedCommand(),
                    commandId
            );

            return new CommandParseResponse(
                    response.intent(),
                    response.parsedCommand(),
                    response.missingRequiredFields(),
                    response.ambiguousFields(),
                    response.needsClarification(),
                    response.confidence(),
                    commandId
            );
        }
    }

    /**
     * 사용자가 선택한 여러 후보 상품을 batch 검증 흐름으로 넘긴다.
     */
    @Transactional
    public CommandSessionResponse handleProductSelection(String commandId, ProductSelectionRequest request) {
        CommandSession session = commandSessionService.getSessionEntityByCommandId(commandId);

        if (session.getStatus() != CommandSessionStatus.PRODUCT_SELECTION_REQUIRED
                && session.getStatus() != CommandSessionStatus.RESUBSCRIBE_CONFIRMATION_REQUIRED) {
            throw new InvalidProductSelectionException(
                    "selection API는 PRODUCT_SELECTION_REQUIRED 또는 RESUBSCRIBE_CONFIRMATION_REQUIRED 상태에서만 사용할 수 있습니다. 현재 상태: "
                            + session.getStatus()
            );
        }

        List<ProductCandidateDto> candidates = parseCandidatesFromSession(session);
        List<ProductCandidateDto> selectedProducts = request.selectedProductIds().stream()
                .map(selectedProductId -> candidates.stream()
                        .filter(candidate -> candidate.productId().equals(selectedProductId))
                        .findFirst()
                        .orElseThrow(() -> new InvalidProductSelectionException(
                                "선택한 상품(commandId: " + commandId
                                        + ", productId: " + selectedProductId
                                        + ")이 후보 목록에 존재하지 않습니다.")))
                .collect(Collectors.toList());

        log.info("다중 상품 선택 처리 시작 - commandId: {}, selectedCount: {}",
                commandId, selectedProducts.size());

        commandSessionService.updateToPriceValidating(commandId, request.selectedProductIds());

        ProductSelectionEventPayload payload = new ProductSelectionEventPayload(
                commandId,
                session.getUserId(),
                session.getTargetPrice(),
                session.getCommandIntent(),
                request.forceResubscribe(),
                selectedProducts
        );

        ProductSelectionEvent event = new ProductSelectionEvent(
                UUID.randomUUID().toString(),
                "PRODUCTS_SELECTED",
                java.time.Instant.now(),
                "command-service",
                payload
        );

        productSelectionEventPublisher.publish(event);

        log.info("다중 상품 선택 처리 완료 - commandId: {}, 상태: PRICE_VALIDATING", commandId);

        return commandSessionService.getByCommandId(commandId);
    }

    /**
     * 사용자가 직접 입력한 상품 URL 목록을 URL 기반 검증 흐름으로 넘긴다.
     *
     * 역할: 1차 검색 결과가 만족스럽지 않을 때 사용자가 직접 찾은 상품 링크들을 받아,
     *       price-service가 플랫폼별 검증 로직으로 후보를 다시 구성하도록 요청한다.
     * 동작:
     * 1. 현재 세션이 PRODUCT_SELECTION_REQUIRED 상태인지 확인한다.
     * 2. 세션을 SEARCHING 상태로 전환하여 새 후보 확인이 진행 중임을 표현한다.
     * 3. 기존 commandId / targetPrice / intent를 유지한 채 price-topic으로 URL 목록을 발행한다.
     *
     * @param commandId 대상 세션의 commandId
     * @param request   사용자가 직접 입력한 URL 목록
     * @return SEARCHING 상태로 전환된 세션 응답 DTO
     */
    @Transactional
    public CommandSessionResponse handleProductUrlSubmission(
            String commandId,
            ProductUrlSubmitRequest request
    ) {
        CommandSession session = commandSessionService.getSessionEntityByCommandId(commandId);

        if (session.getStatus() != CommandSessionStatus.PRODUCT_SELECTION_REQUIRED) {
            throw new InvalidProductSelectionException(
                    "product-links API는 PRODUCT_SELECTION_REQUIRED 상태에서만 사용할 수 있습니다. 현재 상태: "
                            + session.getStatus()
            );
        }

        List<ProductCandidateDto> candidates = parseCandidatesFromSession(session);
        String platform = resolvePlatform(candidates);
        String searchKeyword = resolveSearchKeyword(session, candidates);

        log.info("상품 직접 링크 검증 시작 - commandId: {}, platform: {}, urlCount: {}",
                commandId, platform, request.productUrls().size());

        // URL fallback은 원본 자연어 명령문보다 1차 검색 때 실제로 사용했던 searchKeyword를 재사용해야
        // 네이버/알리 재검색 결과와 URL 매칭이 안정적으로 맞아진다.

        commandSessionService.updateToSearching(commandId, session.getPlatform());

        priceRequestService.publishProductUrlRequest(
                session.getUserId(),
                session.getCommandIntent(),
                session.getTargetPrice(),
                commandId,
                request.productUrls(),
                searchKeyword,
                platform
        );

        return commandSessionService.getByCommandId(commandId);
    }

    private String resolvePlatform(List<ProductCandidateDto> candidates) {
        return candidates.stream()
                .map(ProductCandidateDto::platform)
                .filter(platform -> platform != null && !platform.isBlank())
                .findFirst()
                .orElse("ALIEXPRESS");
    }

    private String resolveSearchKeyword(CommandSession session, List<ProductCandidateDto> candidates) {
        return candidates.stream()
                .map(ProductCandidateDto::searchKeyword)
                .filter(keyword -> keyword != null && !keyword.isBlank())
                .findFirst()
                .orElse(session.getOriginalCommand());
    }

    /**
     * 세션의 candidatesJson 필드에서 ProductCandidateDto 목록을 역직렬화한다.
     *
     * @param session 대상 세션 엔티티
     * @return 후보 상품 DTO 목록 (빈 리스트 가능)
     * @throws InvalidProductSelectionException candidatesJson이 null이거나 파싱 실패 시
     */
    private List<ProductCandidateDto> parseCandidatesFromSession(CommandSession session) {
        String candidatesJson = session.getCandidatesJson();
        if (candidatesJson == null || candidatesJson.isBlank()) {
            throw new InvalidProductSelectionException(
                    "세션(commandId: " + session.getCommandId() + ")에 후보 상품 목록이 없습니다.");
        }
        try {
            return MAPPER.readValue(candidatesJson, new TypeReference<List<ProductCandidateDto>>() {});
        } catch (JsonProcessingException e) {
            throw new InvalidProductSelectionException(
                    "세션(commandId: " + session.getCommandId() + ")의 후보 상품 목록 파싱 실패", e);
        }
    }

    /**
     * CommandParseResponse를 기반으로 사용자에게 보여줄 보완 요청 메시지를 생성한다.
     *
     * @param response 재파싱 응답
     * @return 보완 요청 메시지 문자열
     */
    private String buildClarificationMessage(CommandParseResponse response) {
        StringBuilder sb = new StringBuilder("추가 정보가 필요합니다.");
        if (!response.missingRequiredFields().isEmpty()) {
            sb.append(" 누락 필드: ").append(String.join(", ", response.missingRequiredFields())).append(".");
        }
        if (!response.ambiguousFields().isEmpty()) {
            sb.append(" 모호한 필드: ").append(String.join(", ", response.ambiguousFields())).append(".");
        }
        return sb.toString();
    }
}
