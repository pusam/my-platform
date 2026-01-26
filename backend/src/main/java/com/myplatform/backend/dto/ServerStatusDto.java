package com.myplatform.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ServerStatusDto {

    private CpuInfo cpu;
    private MemoryInfo memory;
    private JvmInfo jvm;
    private List<DiskInfo> disk;
    private SystemInfo system;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CpuInfo {
        private int availableProcessors;
        private double systemLoadAverage;
        private double processCpuUsage;    // 현재 프로세스 CPU 사용률 (%)
        private double systemCpuUsage;     // 시스템 전체 CPU 사용률 (%)
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MemoryInfo {
        private long totalPhysicalMemory;  // 총 물리 메모리
        private long freePhysicalMemory;   // 여유 물리 메모리
        private long usedPhysicalMemory;   // 사용 중인 물리 메모리
        private double usagePercent;       // 사용률 (%)
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class JvmInfo {
        private long heapUsed;             // 사용 중인 힙 메모리
        private long heapMax;              // 최대 힙 메모리
        private long heapCommitted;        // 할당된 힙 메모리
        private double heapUsagePercent;   // 힙 사용률 (%)
        private long nonHeapUsed;          // Non-heap 사용량
        private long nonHeapCommitted;     // Non-heap 할당량
        private String uptime;             // 가동 시간 (문자열)
        private long uptimeMillis;         // 가동 시간 (밀리초)
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DiskInfo {
        private String path;               // 디스크 경로
        private long totalSpace;           // 총 용량
        private long freeSpace;            // 여유 용량
        private long usableSpace;          // 사용 가능 용량
        private long usedSpace;            // 사용 중인 용량
        private double usagePercent;       // 사용률 (%)
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SystemInfo {
        private String osName;             // OS 이름
        private String osVersion;          // OS 버전
        private String osArch;             // OS 아키텍처
        private String javaVersion;        // Java 버전
        private String javaVendor;         // Java 벤더
        private String jvmName;            // JVM 이름
        private String startTime;          // 서버 시작 시간
    }
}
