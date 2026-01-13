package com.myplatform.backend.service;

import com.myplatform.backend.dto.AssetDto;
import com.myplatform.backend.dto.AssetRequest;
import com.myplatform.backend.dto.AssetSummaryDto;
import com.myplatform.backend.entity.GoldPrice;
import com.myplatform.backend.entity.SilverPrice;
import com.myplatform.backend.entity.UserAsset;
import com.myplatform.backend.repository.GoldPriceRepository;
import com.myplatform.backend.repository.SilverPriceRepository;
import com.myplatform.backend.repository.UserAssetRepository;
import com.myplatform.backend.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

@Service
@Transactional
public class AssetService {

    private final UserAssetRepository userAssetRepository;
    private final UserRepository userRepository;
    private final GoldPriceRepository goldPriceRepository;
    private final SilverPriceRepository silverPriceRepository;

    public AssetService(UserAssetRepository userAssetRepository,
                       UserRepository userRepository,
                       GoldPriceRepository goldPriceRepository,
                       SilverPriceRepository silverPriceRepository) {
        this.userAssetRepository = userAssetRepository;
        this.userRepository = userRepository;
        this.goldPriceRepository = goldPriceRepository;
        this.silverPriceRepository = silverPriceRepository;
    }

    public AssetDto addAsset(String username, AssetRequest request) {
        var user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다."));

        UserAsset asset = new UserAsset();
        asset.setUserId(user.getId());
        asset.setAssetType(request.getAssetType());
        asset.setQuantity(request.getQuantity());
        asset.setPurchasePrice(request.getPurchasePrice());
        asset.setPurchaseDate(request.getPurchaseDate());
        asset.setMemo(request.getMemo());
        asset.setTotalAmount(request.getQuantity().multiply(request.getPurchasePrice()));

        UserAsset saved = userAssetRepository.save(asset);
        return convertToDto(saved);
    }

    @Transactional(readOnly = true)
    public List<AssetDto> getMyAssets(String username) {
        var user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다."));

        return userAssetRepository.findByUserId(user.getId()).stream()
                .map(this::convertToDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public AssetSummaryDto getAssetSummary(String username) {
        var user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다."));

        List<UserAsset> allAssets = userAssetRepository.findByUserId(user.getId());
        List<UserAsset> goldAssets = allAssets.stream()
                .filter(a -> "GOLD".equals(a.getAssetType()))
                .toList();
        List<UserAsset> silverAssets = allAssets.stream()
                .filter(a -> "SILVER".equals(a.getAssetType()))
                .toList();

        // 현재 시세 조회
        BigDecimal currentGoldPrice = getCurrentGoldPrice();
        BigDecimal currentSilverPrice = getCurrentSilverPrice();

        AssetSummaryDto summary = new AssetSummaryDto();
        summary.setGold(calculateAssetTypeInfo(goldAssets, currentGoldPrice));
        summary.setSilver(calculateAssetTypeInfo(silverAssets, currentSilverPrice));
        summary.setAssets(allAssets.stream().map(this::convertToDto).toList());

        return summary;
    }

    public void deleteAsset(String username, Long assetId) {
        var user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다."));

        UserAsset asset = userAssetRepository.findById(assetId)
                .orElseThrow(() -> new RuntimeException("자산을 찾을 수 없습니다."));

        if (!asset.getUserId().equals(user.getId())) {
            throw new RuntimeException("본인의 자산만 삭제할 수 있습니다.");
        }

        userAssetRepository.delete(asset);
    }

    private AssetSummaryDto.AssetTypeInfo calculateAssetTypeInfo(List<UserAsset> assets, BigDecimal currentPrice) {
        AssetSummaryDto.AssetTypeInfo info = new AssetSummaryDto.AssetTypeInfo();

        if (assets.isEmpty()) {
            info.setTotalQuantity(BigDecimal.ZERO);
            info.setAveragePurchasePrice(BigDecimal.ZERO);
            info.setTotalInvestment(BigDecimal.ZERO);
            info.setCurrentPrice(currentPrice);
            info.setCurrentValue(BigDecimal.ZERO);
            info.setProfitLoss(BigDecimal.ZERO);
            info.setProfitRate(BigDecimal.ZERO);
            return info;
        }

        BigDecimal totalQuantity = assets.stream()
                .map(UserAsset::getQuantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalInvestment = assets.stream()
                .map(UserAsset::getTotalAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal averagePurchasePrice = totalQuantity.compareTo(BigDecimal.ZERO) > 0
                ? totalInvestment.divide(totalQuantity, 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        BigDecimal currentValue = totalQuantity.multiply(currentPrice);
        BigDecimal profitLoss = currentValue.subtract(totalInvestment);
        BigDecimal profitRate = totalInvestment.compareTo(BigDecimal.ZERO) > 0
                ? profitLoss.divide(totalInvestment, 4, RoundingMode.HALF_UP).multiply(new BigDecimal("100"))
                : BigDecimal.ZERO;

        info.setTotalQuantity(totalQuantity);
        info.setAveragePurchasePrice(averagePurchasePrice);
        info.setTotalInvestment(totalInvestment);
        info.setCurrentPrice(currentPrice);
        info.setCurrentValue(currentValue);
        info.setProfitLoss(profitLoss);
        info.setProfitRate(profitRate);

        return info;
    }

    private BigDecimal getCurrentGoldPrice() {
        return goldPriceRepository.findTopByOrderByFetchedAtDesc()
                .map(GoldPrice::getPricePerGram)
                .orElse(BigDecimal.ZERO);
    }

    private BigDecimal getCurrentSilverPrice() {
        return silverPriceRepository.findTopByOrderByFetchedAtDesc()
                .map(SilverPrice::getPricePerGram)
                .orElse(BigDecimal.ZERO);
    }

    private AssetDto convertToDto(UserAsset asset) {
        AssetDto dto = new AssetDto();
        dto.setId(asset.getId());
        dto.setAssetType(asset.getAssetType());
        dto.setQuantity(asset.getQuantity());
        dto.setPurchasePrice(asset.getPurchasePrice());
        dto.setPurchaseDate(asset.getPurchaseDate());
        dto.setTotalAmount(asset.getTotalAmount());
        dto.setMemo(asset.getMemo());
        dto.setCreatedAt(asset.getCreatedAt());
        return dto;
    }
}

