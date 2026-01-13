package com.myplatform.backend.repository;

import com.myplatform.backend.entity.UserAsset;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface UserAssetRepository extends JpaRepository<UserAsset, Long> {
    
    List<UserAsset> findByUserId(Long userId);
    
    List<UserAsset> findByUserIdAndAssetType(Long userId, String assetType);
    
    @Query("SELECT COALESCE(SUM(ua.quantity), 0) FROM UserAsset ua WHERE ua.userId = :userId AND ua.assetType = :assetType")
    Double getTotalQuantityByUserIdAndAssetType(@Param("userId") Long userId, @Param("assetType") String assetType);
}
