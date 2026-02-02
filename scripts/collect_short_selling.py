#!/usr/bin/env python3
"""
공매도 데이터 수집 스크립트 (pykrx 사용)
- KRX 공개 데이터를 수집하여 서버 API로 전송
- 사용법: python collect_short_selling.py
"""

import requests
import json
from datetime import datetime, timedelta
from pykrx import stock

# 서버 API 설정
API_BASE_URL = "http://localhost:8080"  # 또는 http://dhkim.iptime.org
API_ENDPOINT = "/api/short-selling/import"

# 수집 대상 종목 (주요 대형주)
TARGET_STOCKS = [
    ("005930", "삼성전자"),
    ("000660", "SK하이닉스"),
    ("035420", "NAVER"),
    ("035720", "카카오"),
    ("005380", "현대차"),
    ("006400", "삼성SDI"),
    ("051910", "LG화학"),
    ("068270", "셀트리온"),
    ("105560", "KB금융"),
    ("055550", "신한지주"),
    ("247540", "에코프로비엠"),
    ("003490", "대한항공"),
    ("010130", "고려아연"),
    ("000270", "기아"),
    ("086790", "하나금융지주"),
]

def get_date_range(days=30):
    """최근 N일 날짜 범위 계산"""
    end_date = datetime.now()
    start_date = end_date - timedelta(days=days)
    return start_date.strftime("%Y%m%d"), end_date.strftime("%Y%m%d")

def collect_short_selling_data(stock_code, stock_name, start_date, end_date):
    """특정 종목의 공매도 데이터 수집"""
    data_list = []

    try:
        # 공매도 거래량 조회
        df = stock.get_shorting_volume_by_date(start_date, end_date, stock_code)

        if df.empty:
            print(f"  [{stock_code}] {stock_name}: 데이터 없음")
            return data_list

        for date_idx, row in df.iterrows():
            trade_date = date_idx.strftime("%Y-%m-%d")

            data = {
                "stockCode": stock_code,
                "stockName": stock_name,
                "tradeDate": trade_date,
                "shortVolume": int(row.get("공매도량", 0) or 0),
                "shortTradingValue": int(row.get("공매도거래대금", 0) or 0),
                "shortRatio": float(row.get("공매도비중", 0) or 0),
                "totalVolume": int(row.get("총거래량", 0) or 0),
            }
            data_list.append(data)

        print(f"  [{stock_code}] {stock_name}: {len(data_list)}건 수집")

    except Exception as e:
        print(f"  [{stock_code}] {stock_name}: 오류 - {e}")

    return data_list

def collect_loan_balance_data(stock_code, stock_name, start_date, end_date):
    """특정 종목의 대차잔고 데이터 수집"""
    data_list = []

    try:
        # 대차잔고 조회
        df = stock.get_shorting_balance_by_date(start_date, end_date, stock_code)

        if df.empty:
            return data_list

        for date_idx, row in df.iterrows():
            trade_date = date_idx.strftime("%Y-%m-%d")

            data = {
                "stockCode": stock_code,
                "tradeDate": trade_date,
                "loanBalanceQuantity": int(row.get("잔고수량", 0) or 0),
                "loanBalanceValue": int(row.get("잔고금액", 0) or 0),
                "loanBalanceRatio": float(row.get("잔고비중", 0) or 0),
            }
            data_list.append(data)

    except Exception as e:
        print(f"  [{stock_code}] 대차잔고 오류: {e}")

    return data_list

def send_to_server(data_list):
    """수집된 데이터를 서버 API로 전송"""
    if not data_list:
        print("전송할 데이터가 없습니다.")
        return False

    try:
        response = requests.post(
            f"{API_BASE_URL}{API_ENDPOINT}",
            json=data_list,
            headers={"Content-Type": "application/json"},
            timeout=30
        )

        if response.status_code == 200:
            result = response.json()
            print(f"서버 전송 성공: {result}")
            return True
        else:
            print(f"서버 전송 실패: {response.status_code} - {response.text}")
            return False

    except Exception as e:
        print(f"서버 연결 오류: {e}")
        return False

def save_to_file(data_list, filename="short_selling_data.json"):
    """데이터를 JSON 파일로 저장"""
    with open(filename, "w", encoding="utf-8") as f:
        json.dump(data_list, f, ensure_ascii=False, indent=2)
    print(f"파일 저장 완료: {filename} ({len(data_list)}건)")

def main():
    print("=" * 50)
    print("공매도 데이터 수집 시작 (pykrx)")
    print("=" * 50)

    start_date, end_date = get_date_range(30)
    print(f"수집 기간: {start_date} ~ {end_date}")
    print(f"대상 종목: {len(TARGET_STOCKS)}개")
    print()

    all_data = []

    # 공매도 데이터 수집
    print("[1/2] 공매도 거래량 수집 중...")
    for stock_code, stock_name in TARGET_STOCKS:
        data = collect_short_selling_data(stock_code, stock_name, start_date, end_date)
        all_data.extend(data)

    print()

    # 대차잔고 데이터 수집 및 병합
    print("[2/2] 대차잔고 데이터 수집 중...")
    for stock_code, stock_name in TARGET_STOCKS:
        loan_data = collect_loan_balance_data(stock_code, stock_name, start_date, end_date)

        # 기존 데이터와 병합 (같은 날짜 기준)
        for loan in loan_data:
            for item in all_data:
                if item["stockCode"] == loan["stockCode"] and item["tradeDate"] == loan["tradeDate"]:
                    item.update(loan)
                    break

    print()
    print(f"총 수집 건수: {len(all_data)}건")

    # 파일로 저장
    save_to_file(all_data)

    # 서버 전송 시도 (선택)
    # send_to_server(all_data)

    print()
    print("=" * 50)
    print("수집 완료!")
    print("=" * 50)

if __name__ == "__main__":
    main()
