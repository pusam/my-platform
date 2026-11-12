import { describe, it, expect } from 'vitest'
import { displayAddress, nextSortOrder, safeHref, sortLinks } from './siteLinks'

/**
 * 내 사이트 바로가기 — 사용자가 넣은 주소가 그대로 링크가 된다.
 * http/https 가 아닌 주소(javascript:·data: 등)는 링크로 그리지 않는다.
 */
describe('safeHref — http·https 만 링크가 된다', () => {
  it('http·https 주소는 그대로', () => {
    expect(safeHref('https://example.com')).toBe('https://example.com')
    expect(safeHref('http://example.com:8080/a?b=1#c')).toBe('http://example.com:8080/a?b=1#c')
    expect(safeHref('  https://example.com/x  ')).toBe('https://example.com/x')
    expect(safeHref('HTTPS://Example.com/Admin')).toBe('HTTPS://Example.com/Admin')
  })

  it.each([
    'javascript:alert(1)',
    'JavaScript:alert(1)',
    ' javascript:alert(1)',
    'java\nscript:alert(1)',
    'java\tscript:alert(1)',
    'data:text/html,<script>alert(1)</script>',
    'vbscript:msgbox(1)',
    'file:///etc/passwd',
    'ftp://example.com/',
    '//example.com/path',
    'example.com',
    '/sites',
    'https://example.com/a b',
    'https://user:secret@example.com/',
    'https://user@example.com/',
    ''
  ])('거절: %s', (url) => {
    expect(safeHref(url)).toBeNull()
  })

  it('문자열이 아니면 null', () => {
    expect(safeHref(null)).toBeNull()
    expect(safeHref(undefined)).toBeNull()
    expect(safeHref({ toString: () => 'https://example.com' })).toBeNull()
  })
})

describe('displayAddress — 카드에 보일 짧은 주소', () => {
  it('호스트 + 경로(루트면 호스트만)', () => {
    expect(displayAddress('https://example.com/')).toBe('example.com')
    expect(displayAddress('https://example.com')).toBe('example.com')
    expect(displayAddress('https://sub.example.com:8443/admin')).toBe('sub.example.com:8443/admin')
  })

  it('못 읽는 주소는 원문 그대로(글자로만 보인다)', () => {
    expect(displayAddress('javascript:alert(1)')).toBe('javascript:alert(1)')
    expect(displayAddress(null)).toBe('')
  })
})

describe('정렬·다음 순서', () => {
  it('순서 칸이 작은 것부터, 같으면 먼저 넣은 것부터 — 원본은 건드리지 않는다', () => {
    const links = [
      { id: 3, sortOrder: 2 },
      { id: 1, sortOrder: 2 },
      { id: 2, sortOrder: 1 },
      { id: 4 }
    ]
    expect(sortLinks(links).map((l) => l.id)).toEqual([4, 2, 1, 3])
    expect(links.map((l) => l.id)).toEqual([3, 1, 2, 4])
    expect(sortLinks(null)).toEqual([])
  })

  it('다음 순서 = 가장 큰 순서 + 1 (비면 1)', () => {
    expect(nextSortOrder([])).toBe(1)
    expect(nextSortOrder(null)).toBe(1)
    expect(nextSortOrder([{ sortOrder: 2 }, { sortOrder: 7 }, {}])).toBe(8)
  })
})
