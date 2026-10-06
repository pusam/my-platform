package com.myplatform.backend.dartfinancial;

import com.myplatform.backend.util.SecretRedaction;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myplatform.backend.dartfinancial.ControllingEarnings.Figures;
import com.myplatform.backend.dartfinancial.ControllingEarnings.ReportKey;
import com.myplatform.backend.repository.StockFinancialDataRepository;
import com.myplatform.backend.service.DartService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * DART 정기보고서에서 지배주주 순이익·지배지분 자본을 받아 {@code dart_controlling_financial} 에 쌓는다(2026-09-30).
 * 수집기({@code StockFinancialDataCollector})가 이 표로 PER·PBR·ROE 를 지배주주 기준으로 만든다 — {@link ControllingEarnings}.
 *
 * <p><b>호출 예산</b>: 종목당 "최신 분·반기 보고서 + 직전 사업보고서" 두 건이면 TTM 이 나온다. 이미 받은 보고서는 다시 받지
 * 않고(정기보고서는 한 번 나오면 그대로다), 제출 기한이 지난 보고서만 조회한다. 연결 자료가 없으면(013) 별도로 한 번 더.
 * DART 일일 한도(보통 2만 건)를 다른 기능과 나눠 쓰므로 회차당 {@link #MAX_CALLS_PER_RUN} 에서 멈추고 다음 회차가 잇는다.
 *
 * <p><b>§4c</b>: 조회 실패(네트워크·타임아웃)는 '데이터 없음'으로 기록하지 않는다 — 다음 회차에 다시 본다. DART 가 한도 초과
 * (020)나 키·시스템 오류를 주면 그 회차를 멈추고 WARN 을 남긴다(나머지 종목을 헛호출하지 않는다). 키는 로그에 남기지 않는다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DartControllingFinancialService {

    static final String BASE_URL = "https://opendart.fss.or.kr/api/fnlttSinglAcntAll.json";
    /** 기업개황 — 결산월(acc_mt). 12월 결산이 아니면 지배주주 TTM 을 만들지 않는다(ControllingEarnings.ttm). */
    static final String COMPANY_URL = "https://opendart.fss.or.kr/api/company.json";
    static final int CANDIDATE_COUNT = 4;
    /** 최신 보고서를 찾으려 종목당 한 회차에 거슬러 올라가는 최대 보고서 수. */
    static final int MAX_LATEST_FETCHES_PER_STOCK = 2;
    /** '데이터 없음'(013) 기록 뒤 다시 조회하기까지의 간격 — 늦게 내는 회사를 잡되 매일 헛호출하지 않게. */
    static final int NO_DATA_RETRY_DAYS = 7;
    static final int MAX_CALLS_PER_RUN = 8000;
    /** 부팅 따라잡기가 DART 기업코드 목록(메모리) 로드를 기다리는 한도 — 2초 × 150 = 5분. */
    static final int CORP_CODE_WAIT_POLLS = 150;

    private final DartControllingFinancialRepository repository;
    private final DartCompanyRepository companyRepository;
    private final DartService dartService;
    private final StockFinancialDataRepository stockFinancialDataRepository;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Value("${dart.api.key:}")
    private String dartApiKey;

    /** 호출 사이 간격 — 테스트는 0. */
    long callIntervalMillis = 100;
    long corpCodePollMillis = 2000;

    private final AtomicBoolean running = new AtomicBoolean(false);

    enum Outcome { OK, NO_DATA, FAILED, ABORT }

    /** 한 회차 결과 — 요약 로그와 테스트용. */
    public record Summary(int stocks, int calls, int ok, int noData, int failed, int skippedNoCorp, String abortReason) {}

    /** 평일 21:30 — 저녁 크론(19:00~20:20)이 끝난 뒤, 그날 접수된 보고서까지. */
    @Scheduled(scheduler = "batchScheduler", cron = "0 30 21 * * MON-FRI", zone = "Asia/Seoul")
    public void scheduledCollect() {
        collect();
    }

    /**
     * 첫 배포 따라잡기 — 표가 비어 있으면 부팅 직후 한 번 채운다(비어 있지 않으면 21:30 회차가 잇는다).
     * DART 기업코드 목록은 {@link DartService} 가 부팅 20초 뒤 메모리에 올리므로 그걸 기다린다.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Async
    public void catchUpOnStartup() {
        try {
            // 보고서 표나 결산월 표가 비었으면(첫 배포·V65 직후) 한 번 채운다 — 결산월이 없으면 순이익을 못 만든다
            if (repository.count() > 0 && companyRepository.count() > 0) return;
            for (int i = 0; i < CORP_CODE_WAIT_POLLS && !dartService.isCorpCodeLoaded(); i++) {
                Thread.sleep(corpCodePollMillis);
            }
            if (!dartService.isCorpCodeLoaded()) {
                log.warn("[DART 지배주주] 기업코드 목록이 로드되지 않아 부팅 따라잡기를 건너뛴다 — 21:30 회차가 채운다");
                return;
            }
            log.info("[DART 지배주주] 표가 비어 있어 부팅 따라잡기 시작");
            collect();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("[DART 지배주주] 부팅 따라잡기 실패: {}", e.getMessage());
        }
    }

    public Summary collect() {
        if (!running.compareAndSet(false, true)) {
            log.info("[DART 지배주주] 이미 수집 중 — 이번 호출은 건너뛴다");
            return null;
        }
        try {
            return doCollect();
        } finally {
            running.set(false);
        }
    }

    private Summary doCollect() {
        if (dartApiKey == null || dartApiKey.isBlank()) {
            log.warn("[DART 지배주주] DART_API_KEY 가 없어 수집하지 않는다 — PER·PBR 은 종전 정의(연결 순이익)로 남는다");
            return new Summary(0, 0, 0, 0, 0, 0, "NO_KEY");
        }
        LocalDate today = LocalDate.now(clock);
        List<ReportKey> candidates = ControllingEarnings.candidates(today, CANDIDATE_COUNT);

        Map<String, Map<ReportKey, DartControllingFinancial>> stored = new HashMap<>();
        for (DartControllingFinancial row : repository.findAll()) {
            stored.computeIfAbsent(row.getStockCode(), k -> new HashMap<>()).put(row.key(), row);
        }

        Map<String, DartCompany> companies = new HashMap<>();
        for (DartCompany c : companyRepository.findAll()) companies.put(c.getStockCode(), c);

        Run run = new Run(today);
        for (String code : new LinkedHashSet<>(stockFinancialDataRepository.findAllStockCodes())) {
            if (run.abortReason != null) break;
            if (run.calls >= MAX_CALLS_PER_RUN) {
                run.abortReason = "CALL_BUDGET";
                break;
            }
            String corpCode = dartService.getCorpCodeByStockCode(code);
            if (corpCode == null) {
                run.skippedNoCorp++;
                continue;
            }
            run.stocks++;
            DartCompany company = companies.get(code);
            if (company == null || (company.getFiscalMonth() == null && companyRetryDue(company, run.today))) {
                if (fetchCompany(run, code, corpCode, company, companies) == Outcome.ABORT) break;
            }
            collectStock(run, code, corpCode, stored.getOrDefault(code, new HashMap<>()), candidates);
        }

        Summary s = new Summary(run.stocks, run.calls, run.ok, run.noData, run.failed, run.skippedNoCorp, run.abortReason);
        if (run.abortReason != null && !"CALL_BUDGET".equals(run.abortReason)) {
            log.warn("[DART 지배주주] 수집 중단 — {} (종목 {} · 호출 {} · 저장 {} · 데이터 없음 {} · 실패 {})",
                    run.abortReason, s.stocks(), s.calls(), s.ok(), s.noData(), s.failed());
        } else {
            log.info("[DART 지배주주] 수집 {} — 종목 {} · 호출 {} · 저장 {} · 데이터 없음 {} · 실패 {} · 기업코드 없음 {}",
                    run.abortReason == null ? "완료" : "예산 소진(다음 회차가 잇는다)",
                    s.stocks(), s.calls(), s.ok(), s.noData(), s.failed(), s.skippedNoCorp());
        }
        return s;
    }

    /** 이미 받은 보고서의 연결/별도 구분 — 별도만 내는 회사에 매번 연결부터 물어 호출을 낭비하지 않게. */
    private static String preferredFsDiv(Map<ReportKey, DartControllingFinancial> stored) {
        return stored.values().stream()
                .filter(r -> DartControllingFinancial.STATUS_OK.equals(r.getStatus()) && r.getFsDiv() != null)
                .map(DartControllingFinancial::getFsDiv).findFirst().orElse(null);
    }

    private void collectStock(Run run, String code, String corpCode, Map<ReportKey, DartControllingFinancial> stored,
                              List<ReportKey> candidates) {
        String preferredFs = preferredFsDiv(stored);

        ReportKey latest = null;
        int fetches = 0;
        for (ReportKey key : candidates) {
            DartControllingFinancial row = stored.get(key);
            if (row != null && DartControllingFinancial.STATUS_OK.equals(row.getStatus())) {
                latest = key;
                break;
            }
            if (row != null && !retryDue(row, run.today)) continue;
            if (fetches++ >= MAX_LATEST_FETCHES_PER_STOCK) return;
            Outcome out = fetchAndStore(run, code, corpCode, key, preferredFs, row, stored);
            if (out == Outcome.OK) {
                latest = key;
                break;
            }
            if (out != Outcome.NO_DATA) return;   // FAILED·ABORT — 이 종목은 다음 회차에
        }
        if (latest == null || latest.code().annual()) return;
        ReportKey prior = latest.priorAnnual();
        DartControllingFinancial priorRow = stored.get(prior);
        if (priorRow == null || (!DartControllingFinancial.STATUS_OK.equals(priorRow.getStatus()) && retryDue(priorRow, run.today))) {
            fetchAndStore(run, code, corpCode, prior, preferredFsDiv(stored), priorRow, stored);
        }
    }

    static boolean retryDue(DartControllingFinancial row, LocalDate today) {
        return DartControllingFinancial.STATUS_NO_DATA.equals(row.getStatus())
                && !row.getCollectedAt().toLocalDate().plusDays(NO_DATA_RETRY_DAYS).isAfter(today);
    }

    private Outcome fetchAndStore(Run run, String code, String corpCode, ReportKey key, String preferredFs,
                                  DartControllingFinancial existing, Map<ReportKey, DartControllingFinancial> stored) {
        String[] order = "OFS".equals(preferredFs) ? new String[]{"OFS", "CFS"} : new String[]{"CFS", "OFS"};
        for (String fs : order) {
            if (run.calls >= MAX_CALLS_PER_RUN) {
                run.abortReason = "CALL_BUDGET";
                return Outcome.ABORT;
            }
            JsonNode root = call(run, corpCode, key, fs);
            if (root == null) {
                run.failed++;
                return Outcome.FAILED;
            }
            String status = root.path("status").asText();
            if ("000".equals(status)) {
                Figures f = ControllingEarnings.parse(root.path("list"), key.code(), "CFS".equals(fs));
                DartControllingFinancial row = existing != null ? existing : new DartControllingFinancial();
                row.setStockCode(code);
                row.setCorpCode(corpCode);
                row.setBsnsYear(key.year());
                row.setReprtCode(key.code().code);
                row.setFsDiv(fs);
                row.setStatus(DartControllingFinancial.STATUS_OK);
                row.setFiledOn(f.filedOn());
                row.setCtrlNetIncome(f.ctrlNetIncome());
                row.setCtrlNetIncomePrev(f.ctrlNetIncomePrev());
                row.setTotalNetIncome(f.totalNetIncome());
                row.setCtrlEquity(f.ctrlEquity());
                row.setTotalEquity(f.totalEquity());
                row.setCollectedAt(LocalDateTime.now(clock));
                stored.put(key, repository.save(row));
                run.ok++;
                return Outcome.OK;
            }
            if (!"013".equals(status)) {
                // 020 한도 초과·010/011 키 문제·800 점검 등 — 나머지 종목도 똑같이 실패하니 회차를 멈춘다
                run.abortReason = "DART status " + status + " " + root.path("message").asText();
                return Outcome.ABORT;
            }
        }
        DartControllingFinancial row = existing != null ? existing : new DartControllingFinancial();
        row.setStockCode(code);
        row.setCorpCode(corpCode);
        row.setBsnsYear(key.year());
        row.setReprtCode(key.code().code);
        row.setFsDiv(null);
        row.setStatus(DartControllingFinancial.STATUS_NO_DATA);
        row.setFiledOn(null);
        row.setCtrlNetIncome(null);
        row.setCtrlNetIncomePrev(null);
        row.setTotalNetIncome(null);
        row.setCtrlEquity(null);
        row.setTotalEquity(null);
        row.setCollectedAt(LocalDateTime.now(clock));
        stored.put(key, repository.save(row));
        run.noData++;
        return Outcome.NO_DATA;
    }

    static boolean companyRetryDue(DartCompany company, LocalDate today) {
        return !company.getCollectedAt().toLocalDate().plusDays(NO_DATA_RETRY_DAYS).isAfter(today);
    }

    /** 기업개황에서 결산월을 받아 둔다 — 종목당 한 번(결산월은 거의 바뀌지 않는다). 실패하면 저장하지 않고 다음 회차에. */
    private Outcome fetchCompany(Run run, String code, String corpCode, DartCompany existing,
                                 Map<String, DartCompany> companies) {
        if (run.calls >= MAX_CALLS_PER_RUN) {
            run.abortReason = "CALL_BUDGET";
            return Outcome.ABORT;
        }
        JsonNode root = get(run, UriComponentsBuilder.fromUriString(COMPANY_URL)
                .queryParam("crtfc_key", dartApiKey).queryParam("corp_code", corpCode).build().toUri());
        if (root == null) {
            run.failed++;
            return Outcome.FAILED;
        }
        String status = root.path("status").asText();
        if (!"000".equals(status) && !"013".equals(status)) {
            run.abortReason = "DART status " + status + " " + root.path("message").asText();
            return Outcome.ABORT;
        }
        String accMt = root.path("acc_mt").asText(null);
        DartCompany company = existing != null ? existing : new DartCompany();
        company.setStockCode(code);
        company.setCorpCode(corpCode);
        company.setFiscalMonth(accMt != null && accMt.matches("\\d{2}") ? accMt : null);
        company.setCollectedAt(LocalDateTime.now(clock));
        companies.put(code, companyRepository.save(company));
        return company.getFiscalMonth() != null ? Outcome.OK : Outcome.NO_DATA;
    }

    /** DART 한 건 조회 — 실패는 null(로그는 DEBUG, 키가 든 URL 은 남기지 않는다). */
    private JsonNode call(Run run, String corpCode, ReportKey key, String fsDiv) {
        return get(run, UriComponentsBuilder.fromUriString(BASE_URL)
                .queryParam("crtfc_key", dartApiKey)
                .queryParam("corp_code", corpCode)
                .queryParam("bsns_year", key.year())
                .queryParam("reprt_code", key.code().code)
                .queryParam("fs_div", fsDiv)
                .build().toUri());
    }

    private JsonNode get(Run run, URI uri) {
        run.calls++;
        try {
            if (callIntervalMillis > 0) Thread.sleep(callIntervalMillis);
            String body = SecretRedaction.redactingIoErrors(
                    () -> restTemplate.getForObject(uri, String.class));   // crtfc_key 가 URL 에 있다
            return body == null ? null : objectMapper.readTree(body);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            run.abortReason = "INTERRUPTED";
            return null;
        } catch (Exception e) {
            log.debug("[DART 지배주주] {} 조회 실패: {}", uri.getPath(), e.getMessage());
            return null;
        }
    }

    private static final class Run {
        final LocalDate today;
        int stocks, calls, ok, noData, failed, skippedNoCorp;
        String abortReason;

        Run(LocalDate today) {
            this.today = today;
        }
    }
}
