/**
 * 종목 상세 '투자자별 수급(당일)' 패널의 안내 문구(2026-10-07).
 *
 * KIS 종목별 투자자 집계(주식현재가 투자자 FHKST01010900)는 "당일 데이터는 장 종료 후 제공"(공식 샘플)이라 장중엔 외국인·기관이
 * 비는 게 정상이다 — 그런데 배지는 '실시간'이고 칸은 '-'뿐이라 고장처럼 보였다. 장중 실시간인 것은 프로그램 매매(같은 날 KIS 경로 정정)다.
 * 장후인데 비면 조회 실패라 이유를 지어내지 않는다(빈 문자열 — 기존 '-' 그대로, §4c).
 */
export function investorFlowNote(supplyDemand) {
  if (!supplyDemand) return ''
  const investorsMissing = supplyDemand.foreignNetBuy == null && supplyDemand.instNetBuy == null
  if (!investorsMissing) return ''
  if (supplyDemand.dataSource === '실시간') {
    return '외국인·기관 당일 집계는 장 마감(15:40) 뒤 제공 — 장중엔 프로그램 매매만 실시간입니다.'
  }
  if (supplyDemand.dataSource === '장전(초기화)') {
    return '장 시작 전 — 당일 수급은 아직 없습니다.'
  }
  return ''
}
