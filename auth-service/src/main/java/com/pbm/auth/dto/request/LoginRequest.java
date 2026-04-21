package com.pbm.auth.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

// 역할: 로그인 요청값을 담고, 컨트롤러 진입 전에 기본 형식을 검증
// 수정: 빈 값이나 잘못된 이메일 형식이 바로 400 응답이 되도록 검증 추가
public record LoginRequest(
        @NotBlank(message = "이메일은 필수입니다.")
        @Email(message = "올바른 이메일 형식이 아닙니다.")
        String email,

        @NotBlank(message = "비밀번호는 필수입니다.")
        String password
) {
}
