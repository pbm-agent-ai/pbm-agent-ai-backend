package com.pbm.command.exception;

/**
 * PRODUCT_SELECTION_REQUIRED fast-path에서 유효하지 않은 productId가 선택되었을 때 발생하는 예외.
 *
 * 역할: 사용자가 선택한 상품 목록이 세션의 candidates 목록과 맞지 않거나,
 *       현재 세션 상태에서 허용되지 않은 상품 선택 요청이 들어왔을 때 던진다.
 * 동작: GlobalExceptionHandler가 HTTP 400(Bad Request)로 변환하여 응답한다.
 * 연관: CommandExecutionService, GlobalExceptionHandler.
 */
public class InvalidProductSelectionException extends RuntimeException {

    public InvalidProductSelectionException(String message) {
        super(message);
    }

    public InvalidProductSelectionException(String message, Throwable cause) {
        super(message, cause);
    }
}
