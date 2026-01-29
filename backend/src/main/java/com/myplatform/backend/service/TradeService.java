package com.myplatform.backend.service;

import com.myplatform.backend.dto.PaperTradingDto.*;

import java.math.BigDecimal;
import java.util.List;

/**
 * 매매 서비스 공통 인터페이스
 * - VirtualTradeService (모의투자)
 * - RealTradeService (실전투자)
 */
public interface TradeService {

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
     * 매도 처리
     * @param stockCode 종목코드
     * @param price 매도가격 (시장가일 경우 현재가)
     * @param quantity 매도수량
     * @param reason 매도사유 (STOP_LOSS, TAKE_PROFIT, TIME_CUT, MANUAL 등)
     * @return 거래내역 DTO
     */
    TradeHistoryDto sell(String stockCode, BigDecimal price, Integer quantity, String reason);

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
