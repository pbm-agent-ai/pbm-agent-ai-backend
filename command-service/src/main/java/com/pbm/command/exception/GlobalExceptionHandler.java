package com.pbm.command.exception;

import com.pbm.command.common.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * command-service 전역 예외 처리 핸들러.
 *
 * 역할: 컨트롤러 계층에서 발생한 예외를 일관된 ApiResponse 형식으로 변환한다.
 * 동작: external-api-service 프록시 장애는 503으로, 잘못된 productId 선택은 400으로,
 *       그 외 미처리 런타임 예외는 500으로 응답한다.
 * 연관: ExternalApiProxyException, InvalidProductSelectionException, ApiResponse.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * external-api-service 프록시 호출 실패 예외 처리.
     *
     * @param e 프록시 호출 실패 예외
     * @return 503 Service Unavailable 응답
     */
    @ExceptionHandler(ExternalApiProxyException.class)
    public ResponseEntity<ApiResponse<Void>> handleExternalApiProxyException(ExternalApiProxyException e) {
        log.error("external-api-service 프록시 호출 실패", e);

        return ResponseEntity
                .status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResponse.error("외부 AI 파싱 서비스를 사용할 수 없습니다. 잠시 후 다시 시도해주세요."));
    }

    /**
     * 명령 세션을 찾을 수 없을 때 발생하는 예외 처리.
     *
     * @param e 세션 미발견 예외
     * @return 404 Not Found 응답
     */
    @ExceptionHandler(CommandSessionNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleCommandSessionNotFoundException(CommandSessionNotFoundException e) {
        log.warn("명령 세션을 찾을 수 없음: {}", e.getMessage());

        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error(e.getMessage()));
    }

    /**
     * PRODUCT_SELECTION_REQUIRED fast-path에서 유효하지 않은 productId 선택 시 예외 처리.
     *
     * @param e 유효하지 않은 상품 선택 예외
     * @return 400 Bad Request 응답
     */
    @ExceptionHandler(InvalidProductSelectionException.class)
    public ResponseEntity<ApiResponse<Void>> handleInvalidProductSelectionException(InvalidProductSelectionException e) {
        log.warn("유효하지 않은 상품 선택: {}", e.getMessage());

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(e.getMessage()));
    }

    /**
     * 예상하지 못한 런타임 예외 처리.
     *
     * @param e 런타임 예외
     * @return 500 Internal Server Error 응답
     */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ApiResponse<Void>> handleRuntimeException(RuntimeException e) {
        log.error("예상치 못한 서버 오류 발생", e);

        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("서버 내부 오류가 발생했습니다. 관리자에게 문의해주세요."));
    }
}
