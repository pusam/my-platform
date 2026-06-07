package com.myplatform.backend.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.MonthDay;
import java.util.Set;

/**
 * 한국 증시 캘린더 — 휴장일/장중 시간 통합 가드.
 *
 * 기존엔 SectorTradingService 가 자체 isMarketClosed() 보유, 다른 스케줄러들은
 * MON-FRI cron 만 의존해 평일 공휴일(어린이날 등) 에 빈 데이터로 KIS API 두드리던 문제.
 * 한 곳에 모아 모든 스케줄러가 동일 기준으로 가드하도록 함.
 */
@Service
@Slf4j
public class MarketCalendarService {

    public static final LocalTime MARKET_OPEN = LocalTime.of(9, 0);
    /**
     * 정규장 종료 = 15:40 (KRX 접속매매는 15:30 까지). 15:30~15:40 은 <b>종가 단일가매매</b> 구간으로,
     * 봇/섹터 판정이 이 버퍼까지 정규장으로 봐야 종가 체결을 정상 처리한다. 의도된 값(15:30 아님). (P2-7)
     */
    public static final LocalTime MARKET_CLOSE = LocalTime.of(15, 40);

    /** 한국 고정(양력) 공휴일 */
    private static final Set<MonthDay> KOREA_FIXED_HOLIDAYS = Set.of(
            MonthDay.of(1, 1),   // 신정
            MonthDay.of(3, 1),   // 삼일절
            MonthDay.of(5, 5),   // 어린이날
            MonthDay.of(6, 6),   // 현충일
            MonthDay.of(8, 15),  // 광복절
            MonthDay.of(10, 3),  // 개천절
            MonthDay.of(10, 9),  // 한글날
            MonthDay.of(12, 25)  // 크리스마스
    );

    /**
     * 음력 유래 공휴일(설날·추석·부처님오신날)의 <b>양력 환산일</b> 테이블. (P2-8)
     *
     * <p>Java 표준엔 한국 음력 달력이 없어 매년 발표되는 공식 휴장일을 양력으로 박아 둔다.
     * <b>⚠ 매년 갱신 필요</b> — 미수록 연도는 이 휴일들이 "정상 거래일"로 처리됨(false negative, 안전한 열화).
     * 임시공휴일·대체공휴일·근로자의날(5/1)·연말폐장(12/31) 은 본 테이블 범위 밖(별도 보강 대상).
     */
    private static final Set<LocalDate> KOREA_LUNAR_DERIVED_HOLIDAYS = Set.of(
            // 설날 연휴
            LocalDate.of(2025, 1, 28), LocalDate.of(2025, 1, 29), LocalDate.of(2025, 1, 30),
            LocalDate.of(2026, 2, 16), LocalDate.of(2026, 2, 17), LocalDate.of(2026, 2, 18),
            LocalDate.of(2027, 2, 6),  LocalDate.of(2027, 2, 7),  LocalDate.of(2027, 2, 8),
            // 추석 연휴
            LocalDate.of(2025, 10, 5), LocalDate.of(2025, 10, 6), LocalDate.of(2025, 10, 7),
            LocalDate.of(2026, 9, 24), LocalDate.of(2026, 9, 25), LocalDate.of(2026, 9, 26),
            LocalDate.of(2027, 9, 14), LocalDate.of(2027, 9, 15), LocalDate.of(2027, 9, 16),
            // 부처님오신날 (음력 4/8)
            LocalDate.of(2025, 5, 5),  LocalDate.of(2026, 5, 24), LocalDate.of(2027, 5, 13)
    );

    /** 휴장일 = 주말 또는 (고정 양력 + 음력 유래) 공휴일 */
    public boolean isMarketClosed() {
        return isMarketClosed(LocalDate.now());
    }

    public boolean isMarketClosed(LocalDate date) {
        DayOfWeek dow = date.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) return true;
        return KOREA_FIXED_HOLIDAYS.contains(MonthDay.from(date))
                || KOREA_LUNAR_DERIVED_HOLIDAYS.contains(date);
    }

    /**
     * 정규장 시간(09:00~{@link #MARKET_CLOSE 15:40}) — 프리/애프터마켓 제외.
     *
     * <p><b>종료가 15:40 인 이유</b>: KRX 접속(연속)매매는 15:30 까지지만 15:30~15:40 은
     * <b>종가 단일가매매</b> 구간이다. 봇·섹터 판정이 이 10분 버퍼까지 정규장으로 봐야
     * 종가 체결을 정상 처리한다 → 의도적으로 15:30 이 아닌 15:40. 상수 정의는 {@link #MARKET_CLOSE}. (P2-7)
     */
    public boolean isRegularSession() {
        return isRegularSession(LocalDate.now(), LocalTime.now());
    }

    /** 테스트 가능 오버로드 — 경계(15:30·15:31·15:40·15:41) 검증용. (P2-7) */
    public boolean isRegularSession(LocalDate date, LocalTime time) {
        if (isMarketClosed(date)) return false;
        return !time.isBefore(MARKET_OPEN) && !time.isAfter(MARKET_CLOSE);
    }

    /** 오늘이 평일이고, 컨테이너 시작 시점이 cron 시각 이후라 catch-up 이 의미있는지.
     *  - 평일 + 개장 1시간 전~정오 사이 시작이면 morning 작업 catch-up 가치 있음.
     *  - 너무 늦게(오후·저녁) 시작했으면 morning 작업 catch-up 은 노이즈. */
    public boolean shouldCatchUpMorningTask() {
        if (isMarketClosed()) return false;
        LocalTime t = LocalTime.now();
        return t.isAfter(LocalTime.of(8, 0)) && t.isBefore(LocalTime.of(12, 0));
    }
}
