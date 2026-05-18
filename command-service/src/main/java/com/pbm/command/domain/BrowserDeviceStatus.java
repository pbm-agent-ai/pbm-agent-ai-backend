package com.pbm.command.domain;

/**
 * 브라우저 디바이스 상태 enum.
 *
 * 역할: 사용자의 크롬 확장프로그램 디바이스가 현재 마지막으로 보고한 상태를 표현한다.
 * 동작: MVP 1단계에서는 register/heartbeat 수신 시 ONLINE으로 저장하고,
 *       향후 스케줄러 또는 Redis TTL 기반 판정과 결합하여 OFFLINE 전환을 확장한다.
 * 연관: BrowserDevice, BrowserDeviceService.
 */
public enum BrowserDeviceStatus {
    ONLINE,
    OFFLINE
}
