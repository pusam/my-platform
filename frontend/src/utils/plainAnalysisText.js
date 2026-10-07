/**
 * AI(Gemini) 서술을 평문으로 — 마크다운 기호만 걷어 내고 줄은 살린다(2026-10-07).
 *
 * 종목 상세 'AI 매매 전략'이 '## …'·'**■ 종합 판단:**'을 그대로 한 덩어리로 보였다. 모델 출력은 계속 {{ }} 평문으로 그린다
 * (v-html 금지) — 여기서는 기호만 지운다: 줄머리 '#' 제목, '**굵게**'·'__굵게__', 줄머리 '* '·'- ' 목록(→ '• ').
 * 숫자 사이의 '*'(곱셈) 같은 홑 별표는 건드리지 않는다.
 */
export function plainAnalysisText(text) {
  if (typeof text !== 'string' || text === '') return ''
  return text
    .split(/\r?\n/)
    .map((line) => line
      .replace(/^\s{0,3}#{1,6}\s+/, '')
      .replace(/\*\*(.+?)\*\*/g, '$1')
      .replace(/__(.+?)__/g, '$1')
      .replace(/^(\s*)[*-]\s+/, '$1• ')
      .trimEnd())
    .join('\n')
    .trim()
}
