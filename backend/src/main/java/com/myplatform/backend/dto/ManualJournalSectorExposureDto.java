package com.myplatform.backend.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * 수동 저널 — 섹터 집중 노출 (Phase 3). 매수 폼의 "동일 섹터 보유 N종목" <b>경고 전용</b>(차단 없음).
 *
 * <p>보유 = 열린 수동 저널(매도 전) + 봇 포지션(read-only). 종목이 {@code SectorStockConfig}
 * 매핑 밖이면 {@code mapped=false} — 프론트는 미표시(§4c, "0종목"으로 위장하지 않는다).
 * 한 종목이 복수 섹터에 속할 수 있어(예: 삼성전자) 섹터별 블록 리스트로 반환.
 */
@Getter
@Builder
public class ManualJournalSectorExposureDto {

    /** false = 대상 종목이 섹터 매핑 밖(노출 판단 불가 — 미표시). */
    private boolean mapped;
    private List<SectorBlock> sectors;

    @Getter
    @Builder
    public static class SectorBlock {
        private String sectorCode;
        private String sectorName;
        /** 이 섹터에서 이미 보유 중인 종목 수(저널+봇, 종목코드 중복 제거). */
        private long count;
        private List<Holding> holdings;
    }

    @Getter
    @Builder
    public static class Holding {
        private String stockCode;
        private String stockName;
        /** JOURNAL(수동 저널 보유) / BOT(봇 포지션). 양쪽 보유면 JOURNAL 우선 1건. */
        private String source;
    }
}
