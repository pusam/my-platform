package com.myplatform.backend.service;

import com.myplatform.backend.dto.ExchangeRateDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

/**
 * 환율 정보 서비스
 * - 네이버 금융에서 USD/KRW 환율 크롤링
 * - 외국인 수급 신호 분석
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ExchangeRateService {

    private static final String NAVER_EXCHANGE_URL =
            "https://finance.naver.com/marketindex/exchangeDetail.naver?marketindexCd=FX_USDKRW";

    /**
     * 현재 USD/KRW 환율 정보 조회
     */
    public ExchangeRateDto getCurrentExchangeRate() {
        log.info("환율 정보 조회 시작");

        try {
            Document doc = Jsoup.connect(NAVER_EXCHANGE_URL)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .timeout(10000)
                    .get();

            // 현재 환율
            BigDecimal rate = null;
            Element rateElement = doc.selectFirst("p.no_today span.blind");
            if (rateElement != null) {
                String rateText = rateElement.text().replace(",", "").trim();
                rate = new BigDecimal(rateText);
            }

            // 변동액과 변동률
            BigDecimal change = null;
            BigDecimal changeRate = null;

            // 변동액 (상승/하락에 따라 다른 selector)
            Element changeElement = doc.selectFirst("p.no_exday span.no_up span.blind");
            if (changeElement == null) {
                changeElement = doc.selectFirst("p.no_exday span.no_down span.blind");
            }
            if (changeElement != null) {
                String changeText = changeElement.text().replace(",", "").trim();
                change = new BigDecimal(changeText);

                // 하락인 경우 음수로 변환
                Element downCheck = doc.selectFirst("p.no_exday span.no_down");
                if (downCheck != null) {
                    change = change.negate();
                }
            }

            // 변동률 계산
            if (rate != null && change != null) {
                BigDecimal prevRate = rate.subtract(change);
                if (prevRate.compareTo(BigDecimal.ZERO) > 0) {
                    changeRate = change.divide(prevRate, 4, RoundingMode.HALF_UP)
                            .multiply(new BigDecimal("100"))
                            .setScale(2, RoundingMode.HALF_UP);
                }
            }

            // 신호 분석
            String trend = ExchangeRateDto.determineTrend(change);
            String signal = ExchangeRateDto.determineSignal(changeRate);
            String interpretation = ExchangeRateDto.generateInterpretation(changeRate, signal);

            ExchangeRateDto result = ExchangeRateDto.builder()
                    .rate(rate)
                    .change(change)
                    .changeRate(changeRate)
                    .trend(trend)
                    .signal(signal)
                    .interpretation(interpretation)
                    .fetchedAt(LocalDateTime.now())
                    .build();

            log.info("환율 정보 조회 완료: {} ({}%)", rate, changeRate);
            return result;

        } catch (Exception e) {
            log.error("환율 정보 조회 실패: {}", e.getMessage());
            return ExchangeRateDto.builder()
                    .interpretation("환율 정보를 불러올 수 없습니다")
                    .fetchedAt(LocalDateTime.now())
                    .build();
        }
    }
}
