package com.pbm.notification.common;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 공통 API 응답 래퍼.
 * 모든 API 응답은 이 클래스로 감싸서 반환한다.
 *
 * @param <T> 응답 데이터 타입
 */
@Getter
@AllArgsConstructor
public class ApiResponse<T> {

    /** 요청 성공 여부 */
    private final boolean success;

    /** 응답 데이터 */
    private final T data;

    /** 응답 메시지 */
    private final String message;

    /** 성공 응답 생성 */
    public static <T> ApiResponse<T> success(T data, String message) {
        return new ApiResponse<>(true, data, message);
    }

    /** 데이터 없는 성공 응답 생성 */
    public static <T> ApiResponse<T> success(String message) {
        return new ApiResponse<>(true, null, message);
    }

    /** 실패 응답 생성 */
    public static <T> ApiResponse<T> error(String message) {
        return new ApiResponse<>(false, null, message);
    }
}
