package com.myplatform.backend.sitelinks;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SiteLinkRepository extends JpaRepository<SiteLink, Long> {

    /** 본인 목록 — 순서 칸이 작은 것부터, 같으면 먼저 넣은 것부터. */
    List<SiteLink> findByUserIdOrderBySortOrderAscIdAsc(Long userId);

    /** 본인 행만 — 남의 id 를 넣으면 비어 있다. */
    Optional<SiteLink> findByIdAndUserId(Long id, Long userId);

    long countByUserId(Long userId);
}
