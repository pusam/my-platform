package com.myplatform.backend.controller;

import com.myplatform.backend.dto.ExchangeRateDto;
import com.myplatform.backend.service.ExchangeRateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/exchange-rate")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "환율", description = "환율 정보 API")
public class ExchangeRateController {

    private final ExchangeRateService exchangeRateService;

    @GetMapping
    @Operation(summary = "현재 환율 조회", description = "USD/KRW 환율 및 외국인 수급 신호 조회")
    public ResponseEntity<ExchangeRateDto> getCurrentExchangeRate() {
        log.info("환율 조회 API 호출");
        ExchangeRateDto result = exchangeRateService.getCurrentExchangeRate();
        return ResponseEntity.ok(result);
    }
}
