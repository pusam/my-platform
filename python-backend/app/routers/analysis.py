from fastapi import APIRouter

from app.models.schemas import ok, error
from app.services import stock_data_service, gemini_service

router = APIRouter(prefix="/api/v2", tags=["analysis"])


@router.get("/analysis/{ticker}")
async def get_stock_analysis(ticker: str):
    """개별 종목 실시간 분석: pykrx 데이터 + Gemini AI 분석"""
    # 1) pykrx에서 실시간 데이터
    stock_info = await stock_data_service.get_stock_info(ticker)
    if not stock_info:
        return error(f"종목 {ticker} 데이터를 가져올 수 없습니다")

    # 2) Gemini AI 분석
    ai_result = await gemini_service.analyze_stock(stock_info)

    # 3) 합산
    result = {**stock_info}
    if ai_result:
        result["aiScore"] = ai_result.get("aiScore", 0)
        result["aiComment"] = ai_result.get("aiComment", "")
        result["aiThemes"] = ",".join(ai_result.get("themes", []))
    else:
        result["aiScore"] = 0
        result["aiComment"] = "AI 분석 불가"
        result["aiThemes"] = ""

    return ok(result)
