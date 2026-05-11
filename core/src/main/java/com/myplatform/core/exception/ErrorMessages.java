package com.myplatform.core.exception;

/**
 * 도메인 에러 메시지 상수 단일 출처.
 *
 * 기존: `throw new RuntimeException("사용자를 찾을 수 없습니다.")` — 84개 서비스에 흩어져 있어서
 * 오타·문구 불일치·향후 i18n 마이그레이션 시 grep 부담.
 *
 * 신 패턴: `throw new RuntimeException(ErrorMessages.USER_NOT_FOUND);`
 *
 * GlobalExceptionHandler.statusFor / AdminController.httpStatusFor 가 메시지 패턴 매칭으로
 * HTTP status 추론하므로, 키워드("찾을 수 없", "이미", "유효하지" 등) 보존 필수.
 *
 * 점진적 적용 — 신규 코드부터 사용, 기존 코드는 손댈 때 함께 마이그레이션.
 */
public final class ErrorMessages {

    private ErrorMessages() {}

    // ========== 404 NOT_FOUND ==========
    public static final String USER_NOT_FOUND          = "사용자를 찾을 수 없습니다.";
    public static final String STOCK_NOT_FOUND         = "종목을 찾을 수 없습니다.";
    public static final String SECTOR_NOT_FOUND        = "해당 섹터를 찾을 수 없습니다.";
    public static final String ACCOUNT_NOT_FOUND       = "계좌를 찾을 수 없습니다.";
    public static final String POSITION_NOT_FOUND      = "포지션을 찾을 수 없습니다.";
    public static final String FILE_NOT_FOUND          = "파일을 찾을 수 없습니다.";
    public static final String FOLDER_NOT_FOUND        = "폴더를 찾을 수 없습니다.";
    public static final String BOARD_NOT_FOUND         = "게시글을 찾을 수 없습니다.";
    public static final String RECORD_NOT_FOUND        = "기록을 찾을 수 없습니다.";
    public static final String ASSET_NOT_FOUND         = "자산을 찾을 수 없습니다.";
    public static final String WATCHLIST_NOT_FOUND     = "관심종목을 찾을 수 없습니다.";

    // ========== 400 BAD_REQUEST ==========
    public static final String INVALID_INPUT           = "입력값이 올바르지 않습니다.";
    public static final String INVALID_TOKEN           = "유효하지 않거나 만료된 인증번호입니다.";
    public static final String INVALID_PASSWORD        = "비밀번호가 일치하지 않습니다.";
    public static final String INVALID_STATUS          = "유효하지 않은 상태값입니다.";
    public static final String INVALID_ROLE            = "올바른 권한을 입력해주세요. (USER 또는 ADMIN)";
    public static final String EMAIL_ALREADY_USED      = "이미 사용 중인 이메일입니다.";
    public static final String USERNAME_ALREADY_USED   = "이미 사용 중인 아이디입니다.";
    public static final String PASSWORD_TOO_SHORT      = "비밀번호는 4자 이상이어야 합니다.";

    // ========== 401/403 AUTH ==========
    public static final String UNAUTHORIZED            = "인증이 필요합니다.";
    public static final String FORBIDDEN               = "권한이 없습니다.";

    // ========== 비즈니스 도메인 ==========
    public static final String INSUFFICIENT_BALANCE    = "잔고가 부족합니다.";
    public static final String INSUFFICIENT_QUANTITY   = "보유 수량이 부족합니다.";
    public static final String KILL_SWITCH_ON          = "비상 정지(Kill Switch) 가 활성화되어 매매할 수 없습니다.";
    public static final String MARKET_CLOSED           = "장 시간이 아닙니다.";

    // ========== 외부 시스템 ==========
    public static final String KIS_API_UNAVAILABLE     = "한국투자증권 API 호출에 실패했습니다.";
    public static final String GEMINI_API_UNAVAILABLE  = "AI 분석 서비스에 일시적으로 연결할 수 없습니다.";
}
