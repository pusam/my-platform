package com.myplatform.backend.service;

import com.myplatform.backend.entity.CatalystAlertDedup;
import com.myplatform.backend.repository.CatalystAlertDedupRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 악재경보 dedup 선점(P2-CAT4) — 선점 행 내용·7일 청소·UNIQUE 위반 전파(benign 처리는 호출부 책임).
 * REQUIRES_NEW 격리 자체는 어노테이션 기반(V36 insertOutcomeIsolated 선례)이라 단위테스트 범위 밖.
 */
@ExtendWith(MockitoExtension.class)
class CatalystAlertDedupServiceTest {

    @Mock private CatalystAlertDedupRepository repository;
    @InjectMocks private CatalystAlertDedupService service;

    @Test
    @DisplayName("claimIsolated — 선점 행 저장(키·종목·일자)만, 청소 미포함(선점 트랜잭션 순수성)")
    void claim_savesRowOnly() {
        LocalDate date = LocalDate.of(2026, 7, 10);

        service.claimIsolated("CATNEG_005930_2026-07-10", "005930", date);

        ArgumentCaptor<CatalystAlertDedup> captor = ArgumentCaptor.forClass(CatalystAlertDedup.class);
        verify(repository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getAlertKey()).isEqualTo("CATNEG_005930_2026-07-10");
        assertThat(captor.getValue().getStockCode()).isEqualTo("005930");
        assertThat(captor.getValue().getAlertDate()).isEqualTo(date);
        // 청소는 별도 트랜잭션(cleanupOldIsolated) — 같은 트랜잭션에 두면 청소 DELETE 실패가
        // 선점 INSERT 를 롤백시켜 경보가 억제되는 결합이 있었다(2026-07-11 감사).
        verify(repository, never()).deleteByAlertDateBefore(any());
    }

    @Test
    @DisplayName("cleanupOldIsolated — 7일 지난 행 삭제(선점과 분리된 별도 트랜잭션)")
    void cleanup_deletesOldRows() {
        LocalDate date = LocalDate.of(2026, 7, 10);

        service.cleanupOldIsolated(date);

        verify(repository).deleteByAlertDateBefore(date.minusDays(7));
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("claimIsolated — UNIQUE 위반(경합 패자)은 그대로 전파(호출부가 benign 처리)")
    void claim_propagatesUniqueViolation() {
        doThrow(new DataIntegrityViolationException("uq_cad_alert_key"))
                .when(repository).saveAndFlush(any(CatalystAlertDedup.class));

        assertThatThrownBy(() -> service.claimIsolated("CATNEG_005930_2026-07-10", "005930",
                LocalDate.of(2026, 7, 10)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("releaseIsolated — 키 단건 삭제 위임")
    void release_deletesByKey() {
        service.releaseIsolated("CATNEG_005930_2026-07-10");
        verify(repository).deleteByAlertKey("CATNEG_005930_2026-07-10");
    }
}
