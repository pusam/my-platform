package com.myplatform.backend.controller;

import com.myplatform.backend.service.ExportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

@Tag(name = "데이터 내보내기", description = "자산 및 가계부 데이터 내보내기 API")
@RestController
@RequestMapping("/api/export")
@SecurityRequirement(name = "JWT Bearer")
public class ExportController {

    private final ExportService exportService;

    public ExportController(ExportService exportService) {
        this.exportService = exportService;
    }

    @Operation(summary = "자산 데이터 내보내기", description = "자산 데이터를 Excel 또는 CSV 형식으로 내보냅니다.")
    @GetMapping("/assets")
    public ResponseEntity<byte[]> exportAssets(
            Authentication authentication,
            @RequestParam(defaultValue = "xlsx") String format) throws IOException {

        String username = ((UserDetails) authentication.getPrincipal()).getUsername();
        String dateStr = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));

        if ("csv".equalsIgnoreCase(format)) {
            byte[] data = exportService.exportAssetsToCsv(username);
            String filename = URLEncoder.encode("자산목록_" + dateStr + ".csv", StandardCharsets.UTF_8);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + filename)
                    .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                    .body(data);
        } else {
            byte[] data = exportService.exportAssetsToExcel(username);
            String filename = URLEncoder.encode("자산목록_" + dateStr + ".xlsx", StandardCharsets.UTF_8);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + filename)
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(data);
        }
    }

    @Operation(summary = "가계부 데이터 내보내기", description = "가계부 데이터를 Excel 또는 CSV 형식으로 내보냅니다.")
    @GetMapping("/finance")
    public ResponseEntity<byte[]> exportFinance(
            Authentication authentication,
            @RequestParam(defaultValue = "xlsx") String format,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month) throws IOException {

        String username = ((UserDetails) authentication.getPrincipal()).getUsername();
        String dateStr = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String periodStr = (year != null && month != null) ? year + "년" + month + "월_" : "";

        if ("csv".equalsIgnoreCase(format)) {
            byte[] data = exportService.exportFinanceToCsv(username, year, month);
            String filename = URLEncoder.encode("가계부_" + periodStr + dateStr + ".csv", StandardCharsets.UTF_8);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + filename)
                    .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                    .body(data);
        } else {
            byte[] data = exportService.exportFinanceToExcel(username, year, month);
            String filename = URLEncoder.encode("가계부_" + periodStr + dateStr + ".xlsx", StandardCharsets.UTF_8);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + filename)
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(data);
        }
    }
}
