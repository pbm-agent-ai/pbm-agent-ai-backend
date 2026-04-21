package com.pbm.auth.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

// - AuthException.java: “어떤 비즈니스 에러가 났는지”를 표현하는 클래스
/*
- RuntimeException 상속 + HttpStatus status 보유
- 메시지 + 상태코드를 같이 들고 다님
- emailAlreadyExists(), invalidCredentials() 같은 정적 팩토리로 공통 에러를 표준화
- 서비스 코드에서는 throw AuthException.invalidCredentials();처럼 깔끔하게 사용 가능
 */
@Getter
public class AuthException extends RuntimeException {

    private final HttpStatus status;

    public AuthException(String message, HttpStatus status) {
        super(message);
        this.status = status;
    }

    // 자주 쓰는 예외 정적 팩토리 메서드
    public static AuthException emailAlreadyExists() {
        return new AuthException("이미 사용 중인 이메일입니다.", HttpStatus.CONFLICT);
    }

    public static AuthException invalidCredentials() {
        return new AuthException("이메일 또는 비밀번호가 올바르지 않습니다.", HttpStatus.UNAUTHORIZED);
    }

    public static AuthException invalidToken() {
        return new AuthException("유효하지 않은 토큰입니다.", HttpStatus.UNAUTHORIZED);
    }

    public static AuthException userNotFound() {
        return new AuthException("사용자를 찾을 수 없습니다.", HttpStatus.NOT_FOUND);
    }

    public static AuthException wrongPassword(){
        return new AuthException("현재 비밀번호가 일치하지 않습니다.", HttpStatus.BAD_REQUEST);
    }
}
