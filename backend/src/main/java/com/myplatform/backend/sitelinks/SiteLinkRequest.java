package com.myplatform.backend.sitelinks;

/** 추가·수정 요청. 검사는 {@link SiteLinkRules} 가 한다. loginRequired·sortOrder 를 안 보내면 false·0. */
public record SiteLinkRequest(String name, String description, String url, Boolean loginRequired, Integer sortOrder) {
}
