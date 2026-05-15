package com.pbm.command.service;

import com.pbm.command.dto.event.ParsedCommandSnapshot;
import com.pbm.command.dto.event.PriceRequestEvent;
import com.pbm.command.dto.event.PriceRequestEventPayload;
import com.pbm.command.dto.request.PriceCheckRequest;
import com.pbm.command.dto.response.ParsedCommand;
import com.pbm.command.dto.response.PriceCheckResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;


/**
 * 가격 확인 요청을 Kafka price-topic으로 발행하는 서비스.
 *
 * 역할: API 요청을 Kafka 이벤트 형식으로 변환하여 price-service로 전달한다.
 * 동작: 요청 DTO를 받아 PriceRequestEvent를 생성한 뒤 price-topic으로 발행하고,
 *       발행 결과를 응답 DTO로 반환한다.
 * 연관: PriceCheckRequest, PriceCheckResponse, PriceRequestEvent, KafkaTemplate.
 */
@Service
// 가격 요청 확인을 kafka price-topic으로 발행하는 서비스
public class PriceRequestService {

    // KafkaTemplate<Key 타입, Value 타입>
    // Key = String, Value = PriceRequestEvent(이벤트 객체)
    private final KafkaTemplate<String, PriceRequestEvent> kafkaTemplate;
    private final String priceTopic;

    public PriceRequestService(
            @Qualifier("priceRequestKafkaTemplate")
            KafkaTemplate<String, PriceRequestEvent> kafkaTemplate,
            @Value("${app.kafka.topics.price-topic}") String priceTopic     // @Value("{app.kafka.topics.price-topic}") - application.yml에서 토픽명을 읽어옴
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.priceTopic = priceTopic;
    }

    /**
     * 가격 확인 요청을 Kafka로 발행한다.
     *
     * @param request 사용자 가격 확인 요청
     * @return 발행 결과 응답 DTO
     */
    public PriceCheckResponse publishPriceCheckRequest(PriceCheckRequest request) {
        String eventId = UUID.randomUUID().toString();      // 이벤트마다 고유 ID 생성

        PriceRequestEvent event = new PriceRequestEvent(
                eventId,                                // 이벤트 고유 ID
                "PRICE_CHECK_REQUEST",                  // 이벤트 타입
                Instant.now(),                          // 발생 시각
                "command-service",                      // 발행 서비스명
                new PriceRequestEventPayload(
                        request.userId(),               // 누가 요청했는지
                        request.keyword(),              // 검색 키워드
                        request.targetPrice(),          // 목표 가격
                        request.platform() != null ? request.platform().name() : null,  // NAVER or ALIEXPRESS or null
                        request.currency(),
                        null,                           // commandId - PriceCheckRequest 경로에서는 미사용
                        null,                           // intent - PriceCheckRequest 경로에서는 미사용
                        null,                           // parsedCommandSnapshot - PriceCheckRequest 경로에서는 미사용
                        null,                           // productUrl - Phase 1 준비
                        null,                           // searchKeyword - Phase 1 준비
                        null                            // productUrls - Phase 2 준비
                )
        );

        // Kafka 메시지 Key는 userId로 지정한다.
        // 같은 사용자의 요청을 추적하거나 파티션 분배 기준으로 활용하기 쉽다.
        kafkaTemplate.send(priceTopic, String.valueOf(request.userId()), event);

        return new PriceCheckResponse(
                eventId,
                priceTopic,
                "price-topic 발행 성공"
        );
    }

    /**
     * 자연어 파싱 결과를 바탕으로 가격 확인 요청을 Kafka로 발행한다.
     * <p>
     * 외부에서 전달받은 commandId를 그대로 사용하여 이벤트 페이로드에 포함시킨다.
     * keyword는 기존과 동일하게 buildKeyword로 조합한다.
     *
     * @param userId        요청 사용자 ID
     * @param intent        사용자 의도 (CommandIntent name 문자열)
     * @param parsedCommand GPT가 추출한 구조화 결과
     * @param commandId     명령 고유 식별자 (UUID, 세션에서 생성된 값 재사용)
     * @return 발행 결과 응답 DTO
     */
    public PriceCheckResponse publishParsedCommandRequest(Long userId, String intent, ParsedCommand parsedCommand, String commandId) {

        // GPT가 추출한 필드들을 조합해서 검색 키워드 문자열 생성
        // ex) productName="나이키 조던" + color="블랙" + size="270"  → "나이키 조던 블랙 270"
        String keyword = buildKeyword(parsedCommand);

        // ParsedCommandSnapshot 구성 (enum은 name 문자열로 평탄화)
        ParsedCommandSnapshot snapshot = new ParsedCommandSnapshot(
                parsedCommand.productCategory() != null ? parsedCommand.productCategory().name() : null,
                parsedCommand.productName(),
                parsedCommand.brand(),
                parsedCommand.line(),
                parsedCommand.model(),
                parsedCommand.color(),
                parsedCommand.size(),
                parsedCommand.platform() != null ? parsedCommand.platform().name() : null,
                parsedCommand.maxPrice(),
                parsedCommand.minPrice(),
                parsedCommand.currency(),
                null, // productUrl — Phase 1 준비, 후순위
                null, // searchKeyword — Phase 1 준비, 후순위
                parsedCommand.searchCategoryHint()
        );

        String eventId = UUID.randomUUID().toString();

        PriceRequestEvent event = new PriceRequestEvent(
                eventId,
                "PRICE_CHECK_REQUEST",
                Instant.now(),
                "command-service",
                new PriceRequestEventPayload(
                        userId,
                        keyword,
                        parsedCommand.maxPrice(),
                        parsedCommand.platform() != null ? parsedCommand.platform().name() : null,
                        parsedCommand.currency(),
                        commandId,
                        intent,
                        snapshot,
                        null, // productUrl — Phase 1 준비, 후순위
                        null, // searchKeyword — Phase 1 준비, 후순위
                        null  // productUrls — Phase 2 준비, 후순위
                )
        );

        kafkaTemplate.send(priceTopic, String.valueOf(userId), event);

        return new PriceCheckResponse(
                eventId,
                priceTopic,
                "price-topic 발행 성공"
        );
    }

    /**
     * 사용자가 직접 입력한 상품 URL 목록을 price-topic으로 발행한다.
     *
     * 역할: 1차 검색 결과 대신 사용자가 고른 상품 링크들을
     *       price-service가 플랫폼별 URL 검증 로직으로 처리할 수 있도록 전달한다.
     * 동작:
     * 1. commandId / targetPrice / intent를 기존 세션 값으로 유지한다.
     * 2. keyword/searchKeyword에는 1차 검색에서 실제 사용했던 검색 키워드를 넣는다.
     * 3. productUrls에는 사용자가 입력한 링크 목록을 그대로 담아 발행한다.
     *
     * @param userId         요청 사용자 ID
     * @param intent         사용자 의도 문자열
     * @param targetPrice    목표 가격
     * @param commandId      기존 명령 세션 식별자
     * @param productUrls    사용자가 입력한 상품 URL 목록
     * @param searchKeyword  1차 검색에서 실제 사용했던 검색 키워드
     * @param platform       링크가 속한 플랫폼
     * @return 발행 결과 응답 DTO
     */
    public PriceCheckResponse publishProductUrlRequest(
            Long userId,
            String intent,
            Integer targetPrice,
            String commandId,
            List<String> productUrls,
            String searchKeyword,
            String platform
    ) {
        String eventId = UUID.randomUUID().toString();

        PriceRequestEvent event = new PriceRequestEvent(
                eventId,
                "PRICE_CHECK_REQUEST",
                Instant.now(),
                "command-service",
                new PriceRequestEventPayload(
                        userId,
                        searchKeyword,
                        targetPrice,
                        platform,
                        "KRW",
                        commandId,
                        intent,
                        null,
                        null,
                        searchKeyword,
                        productUrls
                )
        );

        kafkaTemplate.send(priceTopic, String.valueOf(userId), event);

        return new PriceCheckResponse(
                eventId,
                priceTopic,
                "price-topic 발행 성공"
        );
    }

    /**
     * 파싱된 명령에서 검색 키워드를 조합한다.
     *
     * brand, line, productName, model, color, size 필드를 순서대로 공백 한 칸으로 이어붙이며,
     * null 또는 빈 값이면 해당 필드는 건너뛴다.
     *
     * 또한 앞에서 이미 포함된 값은 뒤에서 다시 붙이지 않는다.
     * 반대로 뒤에서 들어오는 더 긴 표현이 앞에서 붙인 짧은 표현을 완전히 포함하면,
     * 기존 짧은 표현을 제거하고 더 긴 표현으로 치환한다.
     * 예를 들어 brand="QCY", productName="QCY T13 PRO 블랙"이면,
     * brand를 따로 유지하지 않고 productName 하나만 남긴다.
     *
     * 예: "나이키 조던" + null + "블랙" + "270" → "나이키 조던 블랙 270"
     * 예: brand="애플", productName="아이폰 15 프로", model="256GB" → "애플 아이폰 15 프로 256GB"
     * 예: "QCY T13 PRO 블랙" + "T13 PRO" + "블랙" + null → "QCY T13 PRO 블랙"
     *
     * @param parsedCommand GPT가 추출한 구조화 결과
     * @return 조합된 검색 키워드
     */
    private String buildKeyword(ParsedCommand parsedCommand) {
        List<String> keywordParts = new ArrayList<>();

        // 브랜드 -> 라인 -> 상품명 -> 모델 -> 색상 -> 사이즈 순으로 검색어를 강화한다.
        appendIfNotDuplicated(keywordParts, parsedCommand.brand());
        appendIfNotDuplicated(keywordParts, parsedCommand.line());
        appendIfNotDuplicated(keywordParts, parsedCommand.productName());
        appendIfNotDuplicated(keywordParts, parsedCommand.model());
        appendIfNotDuplicated(keywordParts, parsedCommand.color());
        appendIfNotDuplicated(keywordParts, parsedCommand.size());

        return String.join(" ", keywordParts);
    }

    /**
     * 이미 앞쪽 키워드에 포함된 값이면 중복 추가하지 않는다.
     *
     * 예를 들어 현재 키워드가 "QCY T13 PRO 블랙"인 상태에서
     * "T13 PRO"를 다시 붙이면 검색 품질만 떨어질 수 있으므로 생략한다.
     */
    private void appendIfNotDuplicated(List<String> keywordParts, String value) {
        if (value == null || value.isBlank()) {
            return;
        }

        String candidate = value.trim();
        String normalizedCandidate = normalizeKeywordPart(candidate);

        // 이미 더 긴 표현 안에 포함되는 값이면 중복 추가하지 않는다.
        for (String existingPart : keywordParts) {
            String normalizedExistingPart = normalizeKeywordPart(existingPart);
            if (normalizedExistingPart.contains(normalizedCandidate)) {
                return;
            }
        }

        // 새 표현이 기존의 짧은 표현을 모두 포함하면 더 긴 표현으로 치환한다.
        keywordParts.removeIf(existingPart -> normalizedCandidate.contains(normalizeKeywordPart(existingPart)));
        keywordParts.add(candidate);
    }

    private String normalizeKeywordPart(String value) {
        return value == null
                ? ""
                : value.trim()
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
    }
}
