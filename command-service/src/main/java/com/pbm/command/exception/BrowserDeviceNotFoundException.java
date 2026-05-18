package com.pbm.command.exception;

/**
 * 브라우저 디바이스를 찾을 수 없을 때 발생하는 예외.
 *
 * 역할: register 이후 heartbeat 또는 조회 요청에서 userId/deviceId 조합이 없을 때 던진다.
 * 동작: GlobalExceptionHandler가 HTTP 404(Not Found)로 변환한다.
 * 연관: BrowserDeviceService, GlobalExceptionHandler.
 */
public class BrowserDeviceNotFoundException extends RuntimeException {

    public BrowserDeviceNotFoundException(String message) {
        super(message);
    }
}
