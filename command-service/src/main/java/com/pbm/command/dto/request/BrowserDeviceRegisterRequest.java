package com.pbm.command.dto.request;

/**
 * 브라우저 디바이스 등록 요청 DTO.
 *
 * 역할: 웹사이트에 로그인된 사용자가 현재 브라우저의 extension 인스턴스를
 *       command-service에 등록할 때 필요한 최소 메타데이터를 전달한다.
 * 동작: deviceId는 클라이언트가 이미 보유한 값이 있으면 재사용하고,
 *       비어 있으면 서버가 새 UUID를 발급할 수 있도록 null 허용한다.
 * 연관: BrowserDeviceController, BrowserDeviceService.
 */
public record BrowserDeviceRegisterRequest(
        String pairingToken,
        String deviceId,
        String platform,
        String extensionVersion,
        String browserInfo
) {
    public BrowserDeviceRegisterRequest {
        pairingToken = normalize(pairingToken);
        deviceId = normalize(deviceId);
        platform = normalize(platform);
        extensionVersion = normalize(extensionVersion);
        browserInfo = normalize(browserInfo);
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
