package com.myplatform.backend.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "폴더 정보")
public class FolderDto {

    @Schema(description = "폴더 ID")
    private Long id;
    @Schema(description = "폴더명")
    private String name;
    @Schema(description = "부모 폴더 ID")
    private Long parentId;
    @Schema(description = "전체 경로")
    private String path;
    @Schema(description = "생성일시")
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Long getParentId() { return parentId; }
    public void setParentId(Long parentId) { this.parentId = parentId; }
    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
