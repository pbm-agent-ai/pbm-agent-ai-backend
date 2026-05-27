package com.pbm.price.exception;

import com.pbm.price.common.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 전역 예외 처리 핸들러
 * 컨트롤러 계층에서 발생하는 예외를 일관된 {@link ApiResponse} 형식으로 변환하여 응답한다.
 *
 * 책임:
 * - {@link ExternalApiException} → 503 Service Unavailable (외부 API 장애)
 * - 그 외 {@link RuntimeException} → 500 Internal Server Error (미처리 예외)
 *
 * 모든 에러 응답은 {@code ApiResponse<Void>}로 감싸며,
 * {@code success=false, data=null, message=에러 메시지} 형태의 JSON을 반환한다.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 모니터링 구독 미존재 예외 처리 → 404 Not Found
     */
    @ExceptionHandler(SubscriptionNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleSubscriptionNotFoundException(SubscriptionNotFoundException e) {
        log.warn("모니터링 구독 미존재: {}", e.getMessage());
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error(e.getMessage()));
    }

    /**
     * 타인의 모니터링 구독 접근 예외 처리 → 403 Forbidden
     */
    @ExceptionHandler(SubscriptionAccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleSubscriptionAccessDeniedException(SubscriptionAccessDeniedException e) {
        log.warn("모니터링 구독 접근 거부: {}", e.getMessage());
        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.error(e.getMessage()));
    }

    /**
     * 잘못된 수정 요청 예외 처리 → 400 Bad Request
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgumentException(IllegalArgumentException e) {
        log.warn("잘못된 요청 파라미터: {}", e.getMessage());
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(e.getMessage()));
    }

    /**
     * 외부 API 호출 실패 예외 처리
     * Circuit Breaker OPEN 또는 재시도 모두 실패한 경우 발생하는
     * {@link ExternalApiException}을 HTTP 503 Service Unavailable 응답으로 변환한다.
     *
     * 외부 의존성 장애는 일시적인 문제일 가능성이 높으므로,
     * 5xx 계열 중에서도 가장 적절한 503(서비스 이용 불가)을 선택한다.
     *
     * @param e ExternalApiException (외부 API 호출 불가 예외)
     * @return 503 상태코드와 ApiResponse 에러 JSON 본문
     */
    @ExceptionHandler(ExternalApiException.class)
    public ResponseEntity<ApiResponse<Void>> handleExternalApiException(ExternalApiException e) {
        log.error("외부 API 호출 실패 처리 - 원인: {}", e.getMessage());

        // 외부 API 장애임을 사용자에게 명확히 전달하는 메시지
        ApiResponse<Void> errorResponse = ApiResponse.error(
                "외부 API 서비스를 사용할 수 없습니다. 잠시 후 다시 시도해주세요.");

        return ResponseEntity
                .status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(errorResponse);
    }

    /**
     * 예상치 못한 런타임 예외 처리
     * 명시적으로 처리되지 않은 모든 {@link RuntimeException}을
     * HTTP 500 Internal Server Error로 변환한다.
     *
     * @param e RuntimeException (미처리 예외)
     * @return 500 상태코드와 ApiResponse 에러 JSON 본문
     */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ApiResponse<Void>> handleRuntimeException(RuntimeException e) {
        log.error("예상치 못한 서버 오류 발생", e);

        ApiResponse<Void> errorResponse = ApiResponse.error(
                "서버 내부 오류가 발생했습니다. 관리자에게 문의해주세요.");

        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(errorResponse);
    }
}
