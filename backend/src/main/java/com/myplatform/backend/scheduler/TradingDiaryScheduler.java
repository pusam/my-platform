package com.myplatform.backend.scheduler;

import com.myplatform.backend.service.TradingDiaryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class TradingDiaryScheduler {

    private static final Logger log = LoggerFactory.getLogger(TradingDiaryScheduler.class);

    private final TradingDiaryService diary;

    public TradingDiaryScheduler(TradingDiaryService diary) {
        this.diary = diary;
    }

    /** 매주 일요일 19:00 KST — 지난주(월~일) 분석 리포트 자동 생성 */
    @Scheduled(cron = "0 0 19 * * SUN", zone = "Asia/Seoul")
    public void generateWeeklyReport() {
        log.info("[주간리포트] 자동 생성 시작");
        try {
            diary.generateLastWeekReport("system-scheduler");
            log.info("[주간리포트] 자동 생성 완료");
        } catch (Exception e) {
            log.error("[주간리포트] 자동 생성 실패", e);
        }
    }
}
