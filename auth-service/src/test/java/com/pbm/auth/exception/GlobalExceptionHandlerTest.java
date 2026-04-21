package com.pbm.auth.exception;

import com.pbm.auth.common.ApiResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GlobalExceptionHandler의 예외 -> ApiResponse 변환 규칙을 단위 테스트한다.
 *
 * 역할: 각 예외 핸들러 메서드가 HTTP 상태코드/메시지를 올바르게 매핑하는지 확인
 * 동작: 예외 객체를 직접 만들어 핸들러 메서드 호출 -> ResponseEntity<ApiResponse> 검증
 * 연관: GlobalExceptionHandler, AuthException, ApiResponse
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler globalExceptionHandler = new GlobalExceptionHandler();

    @Test
    @DisplayName("handleAuthException: AuthException의 HttpStatus와 메시지를 그대로 응답한다")
    void handleAuthException_returnsStatusAndApiErrorMessage() throws Exception {
        // given
        AuthException authException = new AuthException("이미 사용 중인 이메일입니다.", HttpStatus.CONFLICT);

        // when
        ResponseEntity<ApiResponse<Void>> response = globalExceptionHandler.handleAuthException(authException);

        // then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().success()).isFalse();
        assertThat(response.getBody().data()).isNull();
        assertThat(response.getBody().message()).isEqualTo("이미 사용 중인 이메일입니다.");
    }

    @Test
    @DisplayName("handleValidationException: 검증 실패 시 400과 첫 번째 필드 에러 메시지를 반환한다")
    void handleValidationException_returns400WithFirstFieldError() throws Exception {
        // given: 필드 에러를 2개 넣고, 핸들러가 첫 번째 메시지를 선택하는지 검증한다.
        Method method = ValidationDummyController.class.getDeclaredMethod("dummy", ValidationRequest.class);
        MethodParameter methodParameter = new MethodParameter(method, 0);

        ValidationRequest target = new ValidationRequest("", "");
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(target, "validationRequest");
        bindingResult.addError(new FieldError("validationRequest", "email", "이메일은 필수입니다."));
        bindingResult.addError(new FieldError("validationRequest", "nickname", "닉네임은 필수입니다."));

        MethodArgumentNotValidException validationException =
                new MethodArgumentNotValidException(methodParameter, bindingResult);

        // when
        ResponseEntity<ApiResponse<Void>> response = globalExceptionHandler.handleValidationException(validationException);

        // then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().success()).isFalse();
        assertThat(response.getBody().data()).isNull();
        assertThat(response.getBody().message()).isEqualTo("이메일은 필수입니다.");
    }

    @Test
    @DisplayName("handleException: 처리되지 않은 예외는 500과 공통 서버 오류 메시지를 반환한다")
    void handleException_returns500WithGenericMessage() throws Exception {
        // given
        RuntimeException exception = new RuntimeException("unexpected");

        // when
        ResponseEntity<ApiResponse<Void>> response = globalExceptionHandler.handleException(exception);

        // then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().success()).isFalse();
        assertThat(response.getBody().data()).isNull();
        assertThat(response.getBody().message()).isEqualTo("서버 오류가 발생했습니다.");
    }

    // MethodParameter 생성을 위해 필요한 더미 컨트롤러 메서드
    static class ValidationDummyController {
        @SuppressWarnings("unused")
        void dummy(ValidationRequest request) {
        }
    }

    // Bean Validation 예외를 유도하기 위한 테스트 전용 요청 DTO
    record ValidationRequest(
            String email,
            String nickname
    ) {
    }
}
