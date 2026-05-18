package com.pbm.command.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 사용자 명령 처리 세션(Entity).
 * <p>
 * 역할: 하나의 자연어 명령이 pre-search 보완 → 검색 → 상품 선택 → 모니터링 시작으로
 *       이어지는 전체 생명주기를 추적한다.
 * 동작: 고유 commandId(UUID)로 식별하며, 상태 전환은 정적 팩토리 메서드와
 *       의미 있는 update 메서드를 통해서만 이루어진다.
 * 연관: CommandSessionStatus, CommandSessionService, CommandSessionRepository.
 */
@Entity
@Table(name = "command_sessions")
@Getter
// Entity와 Service를 구분해서 Entity에서 컬럼값만 정의하고 모든 로직을 Service에서 구현해도 되지만 AccessLevel.PROTECTED를 함으로써 다른곳에서 객체 생성 불가
// Service에서 status를 빠뜨리거나 잘못된 조합으로 객체를 만드는 실수를 컴파일 타임에 원천 차단하는 게 핵심 목적임
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA는 기본 생성자 필요
@EntityListeners(AuditingEntityListener.class)     // 생성일/수정일 자동 기록
public class CommandSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY) // auto increment
    private Long id;

    @Column(nullable = false, unique = true, length = 36)
    private String commandId; // UUID 문자열

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String originalCommand;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private CommandSessionStatus status;

    @Column(columnDefinition = "TEXT")
    private String missingFieldsJson; // JSON 배열 문자열 (예: ["size","platform"])

    @Column(columnDefinition = "TEXT")
    private String clarificationMessage; // 사용자에게 보낼 보완 요청 메시지

    private String categoryPath; // 최종 확정된 카테고리 경로 (예: "전자기기 > 스마트폰")

    @Column(columnDefinition = "TEXT")
    private String candidatesJson; // 후보 상품 목록 JSON 문자열 (PRODUCT_SELECTION_REQUIRED 시)

    @Column(columnDefinition = "TEXT")
    private String selectedProductIdsJson; // 사용자가 선택한 productId 목록 JSON 문자열

    @Column(columnDefinition = "TEXT")
    private String validationResultJson; // 선택 상품 검증 결과 JSON 문자열

    private Integer targetPrice; // 사용자가 설정한 목표 가격 (원)

    @Column(length = 30)
    private String commandIntent; // 사용자 의도 (예: "AUTO_PURCHASE", "PRICE_CHECK")

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @Builder
    public CommandSession(
            String commandId,
            Long userId,
            String originalCommand,
            CommandSessionStatus status,
            String missingFieldsJson,
            String clarificationMessage,
            String categoryPath,
            String candidatesJson,
            String selectedProductIdsJson,
            String validationResultJson,
            Integer targetPrice,
            String commandIntent
    ) {
        this.commandId = commandId;
        this.userId = userId;
        this.originalCommand = originalCommand;
        this.status = status;
        this.missingFieldsJson = missingFieldsJson;
        this.clarificationMessage = clarificationMessage;
        this.categoryPath = categoryPath;
        this.candidatesJson = candidatesJson;
        this.selectedProductIdsJson = selectedProductIdsJson;
        this.validationResultJson = validationResultJson;
        this.targetPrice = targetPrice;
        this.commandIntent = commandIntent;
    }

    // =========================================================================
    // 정적 팩토리 메서드
    // =========================================================================

    /**
     * pre-search 보완 단계의 세션을 생성한다.
     * <p>
     * 역할: GPT 파싱 결과 필수 필드가 누락되어 사용자에게 추가 입력을 요청할 때 호출한다.
     *
     * @param userId              사용자 ID
     * @param originalCommand     사용자가 입력한 원본 명령문
     * @param missingFieldsJson   누락된 필드 목록 (JSON 배열 문자열)
     * @param clarificationMessage 사용자에게 보여줄 보완 요청 메시지
     * @return PRE_SEARCH_CLARIFICATION 상태의 CommandSession
     */
    public static CommandSession createPreSearchClarification(
            Long userId,
            String originalCommand,
            String missingFieldsJson,
            String clarificationMessage
    ) {
        return CommandSession.builder()
                .commandId(UUID.randomUUID().toString())
                .userId(userId)
                .originalCommand(originalCommand)
                .status(CommandSessionStatus.PRE_SEARCH_CLARIFICATION)
                .missingFieldsJson(missingFieldsJson)
                .clarificationMessage(clarificationMessage)
                .build();
    }

    /**
     * 검색 단계로 바로 진입하는 세션을 생성한다.
     * <p>
     * 역할: GPT 파싱 결과가 완전하여 pre-search 보완 없이 바로 검색을 시작할 때 호출한다.
     *
     * @param userId          사용자 ID
     * @param originalCommand 사용자가 입력한 원본 명령문
     * @return SEARCHING 상태의 CommandSession
     */
    public static CommandSession createSearching(
            Long userId,
            String originalCommand
    ) {
        return CommandSession.builder()
                .commandId(UUID.randomUUID().toString())
                .userId(userId)
                .originalCommand(originalCommand)
                .status(CommandSessionStatus.SEARCHING)
                .build();
    }

    // =========================================================================
    // 상태 변경 메서드
    // =========================================================================

    /**
     * 상품 선택 필요(PRODUCT_SELECTION_REQUIRED) 상태로 전환한다.
     * <p>
     * 역할: 검색 결과에 대해 사용자에게 후보 상품 선택이 필요할 때 호출한다.
     *
     * @param missingFieldsJson   새롭게 누락된 필드 목록 (JSON 배열 문자열)
     * @param clarificationMessage 사용자에게 보여줄 보완 요청 메시지
     * @param categoryPath        검색을 통해 확정된 카테고리 경로
     * @param candidatesJson      후보 상품 목록 JSON 문자열 (null 가능)
     * @param targetPrice         사용자 목표 가격 (null 가능)
     * @param commandIntent       사용자 의도 문자열 (null 가능)
     */
    public void toProductSelectionRequired(
            String missingFieldsJson,
            String clarificationMessage,
            String categoryPath,
            String candidatesJson,
            Integer targetPrice,
            String commandIntent
    ) {
        this.status = CommandSessionStatus.PRODUCT_SELECTION_REQUIRED;
        this.missingFieldsJson = missingFieldsJson;
        this.clarificationMessage = clarificationMessage;
        this.categoryPath = categoryPath;
        this.candidatesJson = candidatesJson;
        this.selectedProductIdsJson = null;
        this.validationResultJson = null;
        this.targetPrice = targetPrice;
        this.commandIntent = commandIntent;
    }

    /**
     * 선택 상품 실시간 검증(PRICE_VALIDATING) 상태로 전환한다.
     * <p>
     * 역할: 사용자가 여러 후보 상품을 선택한 뒤, price-service가 단건 재조회 검증을
     *       수행하는 동안 세션 상태를 명확하게 표현한다.
     *
     * @param selectedProductIdsJson 사용자가 선택한 productId 목록 JSON 문자열
     */
    public void toPriceValidating(String selectedProductIdsJson) {
        this.status = CommandSessionStatus.PRICE_VALIDATING;
        this.selectedProductIdsJson = selectedProductIdsJson;
        this.validationResultJson = null;
    }

    /**
     * 기존 구독 갱신/재시작 여부에 대한 사용자 확인 상태로 전환한다.
     * <p>
     * 역할: price-service가 동일 상품의 기존 구독을 감지하여 사용자 확인이 필요하다고
     *       판단했을 때, polling 응답에서 확인 문구와 대상 상품을 노출할 수 있도록 한다.
     *
     * @param validationResultJson 중복 구독 확인용 결과 JSON 문자열
     */
    public void toResubscribeConfirmationRequired(String validationResultJson) {
        this.status = CommandSessionStatus.RESUBSCRIBE_CONFIRMATION_REQUIRED;
        this.validationResultJson = validationResultJson;
    }

    /**
     * 모니터링 시작 상태로 전환한다.
     * <p>
     * 역할: 모든 확인 과정이 끝나고 실제 가격 모니터링이 시작될 때 호출한다.
     */
    public void toMonitoringStarted() {
        this.status = CommandSessionStatus.MONITORING_STARTED;
    }

    /**
     *  브라우저 구매 진행 상태로 전환한다.
     *  역할: AUTO_PURCHASE 의도에서 가격 검증은 끝났고, 이제 extension이 실제 브라우저 구매 액션을 계속 수행할떄 사용한다.
     */
    public void toBrowserPurchaseInProgress(){
        this.status = CommandSessionStatus.BROWSER_PURCHASE_IN_PROGRESS;
    }

    /**
     * 검증 완료 결과를 저장하고 세션 상태를 최종 상태로 전환한다.
     *
     * @param nextStatus           검증 완료 후 세션 상태
     * @param validationResultJson triggered / monitoring 결과 JSON 문자열
     */
    public void completeValidation(
            CommandSessionStatus nextStatus,
            String validationResultJson
    ) {
        this.status = nextStatus;
        this.validationResultJson = validationResultJson;
    }

    /**
     * 검색(SEARCHING) 상태로 전환한다.
     * <p>
     * 역할: pre-search/product-selection-required 보완 후 재파싱 결과가 완전하여
     *       검색을 시작할 수 있을 때 호출한다.
     *       보완 관련 필드는 모두 초기화한다.
     */
    public void toSearching() {
        this.status = CommandSessionStatus.SEARCHING;
        this.missingFieldsJson = null;
        this.clarificationMessage = null;
        this.categoryPath = null;
        this.candidatesJson = null;
        this.selectedProductIdsJson = null;
        this.validationResultJson = null;
    }

    /**
     * pre-search 보완(PRE_SEARCH_CLARIFICATION) 상태로 전환한다.
     * <p>
     * 역할: 재파싱 결과에도 여전히 필수 필드가 누락되어
     *       다시 사용자 보완이 필요할 때 호출한다.
     *
     * @param missingFieldsJson   새롭게 누락된 필드 목록 (JSON 배열 문자열)
     * @param clarificationMessage 사용자에게 보여줄 보완 요청 메시지
     */
    public void toPreSearchClarification(
            String missingFieldsJson,
            String clarificationMessage
    ) {
        this.status = CommandSessionStatus.PRE_SEARCH_CLARIFICATION;
        this.missingFieldsJson = missingFieldsJson;
        this.clarificationMessage = clarificationMessage;
        this.categoryPath = null;
        this.candidatesJson = null;
        this.selectedProductIdsJson = null;
        this.validationResultJson = null;
    }

    /**
     * 원본 명령문을 업데이트한다.
     * <p>
     * 역할: 보완 제출 시 originalCommand + clarificationInput을 병합한 결과로
     *       세션의 명령문을 갱신할 때 사용한다.
     *
     * @param mergedCommand 병합된 명령문
     */
    public void updateOriginalCommand(String mergedCommand) {
        this.originalCommand = mergedCommand;
    }
}
