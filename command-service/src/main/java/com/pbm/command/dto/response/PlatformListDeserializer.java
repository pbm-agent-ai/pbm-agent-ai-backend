package com.pbm.command.dto.response;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.pbm.command.domain.PlatformType;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * GPT가 platforms 필드를 단수 문자열("NAVER") 또는 배열(["NAVER"])로 반환할 수 있으므로
 * 두 형식을 모두 허용하는 커스텀 역직렬화기.
 *
 * 처리 케이스:
 *   - null           → null
 *   - []             → 빈 리스트
 *   - "NAVER"        → [NAVER]  (단수 문자열 → 리스트로 변환)
 *   - ["NAVER"]      → [NAVER]  (정상 배열)
 *   - 알 수 없는 값  → 해당 항목 무시 (나머지 정상 처리)
 */
public class PlatformListDeserializer extends StdDeserializer<List<PlatformType>> {

    public PlatformListDeserializer() {
        super(List.class);
    }

    @Override
    public List<PlatformType> deserialize(JsonParser p, DeserializationContext ctx) throws IOException {
        // null 토큰
        if (p.currentToken() == JsonToken.VALUE_NULL) {
            return null;
        }

        // 단수 문자열 케이스: "NAVER"
        if (p.currentToken() == JsonToken.VALUE_STRING) {
            String value = p.getText().trim();
            if (value.isEmpty() || value.equalsIgnoreCase("null")) {
                return new ArrayList<>();
            }
            PlatformType platform = parsePlatform(value);
            List<PlatformType> result = new ArrayList<>();
            if (platform != null) {
                result.add(platform);
            }
            return result;
        }

        // 배열 케이스: ["NAVER", "ALIEXPRESS"]
        if (p.currentToken() == JsonToken.START_ARRAY) {
            List<PlatformType> result = new ArrayList<>();
            while (p.nextToken() != JsonToken.END_ARRAY) {
                if (p.currentToken() == JsonToken.VALUE_STRING) {
                    PlatformType platform = parsePlatform(p.getText().trim());
                    if (platform != null) {
                        result.add(platform);
                    }
                }
            }
            return result;
        }

        return new ArrayList<>();
    }

    /** 문자열 → PlatformType 변환. 알 수 없는 값은 null 반환. */
    private PlatformType parsePlatform(String value) {
        try {
            return PlatformType.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
