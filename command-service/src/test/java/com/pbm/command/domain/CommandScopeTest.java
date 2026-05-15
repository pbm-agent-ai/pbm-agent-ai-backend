package com.pbm.command.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * command-service 1단계 범위 확정 테스트.
 *
 * 역할: 현재 MVP에서 허용한 intent와 platform 범위가 코드에 정확히 반영되었는지 검증한다.
 * 동작: enum 값 목록을 확인하여 제외 대상(COUPANG)이 포함되지 않았는지 함께 검증한다.
 * 연관: CommandIntent, PlatformType.
 */
class CommandScopeTest {

    @Test
    @DisplayName("명령 intent 범위는 AUTO_PURCHASE, PRICE_TRACK, PRICE_CHECK만 허용한다")
    void commandIntent_containsOnlySupportedValues() {
        assertThat(CommandIntent.values())
                .containsExactly(
                        CommandIntent.AUTO_PURCHASE,
                        CommandIntent.PRICE_TRACK,
                        CommandIntent.PRICE_CHECK
                );
    }

    @Test
    @DisplayName("플랫폼 범위는 NAVER, ALIEXPRESS만 허용하고 COUPANG은 제외한다")
    void platformType_containsOnlySupportedValues() {
        assertThat(PlatformType.values())
                .containsExactly(
                        PlatformType.NAVER,
                        PlatformType.ALIEXPRESS
                );

        assertThat(Arrays.stream(PlatformType.values())
                .map(Enum::name)
                .toList())
                .doesNotContain("COUPANG");
    }
}
