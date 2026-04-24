package com.myplatform.backend.service;

import com.myplatform.backend.dto.StockDetailDto;
import com.myplatform.backend.dto.StockDetailDto.CandlePoint;
import com.myplatform.backend.dto.StockDetailDto.ChartData;
import com.myplatform.backend.dto.StockDetailDto.ChartSignal;
import com.myplatform.backend.dto.StockDetailDto.PriceInfo;
import com.myplatform.backend.dto.StockDetailDto.VolumePoint;
import com.myplatform.backend.dto.TechnicalIndicatorsDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 차트/기술 지표에서 확정적으로 판단 가능한 시그널을 뽑아 배지 리스트로 반환.
 *
 * Gemini 같은 LLM 해석과 별개 — 코드로 계산해 확실한 것만 담는다.
 * UI 상단에 칩 형태로 보여주기 용.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ChartSignalService {

    private static final String TONE_POSITIVE = "positive";
    private static final String TONE_NEGATIVE = "negative";
    private static final String TONE_NEUTRAL = "neutral";

    private final TechnicalIndicatorService technicalIndicatorService;

    /**
     * 종목 상세 데이터에서 차트 시그널 리스트를 추출.
     * 빈 리스트를 돌려주는 것도 정상 (지표 데이터가 부족할 때).
     */
    public List<ChartSignal> detect(StockDetailDto dto) {
        List<ChartSignal> signals = new ArrayList<>();
        if (dto == null) return signals;

        ChartData chart = dto.getChartData();
        PriceInfo price = dto.getPrice();
        if (chart == null || chart.getCandles() == null || chart.getCandles().isEmpty() || price == null) {
            return signals;
        }

        List<BigDecimal> closes = new ArrayList<>();
        for (CandlePoint c : chart.getCandles()) {
            if (c.getClose() != null) closes.add(c.getClose());
        }
        // 캔들은 최신순이 아닐 수 있으니 "마지막 원소 = 최근" 가정.
        if (closes.size() < 5) return signals;

        BigDecimal current = price.getCurrentPrice() != null
                ? price.getCurrentPrice()
                : closes.get(closes.size() - 1);

        // === 이동평균선 시그널 ===
        detectMovingAverage(signals, closes, chart, current);

        // === RSI ===
        detectRsi(signals, closes);

        // === 볼린저 밴드 ===
        detectBollinger(signals, chart, current);

        // === 거래량 급증 ===
        detectVolumeSurge(signals, chart, price);

        // === 단기 모멘텀 (당일 등락률 극단) ===
        detectExtremeChange(signals, price);

        return signals;
    }

    private void detectMovingAverage(List<ChartSignal> signals,
                                      List<BigDecimal> closes,
                                      ChartData chart,
                                      BigDecimal current) {
        BigDecimal ma5 = chart.getMa5();
        BigDecimal ma20 = chart.getMa20();
        BigDecimal ma60 = chart.getMa60();

        // 5일선 돌파/이탈
        if (ma5 != null && current != null) {
            double diff = pct(current, ma5);
            if (diff > 0 && diff < 1.0) {
                signals.add(build("MA5_NEAR", "5일선 근접",
                        String.format("현재가 %s, MA5 %s (+%.2f%%)",
                                formatPrice(current), formatPrice(ma5), diff),
                        TONE_NEUTRAL));
            }
        }

        // 20일선 지지/이탈
        if (ma20 != null && current != null) {
            double diff = pct(current, ma20);
            if (diff > 0 && diff < 2.0) {
                signals.add(build("MA20_SUPPORT", "20일선 지지권",
                        String.format("현재가가 MA20(%s) 위 +%.2f%%", formatPrice(ma20), diff),
                        TONE_POSITIVE));
            } else if (diff < -2.0) {
                signals.add(build("MA20_BROKEN", "20일선 이탈",
                        String.format("현재가가 MA20(%s) 아래 %.2f%%", formatPrice(ma20), diff),
                        TONE_NEGATIVE));
            }
        }

        // 정배열/역배열
        if (ma5 != null && ma20 != null && ma60 != null) {
            if (ma5.compareTo(ma20) > 0 && ma20.compareTo(ma60) > 0) {
                signals.add(build("MA_ALIGNED_UP", "정배열",
                        "MA5 > MA20 > MA60 — 중기 상승 구조",
                        TONE_POSITIVE));
            } else if (ma5.compareTo(ma20) < 0 && ma20.compareTo(ma60) < 0) {
                signals.add(build("MA_ALIGNED_DOWN", "역배열",
                        "MA5 < MA20 < MA60 — 중기 하락 구조",
                        TONE_NEGATIVE));
            }
        }

        // MA5 / MA20 골든 / 데드 크로스 (마지막 두 값 비교)
        List<BigDecimal> line5 = chart.getMaLine5();
        List<BigDecimal> line20 = chart.getMaLine20();
        if (line5 != null && line20 != null && line5.size() >= 2 && line20.size() >= 2) {
            BigDecimal prev5 = line5.get(line5.size() - 2);
            BigDecimal cur5 = line5.get(line5.size() - 1);
            BigDecimal prev20 = line20.get(line20.size() - 2);
            BigDecimal cur20 = line20.get(line20.size() - 1);
            if (prev5 != null && cur5 != null && prev20 != null && cur20 != null) {
                if (prev5.compareTo(prev20) <= 0 && cur5.compareTo(cur20) > 0) {
                    signals.add(build("MA5_20_GOLDEN", "골든크로스",
                            "MA5 가 MA20 을 상향 돌파", TONE_POSITIVE));
                } else if (prev5.compareTo(prev20) >= 0 && cur5.compareTo(cur20) < 0) {
                    signals.add(build("MA5_20_DEAD", "데드크로스",
                            "MA5 가 MA20 을 하향 이탈", TONE_NEGATIVE));
                }
            }
        }
    }

    private void detectRsi(List<ChartSignal> signals, List<BigDecimal> closes) {
        if (closes.size() < 15) return;
        try {
            BigDecimal rsi = technicalIndicatorService.calculateRSI(closes, 14);
            if (rsi == null) return;
            double v = rsi.doubleValue();
            if (v <= 30) {
                signals.add(build("RSI_OVERSOLD", "RSI 과매도",
                        String.format("RSI %.1f — 반등 기대 구간", v), TONE_POSITIVE));
            } else if (v >= 70) {
                signals.add(build("RSI_OVERBOUGHT", "RSI 과매수",
                        String.format("RSI %.1f — 차익실현 주의", v), TONE_NEGATIVE));
            } else if (v >= 45 && v <= 55) {
                signals.add(build("RSI_NEUTRAL", "RSI 중립",
                        String.format("RSI %.1f — 방향성 부족", v), TONE_NEUTRAL));
            }
        } catch (Exception e) {
            log.debug("[ChartSignal] RSI 계산 실패: {}", e.getMessage());
        }
    }

    private void detectBollinger(List<ChartSignal> signals, ChartData chart, BigDecimal current) {
        if (current == null) return;
        List<BigDecimal> upper = chart.getBbUpper();
        List<BigDecimal> lower = chart.getBbLower();
        if (upper == null || lower == null || upper.isEmpty() || lower.isEmpty()) return;

        BigDecimal up = upper.get(upper.size() - 1);
        BigDecimal lo = lower.get(lower.size() - 1);
        if (up == null || lo == null) return;

        if (current.compareTo(up) >= 0) {
            signals.add(build("BB_UPPER_TOUCH", "볼린저 상단 터치",
                    String.format("%s 상단 %s 근접/돌파 — 단기 과열 가능",
                            formatPrice(current), formatPrice(up)),
                    TONE_NEGATIVE));
        } else if (current.compareTo(lo) <= 0) {
            signals.add(build("BB_LOWER_TOUCH", "볼린저 하단 터치",
                    String.format("%s 하단 %s 근접/이탈 — 반등 가능",
                            formatPrice(current), formatPrice(lo)),
                    TONE_POSITIVE));
        }
    }

    private void detectVolumeSurge(List<ChartSignal> signals, ChartData chart, PriceInfo price) {
        List<VolumePoint> volumes = chart.getVolumes();
        if (volumes == null || volumes.size() < 6) return;

        // 최근 5개(오늘 제외) 평균 거래량 계산
        int n = volumes.size();
        long sum = 0L;
        int cnt = 0;
        for (int i = Math.max(0, n - 6); i < n - 1; i++) {
            Long vol = volumes.get(i).getVolume();
            if (vol != null) { sum += vol; cnt++; }
        }
        if (cnt == 0) return;
        double avg5 = (double) sum / cnt;

        Long today = volumes.get(n - 1).getVolume();
        // PriceInfo 쪽 trading volume 이 있으면 그게 더 최신
        if (price != null && price.getTradingVolume() != null) {
            today = price.getTradingVolume();
        }
        if (today == null || avg5 <= 0) return;

        double ratio = today / avg5;
        if (ratio >= 2.0) {
            signals.add(build("VOLUME_SURGE", "거래량 급증",
                    String.format("5일 평균 대비 %.1f배", ratio),
                    TONE_POSITIVE));
        } else if (ratio <= 0.5) {
            signals.add(build("VOLUME_DRY", "거래량 소강",
                    String.format("5일 평균의 %.0f%% — 방향성 대기",
                            ratio * 100),
                    TONE_NEUTRAL));
        }
    }

    private void detectExtremeChange(List<ChartSignal> signals, PriceInfo price) {
        if (price == null || price.getChangeRate() == null) return;
        double cr = price.getChangeRate().doubleValue();
        if (cr >= 5.0) {
            signals.add(build("SURGE_DAY", "당일 급등",
                    String.format("+%.2f%% — 상한가 근접 주의", cr), TONE_NEGATIVE));
        } else if (cr <= -5.0) {
            signals.add(build("PLUNGE_DAY", "당일 급락",
                    String.format("%.2f%% — 하락 모멘텀", cr), TONE_NEGATIVE));
        }
    }

    // =========================================================
    // helpers
    // =========================================================

    private ChartSignal build(String code, String label, String detail, String tone) {
        return ChartSignal.builder().code(code).label(label).detail(detail).tone(tone).build();
    }

    private double pct(BigDecimal a, BigDecimal b) {
        if (a == null || b == null || b.signum() == 0) return 0.0;
        return a.subtract(b).divide(b, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100)).doubleValue();
    }

    private String formatPrice(BigDecimal v) {
        if (v == null) return "-";
        return String.format("%,d원", v.setScale(0, RoundingMode.HALF_UP).longValue());
    }
}
