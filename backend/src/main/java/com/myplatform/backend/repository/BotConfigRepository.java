package com.myplatform.backend.repository;

import com.myplatform.backend.entity.BotConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface BotConfigRepository extends JpaRepository<BotConfig, Long> {

    /**
     * 설정 키로 조회
     */
    Optional<BotConfig> findByConfigKey(String configKey);
}
