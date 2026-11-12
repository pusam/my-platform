package com.myplatform.backend.sitelinks;

/** 화면에 주는 한 줄. 주인 계정(userId)은 내보내지 않는다 — 본인 것만 주기 때문에 필요 없다. */
public record SiteLinkDto(Long id, String name, String description, String url, boolean loginRequired, int sortOrder) {

    static SiteLinkDto of(SiteLink e) {
        return new SiteLinkDto(e.getId(), e.getName(), e.getDescription(), e.getUrl(), e.isLoginRequired(), e.getSortOrder());
    }
}
