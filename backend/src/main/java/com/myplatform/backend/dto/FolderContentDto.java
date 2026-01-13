package com.myplatform.backend.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "폴더 내용")
public class FolderContentDto {

    @Schema(description = "현재 폴더 정보")
    private FolderDto currentFolder;
    @Schema(description = "상위 폴더 경로")
    private List<FolderDto> breadcrumbs;
    @Schema(description = "하위 폴더 목록")
    private List<FolderDto> folders;
    @Schema(description = "파일 목록")
    private List<FileDto> files;

    public FolderDto getCurrentFolder() { return currentFolder; }
    public void setCurrentFolder(FolderDto currentFolder) { this.currentFolder = currentFolder; }
    public List<FolderDto> getBreadcrumbs() { return breadcrumbs; }
    public void setBreadcrumbs(List<FolderDto> breadcrumbs) { this.breadcrumbs = breadcrumbs; }
    public List<FolderDto> getFolders() { return folders; }
    public void setFolders(List<FolderDto> folders) { this.folders = folders; }
    public List<FileDto> getFiles() { return files; }
    public void setFiles(List<FileDto> files) { this.files = files; }
}
