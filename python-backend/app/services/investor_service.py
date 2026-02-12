"""투자자 매매 서비스 - stock_data_service 위의 얇은 래퍼"""
from app.services import stock_data_service


async def get_top_trades(investor_type: str = "FOREIGN", limit: int = 10) -> list:
    return await stock_data_service.get_investor_top_trades(investor_type, limit)


async def get_consecutive_buy(min_days: int = 3) -> list:
    return await stock_data_service.get_consecutive_buy(min_days)


async def get_surge_stocks() -> list:
    return await stock_data_service.get_surge_stocks()
