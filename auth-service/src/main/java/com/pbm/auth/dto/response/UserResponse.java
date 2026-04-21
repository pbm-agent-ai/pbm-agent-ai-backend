package com.pbm.auth.dto.response;

import com.pbm.auth.domain.User;

public record UserResponse (
        Long id,
        String email,
        String nickname,
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
