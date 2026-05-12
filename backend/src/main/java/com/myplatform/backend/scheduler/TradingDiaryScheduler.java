package com.myplatform.backend.scheduler;

import com.myplatform.backend.service.SchedulerLockService;
import com.myplatform.backend.service.TradingDiaryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class TradingDiaryScheduler {

    private static final Logger log = LoggerFactory.getLogger(TradingDiaryScheduler.class);

    private final TradingDiaryService diary;
    private final SchedulerLockService schedulerLockService;

    public TradingDiaryScheduler(TradingDiaryService diary, SchedulerLockService schedulerLockService) {
        this.diary = diary;
        this.schedulerLockService = schedulerLockService;
    }

    /** 매주 일요일 19:00 KST — 지난주(월~일) 분석 리포트 자동 생성 (REAL + VIRTUAL 둘 다) */
    @Scheduled(cron = "0 0 19 * * SUN", zone = "Asia/Seoul")
    public void generateWeeklyReport() {
        if (!schedulerLockService.tryLock("trading-diary.weekly", Duration.ofHours(1))) {
            log.debug("[주간리포트] 다른 인스턴스에서 진행 중 — 스킵");
            return;
        }
        for (String mode : new String[]{"REAL", "VIRTUAL"}) {
            log.info("[주간리포트] {} 자동 생성 시작", mode);
            try {
                diary.generateLastWeekReport(mode, "system-scheduler");
                log.info("[주간리포트] {} 자동 생성 완료", mode);
            } catch (Exception e) {
                log.error("[주간리포트] {} 자동 생성 실패", mode, e);
            }
        }
    }
}
