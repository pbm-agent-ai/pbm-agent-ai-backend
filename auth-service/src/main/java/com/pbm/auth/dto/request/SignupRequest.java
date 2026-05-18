package com.pbm.auth.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// 역할: 회원가입 입력값을 담고, 최소 길이/형식을 검증
// 수정: 서비스 로직 전에 잘못된 요청을 걸러 서버 오류 대신 400 응답으로 처리
@Schema(description = "회원가입 요청 DTO")
public record SignupRequest(
        @Schema(description = "회원 이메일", example = "user@example.com")
        @NotBlank(message = "이메일은 필수입니다.")
        @Email(message = "올바른 이메일 형식이 아닙니다.")
        String email,

        @Schema(description = "비밀번호", example = "password1234")
        @NotBlank(message = "비밀번호는 필수입니다.")
        @Size(min = 8, message = "비밀번호는 8자 이상이어야 합니다.")
        String password,

        @Schema(description = "사용자 닉네임", example = "coffeepeanut")
        @NotBlank(message = "닉네임은 필수입니다.")
        @Size(min = 2, max = 20, message = "닉네임은 2자 이상 20자 이하여야 합니다.")
        String nickname
) {
}
