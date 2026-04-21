package com.pbm.auth.common;

// record는 Java 16+의 불변 데이터 클래스
// getter, equals, hashCode, toString 자동 생성
public record ApiResponse<T>(
        boolean success,
        T data,
        String message
) {
    // 성공 응답 (데이터 있음)
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(true, data, "성공");
    }

    // 성공 응답 (데이터 없음, 메시지만)
    public static <T> ApiResponse<T> success(String message) {
        return new ApiResponse<>(true, null, message);
    }

    // 실패 응답
    public static <T> ApiResponse<T> error(String message) {
        return new ApiResponse<>(false, null, message);
    }
}
