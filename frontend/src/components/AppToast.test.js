import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'

/**
 * 전역 알림 토스트(성공·오류·경고·안내, 2026-10-01 점검) — 흰 글자를 밝은 500 계열 90% 배경에 올려
 * 대비가 2.2(경고)~3.9(오류)였고, 닫기 '×' 는 이름이 없고 24px 미만이었다. 동작 뒤에만 떠서 화면 점검에 안 잡혔다.
 */
const src = readFileSync(join(process.cwd(), 'src/components/AppToast.vue'), 'utf8')
const css = src.slice(src.indexOf('<style')).replace(/\/\*[\s\S]*?\*\//g, '')
const rule = (sel) => { const i = css.indexOf(sel + ' {'); return i < 0 ? '' : css.slice(i, css.indexOf('}', i)) }

describe('AppToast', () => {
  it('흰 글자 아래 배경은 밝은 500 계열이 아니다', () => {
    expect(rule('.app-toast.success')).not.toMatch(/background:\s*rgba\(34,\s*197,\s*94/)
    expect(rule('.app-toast.error')).not.toMatch(/background:\s*rgba\(239,\s*68,\s*68/)
    expect(rule('.app-toast.warning')).not.toMatch(/background:\s*rgba\(245,\s*158,\s*11/)
    expect(rule('.app-toast.info')).not.toMatch(/background:\s*rgba\(59,\s*130,\s*246/)
  })

  it('닫기 버튼은 이름·type 이 있고 24px 이상이다', () => {
    expect(src).toMatch(/<button type="button" class="toast-dismiss" aria-label="닫기"/)
    expect(rule('.toast-dismiss')).toMatch(/min-height:\s*(2[4-9]|[3-9]\d)px/)
    expect(rule('.toast-dismiss')).toMatch(/min-width:\s*(2[4-9]|[3-9]\d)px/)
  })
})
