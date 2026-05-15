package com.pbm.price.service;

import com.pbm.price.dto.event.ParsedCommandSnapshot;
import com.pbm.price.dto.response.SearchResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * post-search 최소 안전 검증 서비스.
 *
 * 역할: PriceTopicConsumer가 검색 결과를 신뢰하기 전에,
 *       명령 컨텍스트(intent, parsedCommandSnapshot)와 결과물을 교차 검증하여
 *       잘못된 자동 결제/알림 발행을 1차 방어한다.
 *
 * 중요: 이 서비스는 pre-search 검증(command-service의 CommandFieldEvaluationService)을
 *       대체하지 않으며, post-search 시점에 추가로 안전장치를 더하는 1차 safety gate 역할이다.
 *       향후 session/event back-propagation 등으로 확장될 수 있다.
 *
 * 동작 규칙 (현재는 최소한의 안전 규칙만 구현):
 *   1. 검색 결과가 비어있으면 NO_MATCH
 *   2. AUTO_PURCHASE 의도에서 결과가 2개 이상이면 PRODUCT_SELECTION_REQUIRED
 *   3. AUTO_PURCHASE + SHOES 카테고리에서 첫 결과 타이틀에 사이즈 정보 부재 +
 *      명령 스냅샷 사이즈도 비어있으면 PRODUCT_SELECTION_REQUIRED
 *   4. 그 외는 PROCEED
 *
 * 연관: PostSearchValidationResult, PriceTopicConsumer.
 */
@Slf4j
@Service
public class PostSearchValidationService {

    // 한국 신발 사이즈 패턴: 220~300 사이의 3자리 숫자, 또는 숫자+mm/cm 단위
    // 예: "260", "270", "240mm", "27cm"
    private static final Pattern SHOE_SIZE_PATTERN = Pattern.compile(
            "\\b(2[2-9]\\d|300)\\b|\\d+\\s*(mm|MM|cm|CM)"
    );

    /**
     * 검색 결과에 대한 post-search 최소 안전 검증을 수행한다.
     *
     * @param results  검색 결과 목록
     * @param intent   사용자 의도 (PriceRequestEventPayload.intent, null 가능)
     * @param snapshot 파싱된 명령 스냅샷 (null 가능)
     * @return 검증 결과 (PROCEED / NO_MATCH / PRODUCT_SELECTION_REQUIRED)
     */
    public PostSearchValidationResult validate(
            List<SearchResponse> results,
            String intent,
            ParsedCommandSnapshot snapshot) {

        // 규칙 1: 검색 결과가 비어있으면 NO_MATCH
        if (results == null || results.isEmpty()) {
            log.info("[PostSearchValidation] 검색 결과 없음 (NO_MATCH)");
            return PostSearchValidationResult.noMatch("검색 결과가 없습니다");
        }

        // AUTO_PURCHASE 의도에 대해서만 추가 검증 수행
        // PRICE_CHECK, PRICE_TRACK 등은 결과 1건이면 그대로 진행
        if (!"AUTO_PURCHASE".equals(intent)) {
            return PostSearchValidationResult.proceed();
        }

        List<String> missingFields = new ArrayList<>();

        // 규칙 2: AUTO_PURCHASE 의도에서 결과가 2개 이상이면
        // 어떤 상품을 자동 구매할지 명확하지 않으므로 보류
        // 후보 상품 목록(candidates)을 함께 반환하여 사용자 선택을 받을 수 있도록 한다
        if (results.size() > 1) {
            log.warn("[PostSearchValidation] AUTO_PURCHASE 의도이나 검색 결과가 {}개입니다 - 명확성 부족 (PRODUCT_SELECTION_REQUIRED)",
                    results.size());
            missingFields.add("searchResultsCount");
            return PostSearchValidationResult.selectionRequired(
                    missingFields,
                    String.format("AUTO_PURCHASE 의도이나 검색 결과가 %d개로 명확하지 않습니다", results.size()),
                    results  // 후보 상품 목록 포함
            );
        }

        // 규칙 3: AUTO_PURCHASE + SHOES 카테고리에서 사이즈 정보 확인
        // 명령 스냅샷에 size가 없고, 첫 번째 결과 타이틀에도 사이즈 정보가 없으면
        // 사용자가 원하는 사이즈를 알 수 없으므로 보류
        SearchResponse firstResult = results.get(0);
        boolean isShoes = snapshot != null && "SHOES".equals(snapshot.productCategory());
        boolean missingSizeInSnapshot = snapshot == null
                || snapshot.size() == null
                || snapshot.size().isBlank();

        if (isShoes && missingSizeInSnapshot) {
            String title = firstResult.title() != null ? firstResult.title() : "";
            boolean titleHasSizeInfo = SHOE_SIZE_PATTERN.matcher(title).find();

            if (!titleHasSizeInfo) {
                log.warn("[PostSearchValidation] SHOES 카테고리 상품이나 타이틀에 사이즈 정보가 없고 " +
                                "명령 스냅샷에도 size가 없습니다 - title: '{}' (PRODUCT_SELECTION_REQUIRED)",
                        title);
                missingFields.add("size");
                return PostSearchValidationResult.selectionRequired(
                        missingFields,
                        "SHOES 카테고리 상품이나 사이즈 정보가 누락되었습니다"
                );
            }
        }

        // 모든 검증 통과
        return PostSearchValidationResult.proceed();
    }
}
