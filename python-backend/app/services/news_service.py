"""RSS 뉴스 수집 서비스"""
import asyncio
import logging
from datetime import datetime

import feedparser
from bs4 import BeautifulSoup

from app.services.cache_service import redis_client
from app.utils.korean_market import get_cache_ttl, now_kst

logger = logging.getLogger(__name__)

# 한국 경제 뉴스 RSS 피드
RSS_FEEDS = [
    ("한국경제", "https://www.hankyung.com/feed/stock"),
    ("매일경제", "https://www.mk.co.kr/rss/30000001/"),
    ("연합뉴스", "https://www.yna.co.kr/rss/economy.xml"),
]

# 긍정/부정 키워드
POSITIVE_KEYWORDS = ["상승", "급등", "호조", "성장", "확대", "반등", "최고", "수혜", "호재", "강세"]
NEGATIVE_KEYWORDS = ["하락", "급락", "우려", "둔화", "축소", "부진", "최저", "악재", "리스크", "약세"]


async def get_today_news(limit: int = 5) -> list:
    """오늘의 뉴스 (RSS 수집 + 감성 분석)"""
    cache_key = "news_today"
    cached = await redis_client.get(cache_key)
    if cached:
        return cached

    def _fetch():
        articles = []
        for source_name, feed_url in RSS_FEEDS:
            try:
                feed = feedparser.parse(feed_url)
                for entry in feed.entries[:5]:
                    title = entry.get("title", "")
                    summary = entry.get("summary", entry.get("description", ""))
                    # HTML 태그 제거
                    if summary:
                        summary = BeautifulSoup(summary, "html.parser").get_text()[:200]
                    published = entry.get("published", now_kst().isoformat())

                    sentiment = _analyze_sentiment(title + " " + summary)
                    articles.append({
                        "title": title,
                        "summary": summary,
                        "source": source_name,
                        "publishedAt": published,
                        "sentiment": sentiment,
                    })
            except Exception as e:
                logger.warning(f"RSS feed error [{source_name}]: {e}")
                continue

        # 최신순 정렬 후 limit 적용
        return articles[:limit]

    data = await asyncio.to_thread(_fetch)
    if data:
        await redis_client.set(cache_key, data, get_cache_ttl(1800))
    return data or []


def _analyze_sentiment(text: str) -> str:
    """간단한 키워드 기반 감성 분석"""
    pos_count = sum(1 for kw in POSITIVE_KEYWORDS if kw in text)
    neg_count = sum(1 for kw in NEGATIVE_KEYWORDS if kw in text)
    if pos_count > neg_count:
        return "긍정"
    elif neg_count > pos_count:
        return "부정"
    return "중립"
