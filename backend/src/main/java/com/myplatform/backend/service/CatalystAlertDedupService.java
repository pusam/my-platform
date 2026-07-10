package com.myplatform.backend.service;

import com.myplatform.backend.entity.CatalystAlertDedup;
import com.myplatform.backend.repository.CatalystAlertDedupRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * 악재 조기경보 dedup 선점 (P2-CAT4, V47) — {@code UNIQUE(alert_key)} 조건부 INSERT 로
 * 동시 최초 분류(배치+온디맨드) 경합의 <b>승자를 DB 가 판정</b>한다.
 *
 * <p>두 메서드 모두 {@code REQUIRES_NEW} 격리(V36 insertOutcomeIsolated 패턴) — UNIQUE 위반
 * (경합 패자)이 호출부 트랜잭션을 rollback-only 로 오염시키지 않게 새 트랜잭션만 롤백한다.
 * 예외는 그대로 전파하고 benign 처리(패자=중복 억제)는 호출부
 * {@link CatalystRiskAlertService} 가 담당한다.
 */
@Service
@RequiredArgsConstructor
public class CatalystAlertDedupService {

    private final CatalystAlertDedupRepository repository;

    /**
     * 선점 시도 — INSERT 성공하면 이 호출이 승자(당일 최초). 이미 선점됐으면(같은 alert_key)
     * {@link org.springframework.dao.DataIntegrityViolationException} 이 전파된다(호출부 benign 처리).
     *
     * <p>선점 성공 시 7일 지난 행을 함께 청소 — 키에 일자가 포함돼 지난 행은 dedup 에 불필요
     * (전용 정리 크론 없이 테이블 크기 유지).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void claimIsolated(String alertKey, String stockCode, LocalDate alertDate) {
        repository.saveAndFlush(new CatalystAlertDedup(alertKey, stockCode, alertDate));
        repository.deleteByAlertDateBefore(alertDate.minusDays(7));
    }

    /**
     * 선점 반납 — 승자가 발송에 <b>전부 실패</b>했을 때만 호출(다음 분류 기회에 재시도 가능).
     * 일부 채널이라도 발송됐으면 반납하지 않는다(재시도 시 그 채널 중복 = 스팸 방지 우선).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void releaseIsolated(String alertKey) {
        repository.deleteByAlertKey(alertKey);
    }
}
