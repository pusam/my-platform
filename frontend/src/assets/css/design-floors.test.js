import { describe, it, expect } from 'vitest'
import { readFileSync, readdirSync, statSync } from 'node:fs'
import { join } from 'node:path'

/**
 * 디자인 바닥값 회귀 가드(2026-09-21 점검).
 *
 * <p>모바일 실측에서 <b>11px 미만 글씨가 125곳</b> 있었다 — 종합판단 보드 표 본문(`jb-table td`)과
 * AI 크루 대화 본문이 <b>9px</b> 이었다. 한글은 자소가 촘촘해 9~10px 에서 판독이 무너진다.
 * 전부 11px 로 올렸고(라이브 주입 실험에서 레이아웃 깨짐 0건), 이 테스트가 되돌아오는 것을 막는다.
 *
 * <p>⚠ 밀도가 필요하면 11px 까지다. 더 줄여야 할 이유가 생기면 이 테스트를 지우지 말고
 * 그 선택의 근거를 여기에 적을 것.
 */
const SRC = join(process.cwd(), 'src')   // vitest 는 frontend/ 에서 실행된다
const MIN_PX = 11

function walk(dir, out = []) {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name)
    if (statSync(p).isDirectory()) walk(p, out)
    else if (/\.(vue|css)$/.test(name)) out.push(p)
  }
  return out
}

describe('디자인 바닥값', () => {
  it(`font-size 는 ${MIN_PX}px 미만으로 선언하지 않는다`, () => {
    const offenders = []
    for (const file of walk(SRC)) {
      const text = readFileSync(file, 'utf8')
      text.split(/\r?\n/).forEach((line, i) => {
        // px 뿐 아니라 rem/em 도 본다 — 처음엔 px 만 봐서 0.65rem(=10.4px) 14곳을 놓쳤다.
        const m = line.match(/font-size:\s*([\d.]+)(px|rem|em)/)
        if (!m) return
        const px = m[2] === 'px' ? parseFloat(m[1]) : parseFloat(m[1]) * 16
        if (px < MIN_PX) {
          offenders.push(`${file.replace(SRC, '')}:${i + 1}  ${m[0]}`)
        }
      })
    }
    expect(offenders, `11px 미만 글씨:\n${offenders.join('\n')}`).toEqual([])
  })
})

/**
 * 한글 줄바꿈(2026-10-01 '오늘' 탭 점검). 기본값(word-break: normal)은 음절 사이 어디서나 끊어
 * "관 / 망이 결론입니다", "최소 변 / 화량"처럼 단어가 쪼개졌고, 매매 탭(375px)에선 "97,900 / 원" 같은
 * 숫자·단위가 87곳 갈라졌다. keep-all 로 단어 단위로 바꾸고, 줄보다 긴 단어만 break-word 로 끊는다.
 *
 * ⚠ overflow-wrap: anywhere 는 쓰지 않는다 — 최소 폭 계산까지 바꿔 표가 화면에 맞춰 줄면서
 * 종목코드·가격이 두 줄로 쪼개졌다(375px 시장 탭 라이브 주입 실측 118곳, break-word 는 0곳).
 */
describe('한글 줄바꿈', () => {
  const css = readFileSync(join(SRC, 'assets/css/common.css'), 'utf8')
  // `body { … }` 블록만(앞이 줄 시작) — `html, body` 같은 묶음 규칙이나 다른 선택자의 속성은 보지 않는다
  const bodyRules = [...css.matchAll(/(^|\n)body\s*\{([^}]*)\}/g)].map((m) => m[2]).join('\n')

  it('body 는 단어 단위로 줄을 바꾸고(keep-all) 긴 단어만 끊는다(break-word)', () => {
    expect(bodyRules).toMatch(/word-break:\s*keep-all/)
    expect(bodyRules).toMatch(/overflow-wrap:\s*break-word/)
  })

  it('overflow-wrap: anywhere 로 바꾸지 않는다 — 표의 숫자가 쪼개진다', () => {
    expect(bodyRules).not.toMatch(/overflow-wrap:\s*anywhere/)
  })
})
