import { describe, it, expect } from 'vitest'
import {
  toSeriesTime, toSeriesData, toLineData, toChannelLines, toMarkerData,
  HTS_UP_COLOR, HTS_DOWN_COLOR,
} from './htsChartData'

describe('toMarkerData — 날짜 형식', () => {
  // ⚠ 시리즈(toSeriesTime)만 고쳤더니 **여기서 그대로 재발**했다(2026-09-22).
  //    마커는 날짜를 따로 만들고 있었고 substring(0,10) 이 yyyyMMdd 8자를 통과시켜
  //    setMarkers 가 "Invalid date string=20260811" 로 throw 했다.
  //    날짜를 만드는 지점이 둘이면 둘 다 같은 규칙을 써야 한다.
  it('KIS yyyyMMdd 봉과 keyPoint 를 매칭하고 time 을 yyyy-mm-dd 로 낸다', () => {
    const candles = [{ date: '20260811', open: 1, high: 2, low: 1, close: 2 }]
    const patterns = [{ signal: 'BULLISH', label: '쌍바닥', keyPoints: [{ date: '20260811' }] }]
    const m = toMarkerData(patterns, candles)
    expect(m).toHaveLength(1)
    expect(m[0].time).toBe('2026-08-11')
  })
  it('형식이 다른 두 소스도 같은 날짜로 매칭된다', () => {
    const candles = [{ date: '2026-08-11' }]
    const patterns = [{ signal: 'BEARISH', keyPoints: [{ date: '20260811' }] }]
    expect(toMarkerData(patterns, candles)[0].time).toBe('2026-08-11')
  })
  it('알 수 없는 날짜의 keyPoint 는 버린다 — 지어내지 않는다', () => {
    const candles = [{ date: '2026-08-11' }]
    const patterns = [{ signal: 'BULLISH', keyPoints: [{ date: 'bad' }, { date: null }] }]
    expect(toMarkerData(patterns, candles)).toHaveLength(0)
  })
})

describe('toSeriesTime', () => {
  it('일봉 → yyyy-MM-dd 앞 10자 그대로', () => {
    expect(toSeriesTime('2026-07-15', false, null)).toBe('2026-07-15')
    expect(toSeriesTime('2026-07-15T00:00:00', false, null)).toBe('2026-07-15')
  })
  it('분봉 HH:mm → KST epoch 초', () => {
    const t = toSeriesTime('09:30', true, '2026-07-15')
    // 2026-07-15 09:30 KST = 00:30 UTC
    expect(t).toBe(Math.floor(Date.UTC(2026, 6, 15, 0, 30, 0) / 1000))
  })
  // ⚠ 2026-09-22 운영 콘솔에서 잡은 실제 예외:
  //    "Invalid date string=20260811, expected format=yyyy-mm-dd"
  //    KIS 일봉은 stck_bsop_date 가 yyyyMMdd(8자)라 `s.length >= 10` 분기를 타지 못하고
  //    8자 문자열이 **그대로** lightweight-charts 로 들어가 throw 했다. 예외가 setData 를
  //    중단시켜 종목상세가 통째로 덜 그려졌다(본문 1,459자에서 멈춤, PER·차트·결론 모두 없음).
  it('KIS yyyyMMdd(8자)도 일봉으로 받는다 — 그대로 넘기면 차트가 throw 한다', () => {
    expect(toSeriesTime('20260811', false, null)).toBe('2026-08-11')
    expect(toSeriesTime(20260811, false, null)).toBe('2026-08-11')   // 숫자로 와도 동일
  })

  it('알 수 없는 짧은 형식은 null — 문자열을 그대로 넘기지 않는다', () => {
    expect(toSeriesTime('2026-08', false, null)).toBeNull()
    expect(toSeriesTime('abcdefgh', false, null)).toBeNull()
  })

  it('결측/형식 오류 → null', () => {
    expect(toSeriesTime(null, false, null)).toBeNull()
    expect(toSeriesTime('bad', true, '2026-07-15')).toBeNull()
    expect(toSeriesTime('09:30', true, null)).toBeNull()   // todayYmd 없음
  })
})

describe('toSeriesData', () => {
  const candles = [
    { date: '2026-07-13', open: 100, high: 105, low: 99, close: 104 },   // 상승
    { date: '2026-07-14', open: 104, high: 106, low: 100, close: 101 },  // 하락
  ]
  const volumes = [{ volume: 1000 }, { volume: 2000 }]

  it('캔들/거래량 시리즈 변환 + 거래량 색=봉 방향색', () => {
    const { candles: c, volumes: v } = toSeriesData(candles, volumes, false, null)
    expect(c).toHaveLength(2)
    expect(c[0]).toMatchObject({ time: '2026-07-13', open: 100, high: 105, low: 99, close: 104 })
    expect(v[0].value).toBe(1000)
    expect(v[0].color).toContain(HTS_UP_COLOR)     // 상승 봉 = 빨강
    expect(v[1].color).toContain(HTS_DOWN_COLOR)   // 하락 봉 = 파랑
  })

  it('오름차순 정렬 + time 중복 dedupe(마지막 값 우선, §6-1)', () => {
    const dup = [
      { date: '2026-07-14', open: 1, high: 1, low: 1, close: 1 },
      { date: '2026-07-14', open: 9, high: 9, low: 9, close: 9 },   // 같은 날짜 → 마지막 우선
      { date: '2026-07-13', open: 5, high: 5, low: 5, close: 5 },
    ]
    const { candles: c } = toSeriesData(dup, [], false, null)
    expect(c.map(x => x.time)).toEqual(['2026-07-13', '2026-07-14'])   // 정렬됨
    expect(c[1].close).toBe(9)   // 중복은 마지막 값
  })

  it('비정상 봉(NaN) 건너뜀(§4c)', () => {
    const bad = [{ date: '2026-07-14', open: NaN, high: 1, low: 1, close: 1 }]
    expect(toSeriesData(bad, [], false, null).candles).toHaveLength(0)
  })
})

describe('toLineData', () => {
  // displayCandles 과거→최신 2개, rawMa 최신→과거(chartData 규약)
  const displayCandles = [{ date: '2026-07-13' }, { date: '2026-07-14' }]
  it('최신→과거 원자료를 displayCandles 정렬로 맞춤 + null 생략', () => {
    const raw = [114, null]   // 최신=114(07-14), 과거=null(07-13)
    const line = toLineData(raw, displayCandles, false, null)
    expect(line).toEqual([{ time: '2026-07-14', value: 114 }])   // null(07-13) 생략
  })
  it('빈/비배열 입력 → []', () => {
    expect(toLineData(null, displayCandles, false, null)).toEqual([])
    expect(toLineData([1, 2], [], false, null)).toEqual([])
  })
})

describe('toChannelLines', () => {
  const channel = {
    upperStart: 110, upperEnd: 120, lowerStart: 90, lowerEnd: 100, midStart: 100, midEnd: 110,
  }
  it('상단/하단/중심 2점 라인 — 첫 time→Start, 마지막 time→End', () => {
    const lines = toChannelLines(channel, '2026-07-13', '2026-07-14')
    expect(lines.upper).toEqual([
      { time: '2026-07-13', value: 110 }, { time: '2026-07-14', value: 120 }])
    expect(lines.lower[1]).toEqual({ time: '2026-07-14', value: 100 })
    expect(lines.mid[0]).toEqual({ time: '2026-07-13', value: 100 })
  })
  it('채널 null / time 결측·동일 → null', () => {
    expect(toChannelLines(null, 'a', 'b')).toBeNull()
    expect(toChannelLines(channel, 'a', 'a')).toBeNull()
    expect(toChannelLines(channel, null, 'b')).toBeNull()
  })
})

describe('toMarkerData', () => {
  const displayCandles = [{ date: '2026-07-13' }, { date: '2026-07-14' }]
  it('표시된 날짜의 keyPoint 만 마커화 + BULLISH 아래빨강/BEARISH 위파랑', () => {
    const patterns = [
      { signal: 'BULLISH', label: '더블바텀', keyPoints: [{ date: '2026-07-13', price: 100 }] },
      { signal: 'BEARISH', label: '더블탑', keyPoints: [{ date: '2026-07-14', price: 120 }] },
      { signal: 'BULLISH', label: '범위밖', keyPoints: [{ date: '2026-06-01', price: 90 }] }, // 표시 밖 제외
    ]
    const markers = toMarkerData(patterns, displayCandles)
    expect(markers).toHaveLength(2)
    expect(markers[0]).toMatchObject({ time: '2026-07-13', position: 'belowBar', color: HTS_UP_COLOR })
    expect(markers[1]).toMatchObject({ time: '2026-07-14', position: 'aboveBar', color: HTS_DOWN_COLOR })
  })
  it('빈 입력 → []', () => {
    expect(toMarkerData([], displayCandles)).toEqual([])
    expect(toMarkerData(null, displayCandles)).toEqual([])
  })
})
