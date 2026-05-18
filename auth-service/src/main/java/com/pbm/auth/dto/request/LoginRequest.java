package com.pbm.auth.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

// 역할: 로그인 요청값을 담고, 컨트롤러 진입 전에 기본 형식을 검증
// 수정: 빈 값이나 잘못된 이메일 형식이 바로 400 응답이 되도록 검증 추가
@Schema(description = "로그인 요청 DTO")
public record LoginRequest(
        @Schema(description = "로그인 이메일", example = "user@example.com")
        @NotBlank(message = "이메일은 필수입니다.")
        @Email(message = "올바른 이메일 형식이 아닙니다.")
        String email,

        @Schema(description = "비밀번호", example = "password1234")
        @NotBlank(message = "비밀번호는 필수입니다.")
        String password
) {
}
