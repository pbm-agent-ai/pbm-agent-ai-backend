package com.pbm.price.service;

import com.pbm.price.client.ExchangeRateClient;
import com.pbm.price.dto.response.KoreaEximbankExchangeRateItem;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 한국수출입은행 환율 서비스.
 *
 * 역할:
 * - USD/KRW 환율을 수출입은행 API에서 조회한다.
 * - 당일 환율을 인메모리에 캐싱하여 반복 호출을 최소화한다.
 * - 주말/공휴일에는 최대 5영업일 이전까지 날짜를 소급하여 가장 최근 환율을 사용한다.
 * - API 호출이 완전히 실패할 경우 fallback으로 1,400원을 반환한다.
 *
 * 연관: ExchangeRateClient, PriceCurrencyConverter
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExchangeRateService {

    /** 통화 코드: USD */
    private static final String USD_CURRENCY_UNIT = "USD";

    /** 날짜 포맷: YYYYMMDD */
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 주말/공휴일 fallback 최대 소급 일수 */
    private static final int MAX_FALLBACK_DAYS = 5;

    /** 환율 조회 실패 시 사용할 기본값 (1 USD = 1,400 KRW) */
    private static final BigDecimal FALLBACK_EXCHANGE_RATE = BigDecimal.valueOf(1400L);

    private final ExchangeRateClient exchangeRateClient;

    /** 캐시된 USD/KRW 환율 */
    private final AtomicReference<BigDecimal> cachedRate = new AtomicReference<>(null);

    /** 캐시된 환율의 기준 날짜 (YYYYMMDD) */
    private volatile String cachedDate = null;

    /**
     * 현재 USD/KRW 환율을 반환한다.
     *
     * 당일 캐시가 있으면 바로 반환하고, 없으면 수출입은행 API를 호출한다.
     * 주말/공휴일에는 최대 5일 전까지 소급 조회한다.
     * API가 완전히 실패하면 fallback 값(1,400원)을 반환한다.
     *
     * @return 1 USD 기준 KRW 환율
     */
    public BigDecimal getUsdToKrwRate() {
        String today = LocalDate.now().format(DATE_FORMATTER);

        // 당일 캐시가 있으면 바로 반환
        if (today.equals(cachedDate) && cachedRate.get() != null) {
            log.debug("환율 캐시 히트 - date: {}, rate: {}", cachedDate, cachedRate.get());
            return cachedRate.get();
        }

        // 캐시 미스 → API 조회 (주말/공휴일 fallback 포함)
        BigDecimal rate = fetchRateWithFallback(today);

        // 캐시 업데이트
        cachedRate.set(rate);
        cachedDate = today;

        log.info("USD/KRW 환율 갱신 완료 - date: {}, rate: {}", today, rate);
        return rate;
    }

    /**
     * 지정 날짜부터 최대 MAX_FALLBACK_DAYS 일 이전까지 소급 조회한다.
     *
     * @param startDate 조회 시작 날짜 (YYYYMMDD)
     * @return USD/KRW 환율. 모든 시도가 실패하면 FALLBACK_EXCHANGE_RATE 반환
     */
    private BigDecimal fetchRateWithFallback(String startDate) {
        LocalDate date = LocalDate.parse(startDate, DATE_FORMATTER);

        for (int i = 0; i <= MAX_FALLBACK_DAYS; i++) {
            String searchDate = date.minusDays(i).format(DATE_FORMATTER);
            List<KoreaEximbankExchangeRateItem> items = exchangeRateClient.fetchExchangeRates(searchDate);

            if (items.isEmpty()) {
                log.debug("환율 데이터 없음 (주말/공휴일) - searchDate: {}, 소급 시도 중...", searchDate);
                continue;
            }

            BigDecimal rate = extractUsdRate(items, searchDate);
            if (rate != null) {
                return rate;
            }
        }

        log.warn("{}일간 환율 조회 실패 - fallback 환율 {}원 사용", MAX_FALLBACK_DAYS + 1, FALLBACK_EXCHANGE_RATE);
        return FALLBACK_EXCHANGE_RATE;
    }

    /**
     * 환율 목록에서 USD 매매 기준율을 추출하여 BigDecimal로 파싱한다.
     *
     * @param items      환율 항목 목록
     * @param searchDate 로깅용 조회 날짜
     * @return USD/KRW 환율. USD 항목이 없거나 파싱 실패 시 null
     */
    private BigDecimal extractUsdRate(List<KoreaEximbankExchangeRateItem> items, String searchDate) {
        return items.stream()
                .filter(item -> USD_CURRENCY_UNIT.equals(item.curUnit()))
                .findFirst()
                .map(item -> {
                    try {
                        // 수출입은행 API는 숫자에 쉼표 포함 (예: "1,393.00") → 쉼표 제거 후 파싱
                        String cleaned = item.dealBasR().replace(",", "").trim();
                        BigDecimal rate = new BigDecimal(cleaned);
                        log.info("한국수출입은행 USD/KRW 환율 조회 성공 - searchDate: {}, rate: {}", searchDate, rate);
                        return rate;
                    } catch (NumberFormatException e) {
                        log.warn("환율 파싱 실패 - searchDate: {}, dealBasR: '{}'", searchDate, item.dealBasR());
                        return null;
                    }
                })
                .orElse(null);
    }
}
