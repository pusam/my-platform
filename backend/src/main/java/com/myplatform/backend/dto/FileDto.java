package com.myplatform.backend.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Schema(description = "파일 정보")
public class FileDto {

    @Schema(description = "파일 ID")
    private Long id;
    @Schema(description = "원본 파일명")
    private String originalName;
    @Schema(description = "파일 크기")
    private Long fileSize;
    @Schema(description = "파일 타입")
    private String fileType;
    @Schema(description = "파일 확장자")
    private String fileExtension;
    @Schema(description = "폴더 ID")
    private Long folderId;
    @Schema(description = "썸네일 경로")
    private String thumbnailPath;
    @Schema(description = "설명")
    private String description;
    @Schema(description = "업로드 날짜")
    private LocalDate uploadDate;
    @Schema(description = "생성일시")
    private LocalDateTime createdAt;
    @Schema(description = "다운로드 URL")
    private String downloadUrl;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getOriginalName() { return originalName; }
    public void setOriginalName(String originalName) { this.originalName = originalName; }
    public Long getFileSize() { return fileSize; }
    public void setFileSize(Long fileSize) { this.fileSize = fileSize; }
    public String getFileType() { return fileType; }
    public void setFileType(String fileType) { this.fileType = fileType; }
    public String getFileExtension() { return fileExtension; }
    public void setFileExtension(String fileExtension) { this.fileExtension = fileExtension; }
    public Long getFolderId() { return folderId; }
    public void setFolderId(Long folderId) { this.folderId = folderId; }
    public String getThumbnailPath() { return thumbnailPath; }
    public void setThumbnailPath(String thumbnailPath) { this.thumbnailPath = thumbnailPath; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public LocalDate getUploadDate() { return uploadDate; }
    public void setUploadDate(LocalDate uploadDate) { this.uploadDate = uploadDate; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public String getDownloadUrl() { return downloadUrl; }
    public void setDownloadUrl(String downloadUrl) { this.downloadUrl = downloadUrl; }
}
