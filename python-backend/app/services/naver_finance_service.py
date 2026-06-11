"""네이버 금융 크롤링 서비스 - 실시간 한국 주식 데이터

크롤링 대상:
- 외국인/기관 순매수 상위: sise_deal_rank.naver
- 업종별 시세: sise_group.naver
- 거래량 상위: sise_quant.naver
- 상승률 상위: sise_rise.naver
- 시장 지수: sise_index_day.naver
"""
import asyncio
import logging
import re

import httpx
from bs4 import BeautifulSoup

from app.services.cache_service import redis_client
from app.utils.korean_market import get_cache_ttl

logger = logging.getLogger(__name__)

HEADERS = {
    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36',
    'Referer': 'https://finance.naver.com',
}


# ═══════════════════ 유틸 ═══════════════════

def _parse_int(text: str) -> int:
    if not text:
        return 0
    text = re.sub(r'[^\d\-]', '', text.strip())
    try:
        return int(text)
    except ValueError:
        return 0


def _parse_float(text: str) -> float:
    if not text:
        return 0.0
    text = text.strip().replace('%', '').replace('+', '').replace(',', '')
    try:
        return float(text)
    except ValueError:
        return 0.0


def _extract_code(tag) -> str:
    if not tag:
        return ''
    href = tag.get('href', '')
    m = re.search(r'code=(\d{6})', href)
    return m.group(1) if m else ''


async def _fetch_page(url: str) -> BeautifulSoup:
    async with httpx.AsyncClient(timeout=10) as client:
        resp = await client.get(url, headers=HEADERS)
        resp.encoding = 'euc-kr'
        return BeautifulSoup(resp.text, 'html.parser')


def _parse_sign(td) -> int:
    """전일비 칼럼에서 부호 추출: +1, -1, 0"""
    img = td.find('img') if td else None
    if img:
        alt = img.get('alt', '')
        if '상승' in alt or '상한' in alt:
            return 1
        if '하락' in alt or '하한' in alt:
            return -1
    return 0


# ═══════════════════ 외국인/기관 순매수 상위 ═══════════════════

async def get_investor_top_trades(investor_type: str = 'FOREIGN', limit: int = 10) -> list:
    """외국인/기관 순매수 상위 종목 (네이버 금융 크롤링)

    Returns: [{ stockCode, stockName, netBuyAmount, rankChange }, ...]
    """
    cache_key = f"naver_investor_{investor_type}_{limit}"
    cached = await redis_client.get(cache_key)
    if cached:
        return cached

    # 9000=외국인, 1000=기관
    gubun = '9000' if investor_type == 'FOREIGN' else '1000'

    results = []
    for sosok in ['0', '1']:  # KOSPI + KOSDAQ
        url = f'https://finance.naver.com/sise/sise_deal_rank.naver?sosok={sosok}&investor_gubun={gubun}'
        try:
            soup = await _fetch_page(url)
            table = soup.find('table', class_='type_2')
            if not table:
                continue

            for row in table.find_all('tr'):
                cols = row.find_all('td')
                if len(cols) < 7:
                    continue

                name_tag = cols[1].find('a')
                if not name_tag:
                    continue

                stock_name = name_tag.text.strip()
                stock_code = _extract_code(name_tag)
                if not stock_code or not stock_name:
                    continue

                current_price = _parse_int(cols[2].text)
                if current_price <= 0:
                    continue

                # 순매수량 (주 단위) — 마지막 숫자 칼럼
                net_shares = _parse_int(cols[-1].text)
                if net_shares <= 0:
                    continue

                # 순매수 금액(원) = 순매수량 * 현재가
                net_buy_amount = net_shares * current_price

                results.append({
                    'stockCode': stock_code,
                    'stockName': stock_name,
                    'netBuyAmount': net_buy_amount,
                    'rankChange': 0,
                })
        except Exception as e:
            logger.warning(f"Naver investor {investor_type} sosok={sosok} error: {e}")

    results.sort(key=lambda x: x['netBuyAmount'], reverse=True)
    results = results[:limit]

    if results:
        await redis_client.set(cache_key, results, get_cache_ttl(300))
    return results


# ═══════════════════ 업종별 시세 ═══════════════════

async def get_sector_data() -> list:
    """업종별 등락률 (네이버 금융 크롤링)

    Returns: [{ sectorName, changeRate, totalTradingValue }, ...]
    """
    cache_key = "naver_sectors"
    cached = await redis_client.get(cache_key)
    if cached:
        return cached

    url = 'https://finance.naver.com/sise/sise_group.naver?type=upjong'
    try:
        soup = await _fetch_page(url)
        table = soup.find('table', class_='type_1')
        if not table:
            return []

        results = []
        for row in table.find_all('tr'):
            cols = row.find_all('td')
            if len(cols) < 5:
                continue

            name_tag = cols[0].find('a')
            if not name_tag:
                continue

            sector_name = name_tag.text.strip()
            if not sector_name:
                continue

            # 전일대비 부호 판별
            sign = _parse_sign(cols[1])
            # 등락률
            change_rate = _parse_float(cols[2].text)
            if sign < 0 and change_rate > 0:
                change_rate = -change_rate

            # 거래대금 (백만원 단위 → 원 단위)
            trading_val = _parse_int(cols[4].text) if len(cols) > 4 else 0

            results.append({
                'sectorName': sector_name,
                'changeRate': round(change_rate, 2),
                'totalTradingValue': trading_val * 1000000 if trading_val > 0 else 0,
            })

        results.sort(key=lambda x: abs(x['changeRate']), reverse=True)
        results = results[:12]

        if results:
            await redis_client.set(cache_key, results, get_cache_ttl(300))
        return results
    except Exception as e:
        logger.error(f"Naver sector data error: {e}")
        return []


# ═══════════════════ 거래량 상위 ═══════════════════

async def get_top_volume_stocks(limit: int = 10, market: str = 'ALL') -> list:
    """거래량 상위 종목 (네이버 금융 크롤링)

    Returns: [{ stockCode, stockName, currentPrice, changeRate, volume }, ...]
    """
    cache_key = f"naver_volume_{market}_{limit}"
    cached = await redis_client.get(cache_key)
    if cached:
        return cached

    sosok_list = ['0', '1'] if market == 'ALL' else (['0'] if market == 'KOSPI' else ['1'])
    results = []

    for sosok in sosok_list:
        url = f'https://finance.naver.com/sise/sise_quant.naver?sosok={sosok}'
        try:
            soup = await _fetch_page(url)
            table = soup.find('table', class_='type_2')
            if not table:
                continue

            for row in table.find_all('tr'):
                cols = row.find_all('td')
                if len(cols) < 6:
                    continue

                name_tag = cols[1].find('a')
                if not name_tag:
                    continue

                stock_name = name_tag.text.strip()
                stock_code = _extract_code(name_tag)
                if not stock_code or not stock_name:
                    continue

                current_price = _parse_int(cols[2].text)
                if current_price <= 0:
                    continue

                sign = _parse_sign(cols[3])
                change_rate = _parse_float(cols[4].text)
                if sign < 0 and change_rate > 0:
                    change_rate = -change_rate

                volume = _parse_int(cols[5].text)

                results.append({
                    'stockCode': stock_code,
                    'stockName': stock_name,
                    'currentPrice': current_price,
                    'changeRate': round(change_rate, 2),
                    'volume': volume,
                })
        except Exception as e:
            logger.warning(f"Naver volume sosok={sosok} error: {e}")

    results.sort(key=lambda x: x.get('volume', 0), reverse=True)
    results = results[:limit]

    if results:
        await redis_client.set(cache_key, results, get_cache_ttl(120))
    return results


# ═══════════════════ 상승률 상위 ═══════════════════

async def get_top_rising_stocks(limit: int = 10, market: str = 'ALL') -> list:
    """상승률 상위 종목 (네이버 금융 크롤링)

    Returns: [{ stockCode, stockName, currentPrice, changeRate, volume }, ...]
    """
    cache_key = f"naver_rising_{market}_{limit}"
    cached = await redis_client.get(cache_key)
    if cached:
        return cached

    sosok_list = ['0', '1'] if market == 'ALL' else (['0'] if market == 'KOSPI' else ['1'])
    results = []

    for sosok in sosok_list:
        url = f'https://finance.naver.com/sise/sise_rise.naver?sosok={sosok}'
        try:
            soup = await _fetch_page(url)
            table = soup.find('table', class_='type_2')
            if not table:
                continue

            for row in table.find_all('tr'):
                cols = row.find_all('td')
                if len(cols) < 6:
                    continue

                name_tag = cols[1].find('a')
                if not name_tag:
                    continue

                stock_name = name_tag.text.strip()
                stock_code = _extract_code(name_tag)
                if not stock_code or not stock_name:
                    continue

                current_price = _parse_int(cols[2].text)
                if current_price <= 0:
                    continue

                change_rate = _parse_float(cols[4].text)
                volume = _parse_int(cols[5].text)

                results.append({
                    'stockCode': stock_code,
                    'stockName': stock_name,
                    'currentPrice': current_price,
                    'changeRate': round(change_rate, 2),
                    'volume': volume,
                })
        except Exception as e:
            logger.warning(f"Naver rising sosok={sosok} error: {e}")

    results.sort(key=lambda x: x.get('changeRate', 0), reverse=True)
    results = results[:limit]

    if results:
        await redis_client.set(cache_key, results, get_cache_ttl(120))
    return results


# ═══════════════════ 시장 지수 ═══════════════════

async def get_market_indices() -> dict:
    """KOSPI/KOSDAQ 지수 (네이버 금융 크롤링)

    Returns: { kospiIndex, kospiChangeRate, kosdaqIndex, kosdaqChangeRate, adr, marketStatus }
    """
    cache_key = "naver_market_indices"
    cached = await redis_client.get(cache_key)
    if cached:
        return cached

    result = {}
    for name, code in [('kospi', 'KOSPI'), ('kosdaq', 'KOSDAQ')]:
        url = f'https://finance.naver.com/sise/sise_index.naver?code={code}'
        try:
            soup = await _fetch_page(url)
            now_val = soup.find('id', id='now_value') or soup.find('em', id='now_value')
            if now_val:
                result[f'{name}Index'] = now_val.text.strip()

            change_val = soup.find('span', class_='tah')
            if change_val:
                rate = _parse_float(change_val.text)
                # 부호 확인
                blind = soup.find('span', class_='blind')
                if blind and '하락' in blind.text:
                    rate = -abs(rate)
                result[f'{name}ChangeRate'] = round(rate, 2)
        except Exception as e:
            logger.warning(f"Naver {name} index error: {e}")

    # 간단 시장 상황
    kr = result.get('kospiChangeRate', 0)
    kdr = result.get('kosdaqChangeRate', 0)
    parts = []
    parts.append('코스피 상승' if kr > 0 else '코스피 하락' if kr < 0 else '코스피 보합')
    parts.append('코스닥 상승' if kdr > 0 else '코스닥 하락' if kdr < 0 else '코스닥 보합')
    # adr 키 제거 (점검 수정 2026-06-11): 과거 상수 50.0 을 실데이터처럼 반환 — 위장 금지.
    # ADR(20일) 실계산은 Java MarketTimingService 가 담당. 여기선 미제공이 정직.
    result['marketStatus'] = ' · '.join(parts)

    if result:
        await redis_client.set(cache_key, result, get_cache_ttl(60))
    return result


# ═══════════════════ 수급 급증 ═══════════════════

async def get_surge_stocks(limit: int = 10) -> list:
    """수급 급증 종목 = 거래량 상위 + 상승 종목

    Returns: [{ stockCode, stockName, changeRate, volumeTenThousands }, ...]
    """
    stocks = await get_top_volume_stocks(30, 'ALL')
    results = []
    for s in stocks:
        if s.get('changeRate', 0) > 0 and s.get('volume', 0) > 0:
            results.append({
                'stockCode': s['stockCode'],
                'stockName': s['stockName'],
                'changeRate': s['changeRate'],
                # 거래량(만주 단위) — 비율(ratio)이 아님. 과거 'surgeRatio' 명칭이
                # 백분율처럼 오해됐음 (점검 수정 2026-06-11). 정렬용 점수.
                'volumeTenThousands': int(s.get('volume', 0) / 10000),
            })
    results.sort(key=lambda x: x['volumeTenThousands'], reverse=True)
    return results[:limit]


# ═══════════════════ 연속 매수 (간이 방식) ═══════════════════

async def get_consecutive_buy(limit: int = 10) -> list:
    """당일 외국인/기관 순매수 상위 종목

    ⚠ 점검 수정 2026-06-11: 네이버 데이터로는 '연속' 매수일수를 알 수 없다.
    과거엔 순위로부터 max(3, 8-i) 식으로 연속일수를 '생성'해 실데이터처럼
    반환했음 — 날조 금지(§4c). consecutiveDays 는 None(미상)으로 내리고
    basis 로 실제 의미(당일 순매수 상위)를 명시한다. 진짜 연속매수 판정은
    Java backend 의 InvestorTradeService(KIS 일별 데이터 기반)가 담당.

    Returns: [{ stockCode, stockName, consecutiveDays: None, basis, investorType }, ...]
    """
    cache_key = f"naver_consecutive_{limit}"
    cached = await redis_client.get(cache_key)
    if cached:
        return cached

    foreign = await get_investor_top_trades('FOREIGN', 20)
    institution = await get_investor_top_trades('INSTITUTION', 20)

    results = []
    seen = set()

    for item in foreign[:7]:
        code = item['stockCode']
        if code not in seen:
            seen.add(code)
            results.append({
                'stockCode': code,
                'stockName': item['stockName'],
                'consecutiveDays': None,  # 미상 — 당일 데이터만으로 판별 불가
                'basis': 'TODAY_TOP_NETBUY',
                'investorType': 'FOREIGN',
            })

    for item in institution[:7]:
        code = item['stockCode']
        if code not in seen:
            seen.add(code)
            results.append({
                'stockCode': code,
                'stockName': item['stockName'],
                'consecutiveDays': None,
                'basis': 'TODAY_TOP_NETBUY',
                'investorType': 'INSTITUTION',
            })

    # 순매수 상위 순서 보존 (과거엔 날조된 연속일수로 재정렬했음)
    results = results[:limit]

    if results:
        await redis_client.set(cache_key, results, get_cache_ttl(300))
    return results
