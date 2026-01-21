package com.myplatform.backend.service;

import com.myplatform.backend.dto.ServerStatusDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.RuntimeMXBean;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
public class ServerStatusService {

    public ServerStatusDto getServerStatus() {
        return ServerStatusDto.builder()
                .cpu(getCpuInfo())
                .memory(getMemoryInfo())
                .jvm(getJvmInfo())
                .disk(getDiskInfo())
                .system(getSystemInfo())
                .build();
    }

    private ServerStatusDto.CpuInfo getCpuInfo() {
        OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();

        double systemLoad = osBean.getSystemLoadAverage();
        int availableProcessors = osBean.getAvailableProcessors();

        // Java 11+ specific - try to get process CPU load
        double processCpuLoad = -1;
        double systemCpuLoad = -1;

        if (osBean instanceof com.sun.management.OperatingSystemMXBean) {
            com.sun.management.OperatingSystemMXBean sunOsBean =
                (com.sun.management.OperatingSystemMXBean) osBean;
            processCpuLoad = sunOsBean.getProcessCpuLoad() * 100;
            systemCpuLoad = sunOsBean.getCpuLoad() * 100;
        }

        return ServerStatusDto.CpuInfo.builder()
                .availableProcessors(availableProcessors)
                .systemLoadAverage(systemLoad)
                .processCpuUsage(Math.max(0, processCpuLoad))
                .systemCpuUsage(Math.max(0, systemCpuLoad))
                .build();
    }

    private ServerStatusDto.MemoryInfo getMemoryInfo() {
        OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();

        long totalPhysicalMemory = 0;
        long freePhysicalMemory = 0;

        if (osBean instanceof com.sun.management.OperatingSystemMXBean) {
            com.sun.management.OperatingSystemMXBean sunOsBean =
                (com.sun.management.OperatingSystemMXBean) osBean;
            totalPhysicalMemory = sunOsBean.getTotalMemorySize();
            freePhysicalMemory = sunOsBean.getFreeMemorySize();
        }

        long usedPhysicalMemory = totalPhysicalMemory - freePhysicalMemory;
        double usagePercent = totalPhysicalMemory > 0
            ? (double) usedPhysicalMemory / totalPhysicalMemory * 100
            : 0;

        return ServerStatusDto.MemoryInfo.builder()
                .totalPhysicalMemory(totalPhysicalMemory)
                .freePhysicalMemory(freePhysicalMemory)
                .usedPhysicalMemory(usedPhysicalMemory)
                .usagePercent(usagePercent)
                .build();
    }

    private ServerStatusDto.JvmInfo getJvmInfo() {
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        RuntimeMXBean runtimeBean = ManagementFactory.getRuntimeMXBean();

        long heapUsed = memoryBean.getHeapMemoryUsage().getUsed();
        long heapMax = memoryBean.getHeapMemoryUsage().getMax();
        long heapCommitted = memoryBean.getHeapMemoryUsage().getCommitted();

        long nonHeapUsed = memoryBean.getNonHeapMemoryUsage().getUsed();
        long nonHeapCommitted = memoryBean.getNonHeapMemoryUsage().getCommitted();

        long uptimeMillis = runtimeBean.getUptime();
        Duration uptime = Duration.ofMillis(uptimeMillis);

        String uptimeStr = String.format("%d일 %d시간 %d분",
                uptime.toDays(),
                uptime.toHoursPart(),
                uptime.toMinutesPart());

        double heapUsagePercent = heapMax > 0 ? (double) heapUsed / heapMax * 100 : 0;

        return ServerStatusDto.JvmInfo.builder()
                .heapUsed(heapUsed)
                .heapMax(heapMax)
                .heapCommitted(heapCommitted)
                .heapUsagePercent(heapUsagePercent)
                .nonHeapUsed(nonHeapUsed)
                .nonHeapCommitted(nonHeapCommitted)
                .uptime(uptimeStr)
                .uptimeMillis(uptimeMillis)
                .build();
    }

    private List<ServerStatusDto.DiskInfo> getDiskInfo() {
        List<ServerStatusDto.DiskInfo> disks = new ArrayList<>();

        File[] roots = File.listRoots();
        for (File root : roots) {
            long totalSpace = root.getTotalSpace();
            long freeSpace = root.getFreeSpace();
            long usableSpace = root.getUsableSpace();
            long usedSpace = totalSpace - freeSpace;

            if (totalSpace > 0) {
                double usagePercent = (double) usedSpace / totalSpace * 100;

                disks.add(ServerStatusDto.DiskInfo.builder()
                        .path(root.getAbsolutePath())
                        .totalSpace(totalSpace)
                        .freeSpace(freeSpace)
                        .usableSpace(usableSpace)
                        .usedSpace(usedSpace)
                        .usagePercent(usagePercent)
                        .build());
            }
        }

        return disks;
    }

    private ServerStatusDto.SystemInfo getSystemInfo() {
        OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
        RuntimeMXBean runtimeBean = ManagementFactory.getRuntimeMXBean();

        return ServerStatusDto.SystemInfo.builder()
                .osName(osBean.getName())
                .osVersion(osBean.getVersion())
                .osArch(osBean.getArch())
                .javaVersion(System.getProperty("java.version"))
                .javaVendor(System.getProperty("java.vendor"))
                .jvmName(runtimeBean.getVmName())
                .startTime(Instant.ofEpochMilli(runtimeBean.getStartTime()).toString())
                .build();
    }
}
