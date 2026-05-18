package com.pbm.auth.dto.response;

import com.pbm.auth.domain.User;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "사용자 정보 응답 DTO")
public record UserResponse (
        @Schema(description = "사용자 ID", example = "1")
        Long id,
        @Schema(description = "이메일", example = "user@example.com")
        String email,
        @Schema(description = "닉네임", example = "coffeepeanut")
        String nickname,
        @Schema(description = "권한", example = "USER")
        String role
){
    // User 엔티티 -> User Response 변환 정적 팩토리 메서드
    public static UserResponse from (User user){
        return new UserResponse(
                user.getId(),
                user.getEmail(),
                user.getNickname(),
                user.getRole().name()
        );
    }
}
