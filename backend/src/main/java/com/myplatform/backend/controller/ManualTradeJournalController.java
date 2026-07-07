package com.myplatform.backend.controller;

import com.myplatform.backend.entity.ManualTradeJournal;
import com.myplatform.backend.service.ManualTradeJournalService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 수동 매매 저널 API (V43) — 사용자 소유 검증(principal username). 봇/주문 경로와 완전 분리.
 */
@RestController
@RequestMapping("/api/manual-journal")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "수동 매매 저널", description = "수동 매수/매도 기록 + 매수 시점 신호 스냅샷")
public class ManualTradeJournalController {

    private final ManualTradeJournalService journalService;

    private String username(Authentication auth) {
        return ((UserDetails) auth.getPrincipal()).getUsername();
    }

    @Getter @Setter
    public static class BuyRequest {
        private String stockCode;
        private String stockName;
        private BigDecimal buyPrice;
        private BigDecimal quantity;   // 선택
        private String memo;           // 선택
    }

    @Getter @Setter
    public static class CloseRequest {
        private BigDecimal sellPrice;
        private LocalDateTime sellAt;  // 선택(기본 now)
    }

    @PostMapping
    @Operation(summary = "매수 기록", description = "매수 기록 + 매수 시점 신호 스냅샷 자동 채움(소스 실패=필드 null §4c).")
    public ResponseEntity<Map<String, Object>> recordBuy(Authentication auth, @RequestBody BuyRequest req) {
        Map<String, Object> res = new HashMap<>();
        if (req == null || req.getStockCode() == null || req.getStockCode().isBlank()
                || req.getBuyPrice() == null || req.getBuyPrice().signum() <= 0) {
            res.put("success", false);
            res.put("message", "stockCode/buyPrice 는 필수입니다.");
            return ResponseEntity.badRequest().body(res);
        }
        try {
            ManualTradeJournal saved = journalService.recordBuy(username(auth), req.getStockCode().trim(),
                    req.getStockName(), req.getBuyPrice(), req.getQuantity(), req.getMemo());
            res.put("success", true);
            res.put("data", saved);
            return ResponseEntity.ok(res);
        } catch (Exception e) {
            log.error("[수동저널] 매수 기록 실패 {}: {}", req.getStockCode(), e.getMessage(), e);
            res.put("success", false);
            res.put("message", "매수 기록에 실패했습니다.");
            return ResponseEntity.internalServerError().body(res);
        }
    }

    @PutMapping("/{id}/close")
    @Operation(summary = "매도 기록(전량)", description = "실현수익률 확정. 이미 매도된 건/소유 불일치는 400.")
    public ResponseEntity<Map<String, Object>> recordSell(Authentication auth, @PathVariable Long id,
                                                          @RequestBody CloseRequest req) {
        Map<String, Object> res = new HashMap<>();
        try {
            Optional<ManualTradeJournal> updated = journalService.recordSell(
                    username(auth), id, req != null ? req.getSellPrice() : null,
                    req != null ? req.getSellAt() : null);
            if (updated.isEmpty()) {
                res.put("success", false);
                res.put("message", "기록을 찾을 수 없거나 이미 매도되었거나 매도가가 유효하지 않습니다.");
                return ResponseEntity.badRequest().body(res);
            }
            res.put("success", true);
            res.put("data", updated.get());
            return ResponseEntity.ok(res);
        } catch (Exception e) {
            log.error("[수동저널] 매도 기록 실패 id={}: {}", id, e.getMessage(), e);
            res.put("success", false);
            res.put("message", "매도 기록에 실패했습니다.");
            return ResponseEntity.internalServerError().body(res);
        }
    }

    @GetMapping
    @Operation(summary = "내 저널 리스트(최신 매수순)")
    public ResponseEntity<Map<String, Object>> list(Authentication auth) {
        Map<String, Object> res = new HashMap<>();
        try {
            res.put("success", true);
            res.put("data", journalService.list(username(auth)));
            return ResponseEntity.ok(res);
        } catch (Exception e) {
            log.error("[수동저널] 리스트 실패: {}", e.getMessage(), e);
            res.put("success", false);
            res.put("message", "조회에 실패했습니다.");
            return ResponseEntity.internalServerError().body(res);
        }
    }

    @GetMapping("/by-stock/{stockCode}")
    @Operation(summary = "특정 종목의 내 기록", description = "종목상세 신호 이력에 내 매수/매도 마커 병기용.")
    public ResponseEntity<Map<String, Object>> listByStock(Authentication auth, @PathVariable String stockCode) {
        Map<String, Object> res = new HashMap<>();
        try {
            res.put("success", true);
            res.put("data", journalService.listByStock(username(auth), stockCode));
            return ResponseEntity.ok(res);
        } catch (Exception e) {
            log.error("[수동저널] 종목별 조회 실패 {}: {}", stockCode, e.getMessage(), e);
            res.put("success", false);
            res.put("message", "조회에 실패했습니다.");
            return ResponseEntity.internalServerError().body(res);
        }
    }

    @GetMapping("/sector-exposure")
    @Operation(summary = "섹터 집중 노출(경고용)",
            description = "동일 섹터 보유 종목(열린 저널+봇 포지션 read-only). 매핑 밖=mapped:false(§4c 미표시). 경고만, 차단 없음.")
    public ResponseEntity<Map<String, Object>> sectorExposure(Authentication auth,
                                                              @RequestParam String stockCode) {
        Map<String, Object> res = new HashMap<>();
        try {
            res.put("success", true);
            res.put("data", journalService.sectorExposure(username(auth), stockCode.trim()));
            return ResponseEntity.ok(res);
        } catch (Exception e) {
            log.error("[수동저널] 섹터 노출 조회 실패 {}: {}", stockCode, e.getMessage(), e);
            res.put("success", false);
            res.put("message", "조회에 실패했습니다.");
            return ResponseEntity.internalServerError().body(res);
        }
    }

    @GetMapping("/stats")
    @Operation(summary = "내 매매 통계",
            description = "총건수·3거래일 적중률·평균 alpha·실현 승률 + RSI/재료 breakdown. "
                    + "표본 0건 비율/평균은 null, 평가 표본 n<10 은 insufficientSample=true(§4c).")
    public ResponseEntity<Map<String, Object>> stats(Authentication auth) {
        Map<String, Object> res = new HashMap<>();
        try {
            res.put("success", true);
            res.put("data", journalService.stats(username(auth)));
            return ResponseEntity.ok(res);
        } catch (Exception e) {
            log.error("[수동저널] 통계 실패: {}", e.getMessage(), e);
            res.put("success", false);
            res.put("message", "통계 조회에 실패했습니다.");
            return ResponseEntity.internalServerError().body(res);
        }
    }

    @GetMapping("/{id}")
    @Operation(summary = "내 저널 단건")
    public ResponseEntity<Map<String, Object>> get(Authentication auth, @PathVariable Long id) {
        Map<String, Object> res = new HashMap<>();
        Optional<ManualTradeJournal> j = journalService.get(username(auth), id);
        if (j.isEmpty()) {
            res.put("success", false);
            res.put("message", "기록을 찾을 수 없습니다.");
            return ResponseEntity.badRequest().body(res);
        }
        res.put("success", true);
        res.put("data", j.get());
        return ResponseEntity.ok(res);
    }
}
