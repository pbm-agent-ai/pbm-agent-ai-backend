package com.pbm.command.domain;

/**
 * 자연어 명령 파싱 시 허용하는 구매/조회 플랫폼(enum).
 *
 * 역할: 현재 MVP에서 지원하는 플랫폼 범위를 명확히 제한한다.
 * 동작: GPT와 백엔드 규칙은 NAVER, ALIEXPRESS만 유효한 플랫폼으로 간주하고,
 *       제외된 플랫폼(예: COUPANG)은 아직 지원하지 않는 값으로 처리한다.
 * 연관: 향후 ParsedCommand, 필드 검증 규칙, 프론트 모달 보완 입력.
 */
public enum PlatformType {
    NAVER,
    ALIEXPRESS
}
