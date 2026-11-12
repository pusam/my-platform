// 내 사이트 바로가기(/sites) — 주소를 링크로 그리기 전의 검사와 표시용 가공 (순수함수).
//
// 사용자가 넣은 주소가 그대로 <a href> 가 된다. 서버(SiteLinkRules)가 저장 전에 거르지만 화면도 한 번 더 본다 —
// 표에 직접 들어간 값이나 옛 값이 있어도 javascript:·data: 주소가 링크가 되지 않게. 못 믿는 주소는 null(링크 없이 글자로만 보인다).

/** http·https 주소면 그 주소, 아니면 null. */
export const safeHref = (url) => {
  if (typeof url !== 'string') return null
  const v = url.trim()
  // 공백·제어문자가 섞이면 브라우저마다 다르게 읽는다("java\nscript:" 류)
  if (!v || /[\s\u0000-\u001f\u007f]/.test(v)) return null
  let parsed
  try {
    parsed = new URL(v)
  } catch {
    return null
  }
  if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') return null
  if (!parsed.hostname) return null
  if (parsed.username || parsed.password) return null
  return v
}

/** 카드에 보일 짧은 주소 — 'example.com/admin'. 못 읽으면 원문 그대로. */
export const displayAddress = (url) => {
  const href = safeHref(url)
  if (!href) return typeof url === 'string' ? url : ''
  const u = new URL(href)
  const path = u.pathname === '/' ? '' : u.pathname
  return `${u.host}${path}`
}

/** 화면 정렬 — 순서 칸이 작은 것부터, 같으면 먼저 넣은 것(id)부터. 서버와 같은 규칙. */
export const sortLinks = (links) =>
  [...(links || [])].sort((a, b) => (a.sortOrder ?? 0) - (b.sortOrder ?? 0) || (a.id ?? 0) - (b.id ?? 0))

/** 추가 폼에 미리 넣어 줄 다음 순서 값. */
export const nextSortOrder = (links) =>
  (links || []).reduce((max, l) => Math.max(max, l.sortOrder ?? 0), 0) + 1
