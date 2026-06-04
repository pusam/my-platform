import { describe, it, expect } from 'vitest'
import {
  formatNumber,
  formatChange,
  getChangeClass,
  formatTradingValue
} from './marketFormatters.js'

describe('marketFormatters — 시장 데이터 포맷터 (첫 실제 테스트)', () => {
  describe('formatNumber', () => {
    it('null → "-"', () => expect(formatNumber(null)).toBe('-'))
    it('소수 2자리 + 천단위 콤마', () => expect(formatNumber(1234.5)).toBe('1,234.50'))
    it('decimals=0', () => expect(formatNumber(1000, 0)).toBe('1,000'))
  })

  describe('formatChange', () => {
    it('null → "-"', () => expect(formatChange(null)).toBe('-'))
    it('양수는 + 부호', () => expect(formatChange(1.5)).toBe('+1.50%'))
    it('0 도 + 부호 (>=0)', () => expect(formatChange(0)).toBe('+0.00%'))
    it('음수', () => expect(formatChange(-2.5)).toBe('-2.50%'))
  })

  describe('getChangeClass', () => {
    it('양수 → positive', () => expect(getChangeClass(1)).toBe('positive'))
    it('음수 → negative', () => expect(getChangeClass(-1)).toBe('negative'))
    it('inverse(환율): 상승=negative', () => expect(getChangeClass(1, true)).toBe('negative'))
    it('null → 빈 문자열', () => expect(getChangeClass(null)).toBe(''))
  })

  describe('formatTradingValue', () => {
    it('falsy → "0원"', () => expect(formatTradingValue(0)).toBe('0원'))
    it('조 단위', () => expect(formatTradingValue(1.5e12)).toBe('1.50조'))
    it('억 단위 (반올림)', () => expect(formatTradingValue(2e8)).toBe('2억'))
    it('만 단위 (반올림)', () => expect(formatTradingValue(5e4)).toBe('5만'))
    it('만 미만 → 원', () => expect(formatTradingValue(5000)).toBe('5,000원'))
  })
})
