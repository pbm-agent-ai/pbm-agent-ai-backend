package com.pbm.price.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 한국수출입은행 환율 API 응답 단건 DTO.
 *
 * API: GET https://www.koreaexim.go.kr/site/program/financial/exchangeJSON
 *      ?authkey={key}&searchdate={YYYYMMDD}&data=AP01
 *
 * @param result      결과 코드 (1: 성공, 2: 인증 오류, 3: 없는 날짜, 4: 일일 제한 초과)
 * @param curUnit     통화 코드 (예: USD, EUR, JPY(100))
 * @param curNm       통화명 (예: 미국 달러)
 * @param dealBasR    매매 기준율 (예: "1,393.00") - KRW 변환 시 사용
 * @param ttb         전신환(송금) 받을 때
 * @param tts         전신환(송금) 보낼 때
 */
public record KoreaEximbankExchangeRateItem(
        @JsonProperty("result") Integer result,
        @JsonProperty("cur_unit") String curUnit,
        @JsonProperty("cur_nm") String curNm,
        @JsonProperty("deal_bas_r") String dealBasR,
        @JsonProperty("ttb") String ttb,
        @JsonProperty("tts") String tts
) {
}
