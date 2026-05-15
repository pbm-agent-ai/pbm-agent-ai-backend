package com.pbm.price.controller;

import com.pbm.price.common.ApiResponse;
import com.pbm.price.service.AliExpressCategorySyncService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AliExpress 카테고리 수동 동기화용 관리자 컨트롤러.
 *
 * 역할: 운영자가 필요할 때 AliExpress 카테고리 전체 동기화를 직접 실행할 수 있는
 *       관리자용 API 엔드포인트를 제공한다.
 * 동작: POST 요청을 받으면 AliExpressCategorySyncService를 호출하여
 *       external-api-service → category_nodes 동기화를 수행한다.
 * 연관: AliExpressCategorySyncService, ApiResponse.
 */
@RestController
@RequestMapping("api/v1/admin/aliexpress/categories")
@RequiredArgsConstructor
public class AliExpressCategoryAdminController {
    // 카테고리 동기화 비즈니스 로직을 담당하는 서비스
    private final AliExpressCategorySyncService aliExpressCategorySyncService;

    /**
     * AliExpress 카테고리 전체 동기화를 수동 실행한다.
     *
     * 동작 흐름:
     * 1. 운영자가 관리자 API를 호출
     * 2. Service가 external-api-service에서 카테고리 목록 조회
     * 3. parent 관계를 따라 categoryPath를 계산
     * 4. category_nodes 테이블에 upsert 저장
     *
     * @return 동기화 성공 메시지를 담은 공통 응답
     */
    @PostMapping("/sync")
    public ApiResponse<Void> syncAliExpressCategories(){
        aliExpressCategorySyncService.syncCategories();
        return ApiResponse.success("AliExpress 카테고리 동기화 성공");
    }
}
