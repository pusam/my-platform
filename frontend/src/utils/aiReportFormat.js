/**
 * 주간 리포트 AI 본문(마크다운 비슷한 평문) → v-html 용 HTML.
 *
 * v-html 에 넣으므로 **이스케이프가 먼저**다(모델 출력은 믿지 않는다). 다루는 문법은 화면에 실제로 나온 것만:
 * 줄 머리 `#` 제목 · `**굵게**` · 줄 머리 `*`/`-` 목록 기호(• 로) · 줄바꿈. 그 밖은 원문 그대로 둔다.
 * 2026-10-01 전엔 굵게·줄바꿈만 처리해 '### 📊 성과 요약'·'*   ' 가 그대로 보였다.
 */
const bold = (s) => s.replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')

export function formatAiReport(text) {
  if (!text) return ''
  const escaped = String(text)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
  return escaped
    .split(/\r?\n/)
    .map((line) => {
      const heading = line.match(/^\s*#{1,6}\s+(.*)$/)
      if (heading) return `<strong class="ai-heading">${bold(heading[1])}</strong>`
      // '* 항목'·'- 항목'만 목록 — '**굵게**'(별 뒤 공백 없음)·'-3%'(숫자)는 건드리지 않는다
      return bold(line.replace(/^(\s*)[*-]\s+/, '$1• '))
    })
    .join('<br>')
}
