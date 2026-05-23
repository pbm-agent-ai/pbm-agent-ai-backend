package com.pbm.command.service;

import com.pbm.command.client.OpenAiCommandClient;
import com.pbm.command.client.dto.OpenAiParsedCommandPayload;
import com.pbm.command.dto.request.CommandParseRequest;
import com.pbm.command.dto.response.CommandParseResponse;
import com.pbm.command.dto.response.ParsedCommand;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 자연어 명령 파싱 서비스.
 *
 * 역할: 현재 단계에서는 GPT 연동 전 임시(mock) 규칙으로 자연어를 구조화하고,
 *       stage 3에서 만든 필드 평가 서비스를 사용해 누락 필드와 모호 필드를 계산한다.
 * 동작: 요청 문장을 간단한 키워드 규칙으로 intent/category/가격을 추정한 뒤,
 *       FieldEvaluationResult를 합쳐 CommandParseResponse를 반환한다.
 * 연관: CommandParseController, CommandFieldEvaluationService, 향후 GPT Client.
 */
// Service 어노테이션을 이용해 Spring이 이 클래스의 객체를 만듦
@Slf4j
@Service
public class CommandParsingService {

    // GPT에 보낸 결과를 받아서 intent + parsedCommand + confidence로 돌려주는 파서 호출기
    private final OpenAiCommandClient openAiCommandClient;
    // 그 결과를 받아서 "지금 정보로 충분한가?"를 판단하는 검증기
    private final CommandFieldEvaluationService commandFieldEvaluationService;

    public CommandParsingService(
            OpenAiCommandClient openAiCommandClient,                        // 생성자 파라미터
            CommandFieldEvaluationService commandFieldEvaluationService     // 생성자 파라미터
    ) {
        this.openAiCommandClient = openAiCommandClient;
        this.commandFieldEvaluationService = commandFieldEvaluationService;
    }

    /**
     * 자연어 명령을 파싱하여 응답 DTO로 반환한다.
     *
     * @param request 사용자 자연어 명령 요청
     * @return 파싱 결과와 clarification 정보가 포함된 응답 DTO
     */
    public CommandParseResponse parse(CommandParseRequest request) {
        log.info("자연어 파싱 요청 - commandText: {}", request.commandText());
        OpenAiParsedCommandPayload aiPayload = openAiCommandClient.parseCommand(request);
        log.info("AI 자연어 파싱 최종 payload - intent: {}, parsedCommand: {}, confidence: {}",
                aiPayload.intent(), aiPayload.parsedCommand(), aiPayload.confidence());

        // 파싱 결과 로그 - 가격 파싱 오류 디버깅용
        if (aiPayload.parsedCommand() != null) {
            log.info("GPT 파싱 결과 - intent: {}, maxPrice: {}, minPrice: {}, platforms: {}, productName: {}",
                    aiPayload.intent(),
                    aiPayload.parsedCommand().maxPrice(),
                    aiPayload.parsedCommand().minPrice(),
                    aiPayload.parsedCommand().platforms(),
                    aiPayload.parsedCommand().productName()
            );
        }

        // intent가 null이면 GPT가 의도를 파악하지 못한 것 → 프론트에 모달 요청
        if (aiPayload.intent() == null) {
            return new CommandParseResponse(
                    null,
                    aiPayload.parsedCommand(),
                    List.of(),
                    List.of("intent"),
                    true,
                    aiPayload.confidence(),
                    null
            );
        }

        ParsedCommand parsedCommand = aiPayload.parsedCommand();
        FieldEvaluationResult evaluationResult = commandFieldEvaluationService.evaluate(aiPayload.intent(), parsedCommand);

        return new CommandParseResponse(
                aiPayload.intent(),
                parsedCommand,
                evaluationResult.missingRequiredFields(),
                evaluationResult.ambiguousFields(),
                evaluationResult.needsClarification(),
                aiPayload.confidence(),
                null
        );
    }
}
