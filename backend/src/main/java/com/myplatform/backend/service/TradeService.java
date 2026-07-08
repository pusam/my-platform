package com.myplatform.backend.service;

import com.myplatform.backend.dto.PaperTradingDto.*;
import com.myplatform.backend.util.OrderSession;

import java.math.BigDecimal;
import java.util.List;

/**
 * 매매 서비스 공통 인터페이스
 * - VirtualTradeService (모의투자)
 * - RealTradeService (실전투자)
 */
public interface TradeService {

    /**
     * 증권거래세율(매도 시) — 2025년~ 코스피(농특세 0.15%)/코스닥 공히 0.15%. <b>단일 출처</b>:
     * REAL(0.2%)·VIRTUAL(0.18%)이 서로 다른 낡은 세율을 하드코딩해 실현손익 기록이 어긋나던 것을 통일.
     * 실제 현금은 KIS 가 실세율로 부과하므로 이 값은 기록/집계 정확도용. 세법 개정 시 여기 한 곳만 수정.
     */
    BigDecimal SELL_TAX_RATE = new BigDecimal("0.0015");

    /**
     * 매수 처리
     * @param stockCode 종목코드
     * @param price 매수가격 (시장가일 경우 현재가)
     * @param quantity 매수수량
     * @param reason 매수사유 (AUTO_BUY, MANUAL 등)
     * @return 거래내역 DTO
     */
    TradeHistoryDto buy(String stockCode, BigDecimal price, Integer quantity, String reason);

    /**
     * 매수 처리 (종목명 포함)
     * @param stockCode 종목코드
     * @param stockName 종목명
     * @param price 매수가격 (시장가일 경우 현재가)
     * @param quantity 매수수량
     * @param reason 매수사유 (AUTO_BUY, MANUAL 등)
     * @return 거래내역 DTO
     */
    default TradeHistoryDto buy(String stockCode, String stockName, BigDecimal price, Integer quantity, String reason) {
        return buy(stockCode, price, quantity, reason);
    }

    /**
     * 매도 처리
     * @param stockCode 종목코드
     * @param price 매도가격 (시장가일 경우 현재가)
     * @param quantity 매도수량
     * @param reason 매도사유 (STOP_LOSS, TAKE_PROFIT, TIME_CUT, MANUAL 등)
     * @return 거래내역 DTO
     */
    TradeHistoryDto sell(String stockCode, BigDecimal price, Integer quantity, String reason);

    /**
     * 매도 처리 (주문 세션 지정 — NXT/연장장 방어 청산용, 2026-09-14 대비).
     * <b>기본 구현은 세션을 무시하고 REGULAR 로 위임</b> — VirtualTradeService(KIS 미경유) 및
     * 세션 미구분 호출부의 현행 동작 보존. RealTradeService 만 override 해 KIS 주문에 세션을 전달한다.
     * @param session {@link OrderSession#REGULAR}(현행) 또는 {@link OrderSession#NXT_EXTENDED}(NXT 방어청산)
     */
    default TradeHistoryDto sell(String stockCode, BigDecimal price, Integer quantity, String reason, OrderSession session) {
        return sell(stockCode, price, quantity, reason);
    }

    /**
     * 계좌 요약 조회
     * @return 계좌 요약 정보
     */
    AccountSummaryDto getAccountSummary();

    /**
     * 포트폴리오 조회
     * @return 보유종목 목록
     */
    List<PortfolioItemDto> getPortfolio();

    /**
     * 포트폴리오 현재가 업데이트
     */
    void updatePortfolioPrices();

    /**
     * 매매 모드 반환
     * @return VIRTUAL 또는 REAL
     */
    String getTradeMode();
}
