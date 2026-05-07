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
    public static final LocalTime MARKET_CLOSE = LocalTime.of(15, 40);

    /** 한국 고정 공휴일 (음력 공휴일·임시공휴일은 별도 캘린더 API 가 없으면 누락 가능 — 운영중 보강) */
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

    /** 휴장일 = 주말 또는 고정 공휴일 */
    public boolean isMarketClosed() {
        return isMarketClosed(LocalDate.now());
    }

    public boolean isMarketClosed(LocalDate date) {
        DayOfWeek dow = date.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) return true;
        return KOREA_FIXED_HOLIDAYS.contains(MonthDay.from(date));
    }

    /** 정규장 시간(09:00~15:40) — 프리/애프터마켓 제외 */
    public boolean isRegularSession() {
        if (isMarketClosed()) return false;
        LocalTime t = LocalTime.now();
        return !t.isBefore(MARKET_OPEN) && !t.isAfter(MARKET_CLOSE);
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
