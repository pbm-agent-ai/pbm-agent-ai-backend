package com.pbm.command.service;

import com.pbm.command.domain.CommandFieldType;
import com.pbm.command.domain.CommandIntent;
import com.pbm.command.domain.ProductCategory;
import com.pbm.command.dto.response.ParsedCommand;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 파싱 결과의 누락 필드와 모호 필드를 계산하는 서비스.
 *
 * 역할: GPT가 추출한 ParsedCommand를 공통 필수 필드 정책과 비교하여
 *       프론트 모달에 보여줄 missingRequiredFields, ambiguousFields를 계산한다.
 * 동작: 필수 필드(PRODUCT_NAME, MAX_PRICE)는 null/빈값 여부를 검사하고,
 *       PLATFORM은 선택 필드이므로 누락되어도 clarification을 요청하지 않는다.
 *       자동 결제 또는 상품명 모호성 상황에서는 추가 확인 필드를 별도로 수집한다.
 *       (현재 모든 카테고리 공통 정책이며, 카테고리별 size/color/model 의존 로직은 제거됨)
 * 연관: CommandFieldPolicyService, ParsedCommand, FieldEvaluationResult.
 */
@Service
public class CommandFieldEvaluationService {

    private final CommandFieldPolicyService commandFieldPolicyService;

    public CommandFieldEvaluationService(CommandFieldPolicyService commandFieldPolicyService) {
        this.commandFieldPolicyService = commandFieldPolicyService;
    }

    /**
     * 파싱 결과를 기준으로 누락 필드와 모호 필드를 한 번에 계산한다.
     *
     * @param intent       사용자 의도
     * @param parsedCommand GPT가 추출한 구조화 결과
     * @return 누락/모호 필드 계산 결과
     */
    public FieldEvaluationResult evaluate(CommandIntent intent, ParsedCommand parsedCommand) {
        // URL_MONITOR: productUrls가 있고 maxPrice가 있으면 바로 진행, 없으면 maxPrice만 요구
        if (intent == CommandIntent.URL_MONITOR) {
            List<String> missing = new java.util.ArrayList<>();
            if (parsedCommand == null
                    || parsedCommand.productUrls() == null
                    || parsedCommand.productUrls().isEmpty()) {
                missing.add("productUrls");
            }
            if (parsedCommand == null
                    || parsedCommand.maxPrice() == null
                    || parsedCommand.maxPrice() <= 0) {
                missing.add("maxPrice");
            }
            return new FieldEvaluationResult(List.copyOf(missing), List.of(), false);
        }

        // 필수인데 비어있는 필드 계산
        List<String> missingRequiredFields = calculateMissingRequiredFields(intent, parsedCommand);
        // 값은 있지만 더 확인해야 하는 필드 계산
        List<String> ambiguousFields = calculateAmbiguousFields(intent, parsedCommand);
        // 두 결과를 하나의 객체로 합쳐서 변환
        return new FieldEvaluationResult(missingRequiredFields, ambiguousFields, false);
    }

    /**
     * 공통 필수 필드 기준으로 누락 필드를 계산한다.
     *
     * @param intent        사용자 의도
     * @param parsedCommand GPT가 추출한 구조화 결과
     * @return 비어 있는 필수 필드 목록
     */
    public List<String> calculateMissingRequiredFields(CommandIntent intent, ParsedCommand parsedCommand) {
        // GPT가 아예 아무것도 못 뽑았으면 최소 필수 필드를 반환 (PRICE_CHECK는 maxPrice 제외)
        if (parsedCommand == null) {
            List<String> required = new ArrayList<>();
            required.add(CommandFieldType.PRODUCT_CATEGORY.fieldKey());
            required.add(CommandFieldType.PRODUCT_NAME.fieldKey());
            if (intent != CommandIntent.PRICE_CHECK) {
                required.add(CommandFieldType.MAX_PRICE.fieldKey());
            }
            return List.copyOf(required);
        }

        // ArrayList: 여기에 필드키를 하나씩 추가할거라서 가변 리스트 사용
        List<String> missingFields = new ArrayList<>();

        // 공통 필수 필드(PRODUCT_NAME, MAX_PRICE, PLATFORM) 중 비어있는 게 뭔지 검사
        // getRequiredFields는 모든 카테고리에서 동일한 결과를 반환함
        for (CommandFieldType fieldType : commandFieldPolicyService.getRequiredFields(parsedCommand.productCategory())) {
            // MAX_PRICE타입을 검사할때 Intent가 PRICE_CHECK이면 그냥 continue시키는 로직
            if (fieldType == CommandFieldType.MAX_PRICE && intent == CommandIntent.PRICE_CHECK) {
                continue;
            }
            if (isMissing(fieldType, parsedCommand)) {
                missingFields.add(fieldType.fieldKey());
            }
        }

        return List.copyOf(missingFields);
    }

    /**
     * 추가 확인이 필요한 모호 필드를 계산한다.
     *
     * @param intent        사용자 의도
     * @param parsedCommand GPT가 추출한 구조화 결과
     * @return 추가 확인 대상 필드 목록
     */
    // LinkedHashSet을 쓰는 이유:
    // 1) 중복 방지 (같은 필드가 여러 조건에 걸려도 한 번만 나옴)
    // 2) 입력 순서 유지 (프론트에 일관된 순서로 보여주기 위해)
    public List<String> calculateAmbiguousFields(CommandIntent intent, ParsedCommand parsedCommand) {
        LinkedHashSet<String> ambiguousFields = new LinkedHashSet<>();

        // GPT가 아무 값도 뽑지 못한 경우에는 무엇을 검색해야 할지 자체가 불명확하므로 카테고리를 다시 물어본다.
        // 단, productCategory가 UNKNOWN이어도 productName / platform / 가격 정보가 있으면 키워드 검색은 진행할 수 있으므로
        // UNKNOWN만을 이유로 즉시 clarification으로 막지 않는다.
        if (parsedCommand == null || parsedCommand.productCategory() == null) {
            ambiguousFields.add(CommandFieldType.PRODUCT_CATEGORY.fieldKey());
            return List.copyOf(ambiguousFields);
        }

        // 자동 결제의 경우 진짜 돈이 나가므로 더 확실한 정보가 필요함
        // 현재는 PLATFORM이 공통 필수 필드에 포함되어 있어 별도 확인 불필요하나,
        // 향후 추가 확인 필드가 필요하면 policy를 통해 확장 가능
        if (intent == CommandIntent.AUTO_PURCHASE) {
            addMissingFieldKeys(
                    ambiguousFields,
                    commandFieldPolicyService.getAutoPurchaseClarificationFields(parsedCommand.productCategory()),
                    parsedCommand
            );
        }

        // 상품명이 2단어 이하로 너무 짧을 경우 검색 결과가 너무 많아지므로 추가 정보를 받아옴
        // 카테고리별 size/color/model 의존 로직은 제거되었으며, 필요 시 공통 필드로 확장 가능
        if (isBroadProductName(parsedCommand)) {
            addMissingFieldKeys(
                    ambiguousFields,
                    commandFieldPolicyService.getBroadProductClarificationFields(parsedCommand.productCategory()),
                    parsedCommand
            );
        }

        return List.copyOf(ambiguousFields);
    }

    private void addMissingFieldKeys(
            LinkedHashSet<String> ambiguousFields,  // 결과를 여기에 누적
            List<CommandFieldType> fieldTypes,      // 검사할 필드 목록
            ParsedCommand parsedCommand             // GPT 파싱 결과
    ) {
        for (CommandFieldType fieldType : fieldTypes) {
            if (isMissing(fieldType, parsedCommand)) {
                ambiguousFields.add(fieldType.fieldKey());
            }
        }
    }

    private boolean isBroadProductName(ParsedCommand parsedCommand) {
        String productName = parsedCommand.productName();
        if (isBlank(productName)) {
            return false;
        }

        // 브랜드 + 라인 정도만 있는 짧은 상품명은 후보가 너무 많다고 가정한다.
        int tokenCount = productName.trim().split("\\s+").length;
        return tokenCount <= 2;
    }

    private boolean isMissing(CommandFieldType fieldType, ParsedCommand parsedCommand) {
        return switch (fieldType) {
            case PRODUCT_CATEGORY -> parsedCommand.productCategory() == null || parsedCommand.productCategory() == ProductCategory.UNKNOWN;
            case PRODUCT_NAME -> isBlank(parsedCommand.productName());
            case BRAND -> isBlank(parsedCommand.brand());
            case LINE -> isBlank(parsedCommand.line());
            case MODEL -> isBlank(parsedCommand.model());
            case COLOR -> isBlank(parsedCommand.color());
            case SIZE -> isBlank(parsedCommand.size());
            case PLATFORM -> parsedCommand.platforms() == null || parsedCommand.platforms().isEmpty();
            case MAX_PRICE -> parsedCommand.maxPrice() == null || parsedCommand.maxPrice() <= 0;
            case MIN_PRICE -> parsedCommand.minPrice() == null || parsedCommand.minPrice() <= 0;
            case CURRENCY -> isBlank(parsedCommand.currency());
            case SEARCH_CATEGORY_HINT -> isBlank(parsedCommand.searchCategoryHint());
        };
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
