"""Gemini AI 분석 서비스

Java GeminiService와 동일한 패턴:
- gemini-2.0-flash 모델
- response_mime_type="application/json"
- 레이트 리미팅: 15 RPM, 2초 최소 간격
- 연속 실패 3회 시 쿨다운
"""
import asyncio
import json
import logging
import time
from typing import Optional

import google.generativeai as genai

from app.config import get_settings
from app.services.cache_service import redis_client

logger = logging.getLogger(__name__)

# Rate limiting state
_last_request_time = 0.0
_consecutive_errors = 0
_cooldown_until = 0.0


def _configure():
    settings = get_settings()
    if settings.gemini_api_key:
        genai.configure(api_key=settings.gemini_api_key)
        return True
    return False


async def _call_gemini(prompt: str, temperature: float = 0.3) -> Optional[str]:
    """Gemini API 호출 (레이트 리미팅 + 재시도)"""
    global _last_request_time, _consecutive_errors, _cooldown_until

    if not _configure():
        logger.warning("Gemini API key not configured")
        return None

    settings = get_settings()
    now = time.time()

    # 쿨다운 체크
    if now < _cooldown_until:
        logger.warning(f"Gemini in cooldown for {_cooldown_until - now:.0f}s more")
        return None

    # 최소 간격 보장
    elapsed = now - _last_request_time
    if elapsed < settings.gemini_min_interval:
        await asyncio.sleep(settings.gemini_min_interval - elapsed)

    for attempt in range(settings.gemini_max_retries):
        try:
            _last_request_time = time.time()

            model = genai.GenerativeModel(settings.gemini_model)
            response = await asyncio.to_thread(
                model.generate_content,
                prompt,
                generation_config=genai.GenerationConfig(
                    temperature=temperature,
                    max_output_tokens=2048,
                    response_mime_type="application/json",
                ),
            )

            if response and response.text:
                _consecutive_errors = 0
                return response.text

        except Exception as e:
            _consecutive_errors += 1
            err_msg = str(e)
            logger.warning(f"[AI] Gemini attempt {attempt + 1} failed: {err_msg}")

            if _consecutive_errors >= 3:
                _cooldown_until = time.time() + 60
                logger.error("[AI] 3 consecutive errors - cooling down 60s")
                return None

            # 429 에러 → 대기
            if "429" in err_msg or "quota" in err_msg.lower():
                wait = min(30, (attempt + 1) * 5)
                logger.warning(f"[AI] Rate limited, waiting {wait}s")
                await asyncio.sleep(wait)
            else:
                await asyncio.sleep(2)

    return None


async def analyze_stock(stock_data: dict) -> Optional[dict]:
    """개별 종목 AI 분석 → { aiScore, aiComment, themes }"""
    cache_key = f"ai_analysis_{stock_data.get('stockCode', '')}"
    cached = await redis_client.get(cache_key)
    if cached:
        return cached

    prompt = f"""당신은 한국 주식시장 전문 애널리스트입니다.
아래 종목 데이터를 분석하고 JSON으로 응답하세요.

종목: {stock_data.get('stockName', '')} ({stock_data.get('stockCode', '')})
현재가: {stock_data.get('currentPrice', 0):,}원
등락률: {stock_data.get('changeRate', 0)}%
거래량: {stock_data.get('volume', 0):,}
PER: {stock_data.get('per', 0)}
PBR: {stock_data.get('pbr', 0)}

다음 JSON 형식으로 응답:
{{
  "aiScore": (0~100 정수, 투자매력도),
  "aiComment": (40자 이내 한국어 한줄평),
  "themes": ["테마1", "테마2"]
}}"""

    text = await _call_gemini(prompt)
    if not text:
        return None

    try:
        result = _parse_json(text)
        # aiScore 누락 응답은 실패 취급 — 기본값 50(중립)으로 위장하면 호출측이
        # "AI 가 중립 평가" 와 "점수 없음" 을 구분 못 함 (점검 수정 2026-06-11)
        if "aiScore" not in result:
            logger.warning("[AI] aiScore 누락 응답 — 분석 실패 취급")
            return None
        result["aiScore"] = max(0, min(100, int(result["aiScore"])))
        result["aiComment"] = str(result.get("aiComment", ""))[:40]
        themes = result.get("themes", [])
        if isinstance(themes, list):
            result["themes"] = themes[:3]

        await redis_client.set(cache_key, result, 300)
        return result
    except Exception as e:
        logger.error(f"[AI] Parse error: {e}")
        return None


async def score_candidates(candidates: list, strategy_type: str) -> dict:
    """배치 스코어링 → { stockCode: { aiScore, aiComment, themes } }

    Java GeminiService.scoreStockCandidates()와 동일한 응답 형식
    """
    if not candidates:
        return {}

    stocks_desc = []
    for c in candidates[:10]:
        desc = f"- {c.get('stockName','')}({c.get('stockCode','')}): "
        desc += f"가격 {c.get('currentPrice',0):,}원, 등락률 {c.get('changeRate',0)}%, "
        desc += f"알고리즘점수 {c.get('score',0)}"
        if c.get("per"):
            desc += f", PER {c['per']}"
        if c.get("roe"):
            desc += f", ROE {c['roe']}"
        if c.get("volumeRatio"):
            desc += f", 거래량비 {c['volumeRatio']}%"
        stocks_desc.append(desc)

    strategy_desc = {
        "SCALPING": "단타/스캘핑 (거래량 급증 + 단기 모멘텀)",
        "SWING": "스윙 (가치+성장 복합, ROE/PER 중시)",
        "TURNAROUND": "턴어라운드 (흑자전환/이익급증)",
        "VALUE": "가치투자 (저PEG, 저평가 성장주)",
    }

    prompt = f"""당신은 한국 주식시장 전문 애널리스트입니다.
전략: {strategy_desc.get(strategy_type, strategy_type)}

아래 후보 종목들을 분석하고 JSON 배열로 응답하세요.

{chr(10).join(stocks_desc)}

각 종목에 대해:
[
  {{
    "stockCode": "종목코드",
    "aiScore": (0~100 정수),
    "aiComment": (40자 이내 한국어),
    "themes": ["테마1", "테마2"]
  }}
]"""

    text = await _call_gemini(prompt)
    if not text:
        return {}

    try:
        arr = _parse_json(text)
        if not isinstance(arr, list):
            arr = [arr]

        result = {}
        for item in arr:
            code = str(item.get("stockCode", ""))
            # aiScore 없는 항목은 제외 — 50점(중립) 위장 금지 (점검 수정 2026-06-11)
            if not code or "aiScore" not in item:
                continue
            result[code] = {
                "aiScore": max(0, min(100, int(item["aiScore"]))),
                "aiComment": str(item.get("aiComment", ""))[:40],
                "themes": item.get("themes", [])[:3],
            }
        return result
    except Exception as e:
        logger.error(f"[AI Scoring] Parse error: {e}")
        return {}


def _parse_json(text: str):
    """JSON 파싱 (마크다운 코드 블록 핸들링)"""
    text = text.strip()
    if text.startswith("```"):
        lines = text.split("\n")
        text = "\n".join(lines[1:-1] if lines[-1].strip() == "```" else lines[1:])
        text = text.strip()
    return json.loads(text)
