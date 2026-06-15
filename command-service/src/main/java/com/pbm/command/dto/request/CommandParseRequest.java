package com.pbm.command.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 자연어 명령 파싱 요청 DTO.
 *
 * 역할: 사용자가 입력한 자연어 명령문을 command-service가 파싱할 수 있도록 전달한다.
 * 동작: commandText를 받아 GPT 파싱 서비스로 넘기며,
 *       userId는 요청 바디가 아니라 인증 토큰/헤더 기준으로 서버가 직접 결정한다.
 *       commandText는 앞뒤 공백을 제거하여 불필요한 오탐을 줄인다.
 * 연관: 향후 CommandParseController, CommandParsingService.
 */
@Schema(description = "자연어 명령 파싱 요청 DTO")
public record CommandParseRequest(
        @Schema(description = "자연어 명령문", example = "AliExpress에서 QCY T13 ANC 블루투스 이어폰 검정색 상품 10만원 이하면 구매해줘.")
        String commandText,
        @Schema(description = "현재 페이지 상품의 현재가 (익스텐션이 DOM에서 추출, 선택적)", example = "185973")
        Integer currentPrice
) {
    /** commandText만 전달하는 기존 호출부와의 하위 호환 생성자 */
    public CommandParseRequest(String commandText) {
        this(commandText, null);
    }

    public CommandParseRequest {
        commandText = commandText == null ? null : commandText.trim();
    }
}
