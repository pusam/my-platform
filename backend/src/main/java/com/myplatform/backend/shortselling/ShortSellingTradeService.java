package com.myplatform.backend.shortselling;

import com.fasterxml.jackson.databind.JsonNode;
import com.myplatform.backend.service.KoreaInvestmentService;
import com.myplatform.backend.service.MarketCalendarService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 공매도 <b>거래 비중</b> — KIS 공식 API 수집·조회(2026-10-02, 사용자 결정 "거래 비중으로, 표시만").
 *
 * <p>잔고 출처(KRX data 포털 {@code LOGOUT} · 네이버 금융 폐지)는 죽었고 KIS 에는 잔고 API 가 없다. 그래서
 * <ul>
 *   <li>평일 18:30 크론이 공매도 상위종목(국내주식-133)을 {@code short_selling_trade} 에 기준일별로 쌓는다 — 시장 탭 화면.</li>
 *   <li>종목별 값은 일별추이(국내주식-134)에서 <b>직전 마감일</b> 것을 읽는다 — 체크리스트 '참고' 항목.</li>
 * </ul>
 * ⚠ <b>표시 전용</b>이다. 판정·봇 진입 차단·경보·추천에 쓰지 않는다 — 기존 기준(5%)은 잔고 비율용이고 거래 비중용
 * 기준은 검증된 적이 없다. 봇·경보·필수 판정이 보는 잔고({@code ShortSellingService})는 데이터가 없어 작동하지 않는다.
 */
@Service
@Slf4j
public class ShortSellingTradeService {

    /** 연속조회 상한 — 공식 샘플의 max_depth 와 같다. 외부 페이지네이션은 반드시 끝이 있어야 한다(autoPager 교훈). */
    static final int MAX_RANKING_PAGES = 10;
    /** 종목 일별추이 조회 창(달력일) — 연휴를 끼어도 직전 마감일이 들어오게. */
    static final int STOCK_LOOKBACK_CALENDAR_DAYS = 20;

    private final ShortSellingTradeRepository repository;
    private final KoreaInvestmentService kis;
    private final MarketCalendarService calendar;
    private final Clock clock;
    private final TransactionTemplate tx;

    /** 이 프로세스의 마지막 수집 결과 — 화면이 '수집 실패'와 '0건'을 구분하게(§4c). 재시작하면 비어 있다. */
    private volatile CollectionStatus lastCollection;
    /** 종목·마감일별 값 — 조회 성공만 담는다(실패를 '값 없음'으로 굳히지 않게). */
    private final Map<String, StockShare> stockShareCache = new ConcurrentHashMap<>();

    public ShortSellingTradeService(ShortSellingTradeRepository repository, KoreaInvestmentService kis,
                                    MarketCalendarService calendar, Clock clock,
                                    PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.kis = kis;
        this.calendar = calendar;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /** 수집 결과. {@code ok=false} 면 {@code message} 가 사유다(부분 수집이면 저장한 행 수와 함께). */
    public record CollectionStatus(LocalDateTime at, boolean ok, int stored, String message) {
    }

    /** 상위 목록. {@code dataAvailable=false} 는 조회 실패 — '아직 수집 전(asOf=null)'과 다르다. */
    public record Ranking(boolean dataAvailable, LocalDate asOf, List<ShortSellingTrade> rows,
                          CollectionStatus lastCollection) {
    }

    /**
     * 종목 값. {@code dataAvailable=false} 는 조회 실패, 조회는 됐는데 비중 값이 없으면 {@code shortVolumeShare=null}.
     */
    public record StockShare(boolean dataAvailable, LocalDate asOf, BigDecimal shortVolumeShare,
                             Long shortVolume, BigDecimal shortAmountShare, String message) {
    }

    // ============================== 수집 ==============================

    /**
     * 공매도 상위종목을 받아 기준일별로 갈아 끼운다. 첫 페이지가 실패하면 아무것도 지우거나 쓰지 않는다.
     * 뒤 페이지가 실패하면 받은 앞부분(순위 1..k)은 저장하고 '부분 수집'으로 남긴다.
     */
    public CollectionStatus collect() {
        LocalDateTime now = LocalDateTime.now(clock);
        List<ShortSaleRows.RankingRow> rows = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String failure = null;
        int responseRows = 0;   // KIS 가 준 행 수 — 저장 행 수와 다르면 그 이유가 보이게(10/2 첫 수집이 1행이었다)
        int missingCode = 0;    // 종목코드가 없어 버린 행
        int missingDate = 0;    // 기준일을 정할 수 없어 버린 행
        int dateFromResponse = 0;   // 자기 기준일이 없어 응답 기준일을 쓴 행(KIS 는 첫 행에만 기준일을 준다 — 10/6 실측)
        List<String> firstDroppedFields = List.of();
        boolean continuation = false;
        for (int page = 0; page < MAX_RANKING_PAGES; page++) {
            KoreaInvestmentService.KisPage resp = kis.getShortSaleRankingPage(continuation);
            if (resp == null || resp.body() == null) {
                failure = "KIS 응답 없음(토큰·네트워크)";
                break;
            }
            JsonNode body = resp.body();
            String rtCd = body.path("rt_cd").asText("");
            if (!"0".equals(rtCd)) {
                failure = ("KIS 오류 rt_cd=" + rtCd + " " + body.path("msg_cd").asText("") + " "
                        + body.path("msg1").asText("")).trim();
                break;
            }
            int added = 0;
            JsonNode output = body.get("output");
            int pageRows = output != null && output.isArray() ? output.size() : 0;
            // 순위는 응답 위치를 이어 센다 — 버린 행도 자리를 차지하므로 앞 페이지 '행 수'가 아니라 '응답 수'를 넘긴다
            ShortSaleRows.RankingParse parsed = ShortSaleRows.parseRankingDetailed(output, responseRows);
            responseRows += pageRows;
            missingCode += parsed.missingCode();
            missingDate += parsed.missingDate();
            dateFromResponse += parsed.dateFromResponse();
            if (firstDroppedFields.isEmpty()) firstDroppedFields = parsed.firstDroppedFields();
            for (ShortSaleRows.RankingRow r : parsed.rows()) {
                if (seen.add(r.stockCode() + "|" + r.tradeDate())) {
                    rows.add(r);
                    added++;
                }
            }
            if (added == 0) {
                break;   // 진전 없음 — 같은 페이지가 되풀이되면 끊는다
            }
            String trCont = resp.trCont();
            if (!"M".equals(trCont) && !"F".equals(trCont)) {
                break;   // D·E·없음 = 마지막
            }
            continuation = true;
        }

        if (rows.isEmpty()) {
            String msg = failure != null ? failure : "KIS 빈 응답(0행) — 기존 기록은 그대로 둔다";
            log.warn("[공매도 거래 비중] 수집 실패: {}", msg);
            return remember(new CollectionStatus(now, false, 0, msg));
        }

        int stored = replaceDays(rows, now);
        if (failure != null) {
            String msg = "부분 수집 " + stored + "행 — 뒤 페이지 실패: " + failure;
            log.warn("[공매도 거래 비중] {}", msg);
            return remember(new CollectionStatus(now, false, stored, msg));
        }
        LocalDate asOf = rows.get(0).tradeDate();
        String note = collectionNote(responseRows, missingCode, missingDate, dateFromResponse);
        if (missingCode + missingDate > 0) {
            // 버린 행이 있으면 그 행의 필드 이름(값 없음)까지 남긴다 — 응답 모양이 바뀌었는지 볼 단서
            log.warn("[공매도 거래 비중] 수집 완료 — {}행 (기준일 {}){} · 처음 버린 행의 필드 {}",
                    stored, asOf, note, firstDroppedFields);
        } else {
            log.info("[공매도 거래 비중] 수집 완료 — {}행 (응답 {}건, 기준일 {}){}", stored, responseRows, asOf, note);
        }
        return remember(new CollectionStatus(now, true, stored,
                "수집 완료 " + stored + "행 (기준일 " + asOf + ")" + note));
    }

    /**
     * 수집 상태 뒷말 — 버린 행은 이유별로(종목코드 없음·기준일 없음), 응답 기준일을 쓴 행은 따로 센다. 없으면 빈 문자열. 순수 함수.
     * (10/2·10/6 은 "응답 30건 중 29건은 종목코드·기준일이 없어 건너뜀" 한 줄이라 둘 중 무엇이 빠졌는지 몰랐다.)
     */
    static String collectionNote(int responseRows, int missingCode, int missingDate, int dateFromResponse) {
        StringBuilder sb = new StringBuilder();
        int dropped = missingCode + missingDate;
        if (dropped > 0) {
            sb.append(" — 응답 ").append(responseRows).append("건 중 ").append(dropped).append("건 건너뜀(");
            List<String> why = new ArrayList<>();
            if (missingCode > 0) why.add("종목코드 없음 " + missingCode);
            if (missingDate > 0) why.add("기준일 없음 " + missingDate);
            sb.append(String.join(" · ", why)).append(')');
        }
        if (dateFromResponse > 0) {
            sb.append(dropped > 0 ? ", " : " — ").append(dateFromResponse)
                    .append("건은 응답 기준일로 놓음(KIS 는 첫 행에만 기준일을 준다)");
        }
        return sb.toString();
    }

    /** 기준일마다 그날 행을 지우고 새로 넣는다 — 한 트랜잭션. */
    private int replaceDays(List<ShortSaleRows.RankingRow> rows, LocalDateTime now) {
        Map<LocalDate, List<ShortSellingTrade>> byDate = new LinkedHashMap<>();
        for (ShortSaleRows.RankingRow r : rows) {
            byDate.computeIfAbsent(r.tradeDate(), d -> new ArrayList<>()).add(ShortSellingTrade.builder()
                    .stockCode(r.stockCode())
                    .stockName(r.stockName())
                    .tradeDate(r.tradeDate())
                    .rankNo(r.rank())
                    .shortVolume(r.shortVolume())
                    .shortVolumeShare(r.shortVolumeShare())
                    .shortAmount(r.shortAmount())
                    .shortAmountShare(r.shortAmountShare())
                    .totalVolume(r.totalVolume())
                    .totalAmount(r.totalAmount())
                    .price(r.price())
                    .changeRate(r.changeRate())
                    .avgPrice(r.avgPrice())
                    .collectedAt(now)
                    .build());
        }
        tx.executeWithoutResult(s -> byDate.forEach((date, entities) -> {
            repository.deleteByTradeDate(date);
            repository.saveAll(entities);
        }));
        return rows.size();
    }

    private CollectionStatus remember(CollectionStatus status) {
        this.lastCollection = status;
        return status;
    }

    /** 이 프로세스의 마지막 수집 결과 — 재시작 뒤엔 null(아직 시도 없음). */
    public CollectionStatus lastCollection() {
        return lastCollection;
    }

    // ============================== 조회 ==============================

    /** 최신 기준일의 상위 목록(응답 순서). 조회 실패는 {@code dataAvailable=false}. */
    public Ranking latestRanking(int limit) {
        try {
            Optional<LocalDate> latest = repository.findLatestTradeDate();
            if (latest.isEmpty()) {
                return new Ranking(true, null, List.of(), lastCollection);
            }
            List<ShortSellingTrade> rows = repository.findByTradeDateOrderByRankNoAsc(
                    latest.get(), PageRequest.of(0, Math.max(1, Math.min(limit, 500))));
            return new Ranking(true, latest.get(), rows, lastCollection);
        } catch (Exception e) {
            log.warn("[공매도 거래 비중] 상위 목록 조회 실패: {}", e.getMessage());
            return new Ranking(false, null, List.of(), lastCollection);
        }
    }

    /**
     * 종목의 직전 마감일 공매도 거래 비중 — KIS 일별추이. 같은 마감일 안에서는 한 번만 부른다.
     * 장중이면 오늘 행(형성 중)은 쓰지 않는다. 실패는 캐시하지 않는다.
     */
    public StockShare stockShare(String stockCode) {
        if (stockCode == null || stockCode.isBlank()) {
            return new StockShare(false, null, null, null, null, "종목코드 없음");
        }
        String code = stockCode.trim();
        LocalDate closed = calendar.lastClosedTradingDay(LocalDateTime.now(clock));
        String key = code + "|" + closed;
        StockShare cached = stockShareCache.get(key);
        if (cached != null) {
            return cached;
        }

        JsonNode body = kis.getDailyShortSale(code, closed.minusDays(STOCK_LOOKBACK_CALENDAR_DAYS), closed);
        if (body == null) {
            return new StockShare(false, null, null, null, null, "KIS 응답 없음(토큰·네트워크)");
        }
        String rtCd = body.path("rt_cd").asText("");
        if (!"0".equals(rtCd)) {
            return new StockShare(false, null, null, null, null,
                    ("KIS 오류 rt_cd=" + rtCd + " " + body.path("msg1").asText("")).trim());
        }
        StockShare share = ShortSaleRows.latestOnOrBefore(ShortSaleRows.parseDaily(body.get("output2")), closed)
                .map(r -> new StockShare(true, r.date(), r.shortVolumeShare(), r.shortVolume(),
                        r.shortAmountShare(), null))
                .orElseGet(() -> new StockShare(true, null, null, null, null, "공매도 거래 기록 없음"));
        // 마감일이 바뀌면 옛 키는 다시 안 쓰인다 — 쌓이지 않게 다른 날 것은 비운다.
        stockShareCache.keySet().removeIf(k -> !k.endsWith("|" + closed));
        stockShareCache.put(key, share);
        return share;
    }
}
