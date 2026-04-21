package com.pbm.auth.exception;

import com.pbm.auth.common.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

// - GlobalExceptionHandler.java: “그 에러를 HTTP 응답으로 어떻게 내려줄지”를 담당하는 클래스
// - @RestControllerAdvice로 전역 예외 처리
@RestControllerAdvice
public class GlobalExceptionHandler {

    // 역할: 예외를 일관된 JSON 형태로 바꿔 프론트가 처리하기 쉽게 만듦
    // 수정: DTO 검증 실패를 400으로 내려주도록 검증 예외 처리를 추가
    /*
     - e.getStatus()로 상태코드 결정
     - ApiResponse.error(e.getMessage())로 공통 JSON 포맷 응답
     */
    @ExceptionHandler(AuthException.class)
    public ResponseEntity<ApiResponse<Void>> handleAuthException(AuthException e) {
        return ResponseEntity
                .status(e.getStatus())
                .body(ApiResponse.error(e.getMessage()));
    }

    // - DTO 검증 실패를 400으로 변환
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidationException(MethodArgumentNotValidException e) {
        String message = e.getBindingResult()
                .getFieldErrors()
                .stream()
                .findFirst()
                .map(FieldError::getDefaultMessage)
                .orElse("잘못된 요청입니다.");

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(message));
    }

    // - 나머지 예외는 500으로 안전하게 처리
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException(Exception e) {
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("서버 오류가 발생했습니다."));
    }
}
