import { describe, it, expect } from 'vitest'
import { formatAiReport } from './aiReportFormat'

/**
 * 주간 리포트 AI 본문은 v-html 로 들어간다 — 이스케이프가 먼저다.
 * 2026-10-01 운영 화면에 '### 📊 성과 요약'·'*   ' 가 원문 그대로 보였다(굵게와 줄바꿈만 처리하고 있었다).
 */
describe('formatAiReport', () => {
  it('### 제목과 줄 머리 * 목록 기호를 원문 그대로 보여주지 않는다', () => {
    const html = formatAiReport('### 📊 성과 요약\n\n*   **매매** 없음\n- 둘째\n1.  기준 정립')
    expect(html).toBe(
      '<strong class="ai-heading">📊 성과 요약</strong><br><br>' +
      '• <strong>매매</strong> 없음<br>' +
      '• 둘째<br>' +
      '1.  기준 정립'
    )
  })

  it('줄 머리의 **굵게**·-3% 같은 숫자는 목록으로 오인하지 않는다', () => {
    expect(formatAiReport('**핵심**: 유지')).toBe('<strong>핵심</strong>: 유지')
    expect(formatAiReport('-3% 손절')).toBe('-3% 손절')
  })

  it('HTML 은 먼저 이스케이프한다 — 제목 안에서도', () => {
    expect(formatAiReport('<img src=x onerror=alert(1)>')).toBe('&lt;img src=x onerror=alert(1)&gt;')
    const heading = formatAiReport('### <b>x</b> & y')
    expect(heading).not.toContain('<b>')
    expect(heading).toContain('&lt;b&gt;x&lt;/b&gt; &amp; y')
  })

  it('CRLF 줄바꿈도 같은 결과다', () => {
    expect(formatAiReport('### 제목\r\n- 항목')).toBe('<strong class="ai-heading">제목</strong><br>• 항목')
  })

  it('빈 값은 빈 문자열', () => {
    expect(formatAiReport('')).toBe('')
    expect(formatAiReport(null)).toBe('')
    expect(formatAiReport(undefined)).toBe('')
  })
})
