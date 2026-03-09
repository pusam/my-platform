package com.myplatform.backend.controller;

import com.myplatform.backend.dto.OilPriceDto;
import com.myplatform.backend.service.OilPriceService;
import com.myplatform.core.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/oil")
@RequiredArgsConstructor
public class OilPriceController {

    private final OilPriceService oilPriceService;

    @GetMapping("/price")
    public ResponseEntity<ApiResponse<OilPriceDto>> getOilPrice() {
        OilPriceDto oilPrice = oilPriceService.getOilPrice();
        if (oilPrice == null) {
            return ResponseEntity.ok(ApiResponse.fail("원유 시세 정보를 가져올 수 없습니다. KIS API 키를 확인하세요."));
        }
        return ResponseEntity.ok(ApiResponse.success("원유 시세 조회 성공", oilPrice));
    }

    @GetMapping("/history/month")
    public ResponseEntity<ApiResponse<List<OilPriceDto>>> getMonthlyHistory() {
        List<OilPriceDto> history = oilPriceService.getMonthlyHistory();
        return ResponseEntity.ok(ApiResponse.success("조회 성공", history));
    }
}
