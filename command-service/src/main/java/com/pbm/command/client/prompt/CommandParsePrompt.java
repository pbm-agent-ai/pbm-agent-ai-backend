package com.pbm.command.client.prompt;

/**
 * GPT 자연어 파싱 프롬프트 묶음 DTO.
 *
 * 역할: LLM 호출 시 사용할 system prompt와 user prompt를 한 객체로 관리한다.
 * 동작: 프롬프트 빌더가 생성한 두 문자열을 함께 보관하여,
 *       이후 GPT client가 요청 본문에 그대로 넣을 수 있게 한다.
 * 연관: CommandParsePromptBuilder, 향후 GPT Client.
 */
public record CommandParsePrompt(
        String systemPrompt,
        String userPrompt
) {
}
