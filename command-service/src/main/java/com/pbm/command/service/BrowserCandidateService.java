package com.pbm.command.service;

import com.pbm.command.domain.CommandSession;
import com.pbm.command.domain.CommandSessionStatus;
import com.pbm.command.dto.event.ProductCandidateDto;
import com.pbm.command.dto.request.BrowserCandidateSubmitRequest;
import com.pbm.command.dto.response.CommandSessionResponse;
import com.pbm.command.exception.InvalidProductSelectionException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 브라우저 수집 후보 상품을 세션에 반영하는 서비스.
 */
@Service
public class BrowserCandidateService {

    private final CommandSessionService commandSessionService;

    public BrowserCandidateService(CommandSessionService commandSessionService) {
        this.commandSessionService = commandSessionService;
    }

    @Transactional
    public CommandSessionResponse submitAliExpressCandidates(
            String commandId,
            BrowserCandidateSubmitRequest request
    ) {
        CommandSession session = commandSessionService.getSessionEntityByCommandId(commandId);

        if (!"ALIEXPRESS".equalsIgnoreCase(session.getPlatform())) {
            throw new InvalidProductSelectionException(
                    "browser-candidates API는 현재 ALIEXPRESS 세션에서만 사용할 수 있습니다. 현재 플랫폼: "
                            + session.getPlatform()
            );
        }

        if (session.getStatus() != CommandSessionStatus.PRODUCT_SELECTION_REQUIRED
                && session.getStatus() != CommandSessionStatus.SEARCHING) {
            throw new InvalidProductSelectionException(
                    "browser-candidates API는 SEARCHING 또는 PRODUCT_SELECTION_REQUIRED 상태에서만 사용할 수 있습니다. 현재 상태: "
                            + session.getStatus()
            );
        }

        String fallbackKeyword = request.searchKeyword() != null && !request.searchKeyword().isBlank()
                ? request.searchKeyword().trim()
                : session.getOriginalCommand();

        Map<String, ProductCandidateDto> deduped = new LinkedHashMap<>();
        for (ProductCandidateDto candidate : request.candidates()) {
            String productId = candidate.productId().trim();
            String productUrl = candidate.productUrl().trim();
            String key = !productId.isBlank() ? productId : productUrl;

            deduped.put(key, new ProductCandidateDto(
                    productId,
                    candidate.title().trim(),
                    candidate.lprice(),
                    candidate.mallName() != null && !candidate.mallName().isBlank()
                            ? candidate.mallName().trim()
                            : "AliExpress",
                    productUrl,
                    candidate.imageUrl(),
                    candidate.currency() != null && !candidate.currency().isBlank()
                            ? candidate.currency().trim().toUpperCase()
                            : "KRW",
                    "ALIEXPRESS",
                    candidate.searchKeyword() != null && !candidate.searchKeyword().isBlank()
                            ? candidate.searchKeyword().trim()
                            : fallbackKeyword
            ));
        }

        return commandSessionService.replaceProductSelectionCandidates(
                commandId,
                List.of(),
                "검색 결과를 확인하고 상품을 선택해주세요.",
                null,
                deduped.values().stream().toList(),
                session.getTargetPrice(),
                session.getCommandIntent()
        );
    }
}
