package com.myplatform.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 비동기 뉴스 수집 작업 시작 응답
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class NewsFetchAsyncResponse {

    private String status;
    private String message;
}
