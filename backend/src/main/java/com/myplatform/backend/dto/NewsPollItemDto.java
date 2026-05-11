package com.myplatform.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 최신 뉴스 폴링 응답 항목
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class NewsPollItemDto {

    private Long id;
    private String title;
    private String summary;
    private String sourceName;
    private String sourceUrl;
    private String sentiment;
    private String sentimentLabel;
    private LocalDateTime summarizedAt;
    private boolean urgent;
}
