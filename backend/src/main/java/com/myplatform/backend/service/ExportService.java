package com.myplatform.backend.service;

import com.myplatform.backend.entity.UserAsset;
import com.myplatform.backend.entity.FinanceTransaction;
import com.myplatform.backend.repository.UserAssetRepository;
import com.myplatform.backend.repository.FinanceTransactionRepository;
import com.myplatform.backend.repository.UserRepository;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;

@Service
public class ExportService {

    private final UserAssetRepository userAssetRepository;
    private final FinanceTransactionRepository financeTransactionRepository;
    private final UserRepository userRepository;

    public ExportService(UserAssetRepository userAssetRepository,
                        FinanceTransactionRepository financeTransactionRepository,
                        UserRepository userRepository) {
        this.userAssetRepository = userAssetRepository;
        this.financeTransactionRepository = financeTransactionRepository;
        this.userRepository = userRepository;
    }

    public byte[] exportAssetsToExcel(String username) throws IOException {
        Long userId = getUserId(username);
        List<UserAsset> assets = userAssetRepository.findByUserId(userId);
        assets.sort(Comparator.comparing(UserAsset::getPurchaseDate).reversed());

        try (Workbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            Sheet sheet = workbook.createSheet("자산 목록");

            // 헤더 스타일
            CellStyle headerStyle = workbook.createCellStyle();
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            headerStyle.setBorderBottom(BorderStyle.THIN);

            // 헤더 생성
            Row headerRow = sheet.createRow(0);
            String[] headers = {"종류", "종목명/자산명", "수량", "구매가격", "총액", "구매일", "메모"};
            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
            }

            // 데이터 행 생성
            DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
            int rowNum = 1;
            for (UserAsset asset : assets) {
                Row row = sheet.createRow(rowNum++);
                row.createCell(0).setCellValue(getAssetTypeLabel(asset.getAssetType()));
                row.createCell(1).setCellValue(getAssetName(asset));
                row.createCell(2).setCellValue(asset.getQuantity().doubleValue());
                row.createCell(3).setCellValue(asset.getPurchasePrice().doubleValue());
                row.createCell(4).setCellValue(asset.getTotalAmount().doubleValue());
                row.createCell(5).setCellValue(asset.getPurchaseDate() != null
                        ? asset.getPurchaseDate().format(dateFormatter) : "");
                row.createCell(6).setCellValue(asset.getMemo() != null ? asset.getMemo() : "");
            }

            // 열 너비 자동 조정
            for (int i = 0; i < headers.length; i++) {
                sheet.autoSizeColumn(i);
            }

            workbook.write(out);
            return out.toByteArray();
        }
    }

    public byte[] exportAssetsToCsv(String username) throws IOException {
        Long userId = getUserId(username);
        List<UserAsset> assets = userAssetRepository.findByUserId(userId);
        assets.sort(Comparator.comparing(UserAsset::getPurchaseDate).reversed());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // BOM for Excel UTF-8 compatibility
        out.write(0xEF);
        out.write(0xBB);
        out.write(0xBF);

        try (PrintWriter writer = new PrintWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8))) {
            // 헤더
            writer.println("종류,종목명/자산명,수량,구매가격,총액,구매일,메모");

            DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
            for (UserAsset asset : assets) {
                writer.println(String.format("%s,%s,%s,%s,%s,%s,%s",
                        escapeCsv(getAssetTypeLabel(asset.getAssetType())),
                        escapeCsv(getAssetName(asset)),
                        asset.getQuantity(),
                        asset.getPurchasePrice(),
                        asset.getTotalAmount(),
                        asset.getPurchaseDate() != null ? asset.getPurchaseDate().format(dateFormatter) : "",
                        escapeCsv(asset.getMemo() != null ? asset.getMemo() : "")
                ));
            }
        }

        return out.toByteArray();
    }

    public byte[] exportFinanceToExcel(String username, Integer year, Integer month) throws IOException {
        List<FinanceTransaction> transactions;

        if (year != null && month != null) {
            transactions = financeTransactionRepository.findByUsernameAndYearMonth(username, year, month);
        } else {
            transactions = financeTransactionRepository.findByUsernameOrderByTransactionDateDesc(username);
        }

        try (Workbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            Sheet sheet = workbook.createSheet("가계부");

            // 헤더 스타일
            CellStyle headerStyle = workbook.createCellStyle();
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            headerStyle.setBorderBottom(BorderStyle.THIN);

            // 헤더 생성
            Row headerRow = sheet.createRow(0);
            String[] headers = {"유형", "카테고리", "금액", "날짜", "메모"};
            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
            }

            // 데이터 행 생성
            DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
            int rowNum = 1;
            for (FinanceTransaction tx : transactions) {
                Row row = sheet.createRow(rowNum++);
                row.createCell(0).setCellValue(tx.getType() == FinanceTransaction.TransactionType.INCOME ? "수입" : "지출");
                row.createCell(1).setCellValue(tx.getCategory());
                row.createCell(2).setCellValue(tx.getAmount().doubleValue());
                row.createCell(3).setCellValue(tx.getTransactionDate() != null
                        ? tx.getTransactionDate().format(dateFormatter) : "");
                row.createCell(4).setCellValue(tx.getMemo() != null ? tx.getMemo() : "");
            }

            // 열 너비 자동 조정
            for (int i = 0; i < headers.length; i++) {
                sheet.autoSizeColumn(i);
            }

            workbook.write(out);
            return out.toByteArray();
        }
    }

    public byte[] exportFinanceToCsv(String username, Integer year, Integer month) throws IOException {
        List<FinanceTransaction> transactions;

        if (year != null && month != null) {
            transactions = financeTransactionRepository.findByUsernameAndYearMonth(username, year, month);
        } else {
            transactions = financeTransactionRepository.findByUsernameOrderByTransactionDateDesc(username);
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // BOM for Excel UTF-8 compatibility
        out.write(0xEF);
        out.write(0xBB);
        out.write(0xBF);

        try (PrintWriter writer = new PrintWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8))) {
            // 헤더
            writer.println("유형,카테고리,금액,날짜,메모");

            DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
            for (FinanceTransaction tx : transactions) {
                writer.println(String.format("%s,%s,%s,%s,%s",
                        tx.getType() == FinanceTransaction.TransactionType.INCOME ? "수입" : "지출",
                        escapeCsv(tx.getCategory()),
                        tx.getAmount(),
                        tx.getTransactionDate() != null ? tx.getTransactionDate().format(dateFormatter) : "",
                        escapeCsv(tx.getMemo() != null ? tx.getMemo() : "")
                ));
            }
        }

        return out.toByteArray();
    }

    private Long getUserId(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다."))
                .getId();
    }

    private String getAssetTypeLabel(String assetType) {
        return switch (assetType) {
            case "GOLD" -> "금";
            case "SILVER" -> "은";
            case "STOCK" -> "주식";
            case "OTHER" -> "기타";
            default -> assetType;
        };
    }

    private String getAssetName(UserAsset asset) {
        if ("STOCK".equals(asset.getAssetType())) {
            return asset.getStockName() != null ? asset.getStockName() : asset.getStockCode();
        } else if ("OTHER".equals(asset.getAssetType())) {
            return asset.getOtherName() != null ? asset.getOtherName() : "";
        }
        return "";
    }

    private String escapeCsv(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
