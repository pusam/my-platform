from pydantic import BaseModel
from typing import Any, Optional


class ApiResponse(BaseModel):
    """Java ApiResponse 형식과 동일한 응답 래퍼"""
    success: bool = True
    message: str = "OK"
    data: Any = None


def ok(data: Any, message: str = "OK") -> dict:
    return {"success": True, "message": message, "data": data}


def error(message: str, data: Any = None) -> dict:
    return {"success": False, "message": message, "data": data}
