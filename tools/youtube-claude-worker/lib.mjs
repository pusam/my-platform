// 유튜브 의견 Claude 작업자 — 순수 규칙(테스트 대상). 부작용 없음.
//
// 원칙(서버 YoutubeOpinionWorkerService 와 짝):
//  - 자막(프롬프트)은 stdin 으로만 넘긴다. 명령 인자·셸 문자열에 넣지 않는다.
//  - 분석 호출엔 도구·MCP·슬래시 명령·세션 저장·사용자 설정을 전부 끈다. 출력은 구조화된 텍스트(JSON 배열)뿐.
//  - 구독 로그인 경로만 쓴다. API 키 환경변수는 자식 프로세스에 넘기지 않고, 실행 중 apiKeySource 가 'none' 이
//    아니면 그 자리에서 멈춘다(별도 과금 경로로 돌지 않는다). 다른 모델로 바뀌었으면 결과를 쓰지 않는다.

/** 자식 프로세스에 넘길 환경변수 — 허용 목록만. API 키·토큰·게이트웨이·클라우드 공급자 변수는 전부 빠진다. */
export const ENV_ALLOWLIST = [
  'SystemRoot', 'SYSTEMROOT', 'windir', 'WINDIR', 'PATH', 'Path', 'PATHEXT', 'ComSpec', 'COMSPEC',
  'USERPROFILE', 'HOMEDRIVE', 'HOMEPATH', 'HOME', 'APPDATA', 'LOCALAPPDATA', 'TEMP', 'TMP', 'TMPDIR',
  'USERNAME', 'USER', 'LOGNAME', 'LANG', 'LC_ALL', 'LC_CTYPE', 'XDG_CONFIG_HOME', 'XDG_DATA_HOME',
  'ProgramData', 'PROGRAMDATA', 'ProgramFiles', 'PROGRAMFILES', 'OS', 'NUMBER_OF_PROCESSORS', 'PROCESSOR_ARCHITECTURE'
];

/**
 * @param {Record<string,string|undefined>} source 부모 환경
 * @param {{claudeConfigDir?: string}} [opts] 작업자 전용 Claude 설정 폴더(따로 로그인한 경우)
 */
export function sanitizeEnv(source, opts = {}) {
  const env = {};
  for (const k of ENV_ALLOWLIST) {
    if (source[k] !== undefined) env[k] = source[k];
  }
  if (opts.claudeConfigDir) env.CLAUDE_CONFIG_DIR = opts.claudeConfigDir;
  return env;
}

/** 금지 플래그 — 자동 모델 전환(--fallback-model), API 키 전용 모드(--bare). */
export const FORBIDDEN_FLAGS = ['--fallback-model', '--bare', '--dangerously-skip-permissions', '--allowedTools', '--allowed-tools'];

/**
 * 분석 호출 인자. 프롬프트(자막)는 여기 없다 — stdin 으로만 간다.
 * @param {{model: string, systemPrompt: string}} p
 */
export function buildClaudeArgs({ model, systemPrompt }) {
  if (!model) throw new Error('model 이 없다');
  if (!systemPrompt) throw new Error('systemPrompt 가 없다');
  return [
    '-p',
    '--output-format', 'stream-json', '--verbose',   // init(인증 경로·모델) + result 를 한 번에 읽는다
    '--model', model,
    '--tools', '',                                    // 내장 도구 전부 끔(셸·파일·웹)
    '--strict-mcp-config', '--mcp-config', '{"mcpServers":{}}',   // 사용자 MCP 서버(브라우저 등) 미적재
    '--setting-sources', 'project',                   // 빈 작업 폴더에서 실행 — 사용자 설정(훅·apiKeyHelper) 미적재
    '--no-session-persistence',                       // 분석 세션을 사용자 기록에 남기지 않는다
    '--disable-slash-commands',
    '--system-prompt', systemPrompt                   // 기본(코딩 에이전트) 프롬프트를 추출기 역할로 대체
  ];
}

/** stream-json 출력 → init·result. 해석 못 한 줄은 센다(그 자체로 실패 사유가 된다). */
export function parseStreamJson(stdout) {
  let init = null, result = null, nonJson = 0;
  for (const line of String(stdout || '').split(/\r?\n/)) {
    if (!line.trim()) continue;
    let j;
    try { j = JSON.parse(line); } catch { nonJson++; continue; }
    if (j && j.type === 'system' && j.subtype === 'init') init = j;
    else if (j && j.type === 'result') result = j;
  }
  return { init, result, nonJson };
}

/**
 * 모델 일치 — 서버 YoutubeOpinionWorkerService.modelMatches 와 같은 규칙.
 * 별칭(sonnet 등)은 포함, 정식 ID(claude-…)는 같거나 날짜 접미사만 다름, 보고가 없으면 불일치.
 */
export function modelMatches(requested, actual) {
  if (!actual || !String(actual).trim()) return false;
  if (!requested || !String(requested).trim()) return true;
  const r = String(requested).trim().toLowerCase();
  const a = String(actual).trim().toLowerCase();
  if (r.startsWith('claude-')) return a === r || new RegExp(`^${r.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}-\\d{8}$`).test(a);
  return a.includes(r);
}

const RATE_LIMIT_RE = /usage limit|rate.?limit|limit reached|too many requests|\b429\b|quota/i;
const AUTH_RE = /authenticat|log ?in|oauth|not logged|unauthori[sz]ed|\b401\b|token.*expired|expired.*token|credential/i;

/**
 * 사용량 한도 메시지에서 다시 시도까지 남은 초. "…|1696166400"(epoch 초) 또는 "resets 3pm"/"resets at 15:00" 형식.
 * 모르면 null(서버 기본값 — 지어내지 않는다).
 */
export function parseRetryAfterSeconds(text, nowMs = Date.now()) {
  const s = String(text || '');
  const epoch = s.match(/\|(\d{10})\b/);
  if (epoch) {
    const sec = Math.round(Number(epoch[1]) - nowMs / 1000);
    return sec > 0 ? sec : 60;
  }
  const clock = s.match(/resets?\s*(?:at\s*)?(\d{1,2})(?::(\d{2}))?\s*(am|pm)?/i);
  if (clock) {
    let h = Number(clock[1]);
    const m = Number(clock[2] || 0);
    const ap = (clock[3] || '').toLowerCase();
    if (ap === 'pm' && h < 12) h += 12;
    if (ap === 'am' && h === 12) h = 0;
    if (h > 23 || m > 59) return null;
    const now = new Date(nowMs);
    const target = new Date(nowMs);
    target.setHours(h, m, 0, 0);
    if (target.getTime() <= now.getTime()) target.setDate(target.getDate() + 1);
    return Math.round((target.getTime() - now.getTime()) / 1000);
  }
  return null;
}

/** 응답 텍스트가 JSON 배열인가 — 코드 블록 울타리는 벗겨 본다. 서버가 다시 검증한다(작업자는 재시도 판단용). */
export function isJsonArrayText(text) {
  let t = String(text || '').trim();
  const fence = t.match(/^```(?:json)?\s*([\s\S]*?)\s*```$/i);
  if (fence) t = fence[1].trim();
  try { return Array.isArray(JSON.parse(t)); } catch { return false; }
}

/**
 * claude -p 한 번의 결과 판정.
 * @returns {{ok: true, text: string, model: string, durationMs: number|null} |
 *           {ok: false, type: string, message: string, retryAfterSeconds?: number|null}}
 */
export function classifyRun({ exitCode, timedOut, stdout, stderr, requestedModel, nowMs = Date.now() }) {
  if (timedOut) return { ok: false, type: 'TIMEOUT', message: '호출 시간 초과' };
  const { init, result } = parseStreamJson(stdout);
  const errText = `${result && typeof result.result === 'string' ? result.result : ''} ${stderr || ''}`.trim();
  // 인증 경로 확인 — 구독 로그인(OAuth)은 apiKeySource 'none'. 그 밖(환경변수·apiKeyHelper 키)은 별도 과금 경로다.
  if (init && init.apiKeySource && init.apiKeySource !== 'none') {
    return { ok: false, type: 'AUTH_PATH_NOT_SUBSCRIPTION', message: `apiKeySource=${init.apiKeySource}` };
  }
  if (!result) {
    if (AUTH_RE.test(errText)) return { ok: false, type: 'AUTH_REQUIRED', message: clip(errText) };
    return { ok: false, type: 'CLI_ERROR', message: clip(errText || `exit ${exitCode}, 결과 없음`) };
  }
  if (result.is_error || (result.subtype && result.subtype !== 'success')) {
    if (RATE_LIMIT_RE.test(errText)) {
      return { ok: false, type: 'RATE_LIMITED', message: clip(errText), retryAfterSeconds: parseRetryAfterSeconds(errText, nowMs) };
    }
    if (AUTH_RE.test(errText)) return { ok: false, type: 'AUTH_REQUIRED', message: clip(errText) };
    return { ok: false, type: 'CLI_ERROR', message: clip(errText || `subtype=${result.subtype}`) };
  }
  // 실제로 응답한 모델 — init 의 모델과 결과의 모델 사용량. 하나라도 요청과 다르면(자동 전환) 결과를 쓰지 않는다.
  const used = Object.keys(result.modelUsage || {});
  const models = [...new Set([...(init && init.model ? [init.model] : []), ...used])];
  if (models.length === 0) return { ok: false, type: 'MODEL_MISMATCH', message: '응답 모델을 확인하지 못함' };
  const off = models.find(m => !modelMatches(requestedModel, m));
  if (off) return { ok: false, type: 'MODEL_MISMATCH', message: `요청 ${requestedModel}, 실제 ${off}` };
  if (typeof result.result !== 'string') return { ok: false, type: 'INVALID_OUTPUT', message: '결과 텍스트 없음' };
  return { ok: true, text: result.result, model: init && init.model ? init.model : models[0],
    durationMs: typeof result.duration_ms === 'number' ? result.duration_ms : null };
}

/** claude auth status(JSON) 판정 — 로그인돼 있고, 1st party 이고, API 키 방식이 아니어야 한다. */
export function checkAuthStatus(json) {
  if (!json || typeof json !== 'object') return { ok: false, reason: 'claude auth status 결과를 읽지 못함' };
  if (json.loggedIn !== true) return { ok: false, reason: 'claude 로그인이 필요함(claude auth login)' };
  if (json.apiProvider && json.apiProvider !== 'firstParty') return { ok: false, reason: `apiProvider=${json.apiProvider}` };
  if (/api.?key|apikeyhelper|bedrock|vertex|foundry/i.test(String(json.authMethod || ''))) {
    return { ok: false, reason: `authMethod=${json.authMethod} — 구독 로그인이 아님(별도 과금 경로)` };
  }
  return { ok: true };
}

/** 재시도 정책 — 같은 구간에서 일시 오류·JSON 아님만 한 번 더. 한도·인증·모델·인증경로는 즉시 보고. */
export function shouldRetry(type) {
  return type === 'TIMEOUT' || type === 'CLI_ERROR' || type === 'INVALID_OUTPUT';
}

function clip(s, n = 200) {
  const t = String(s || '').replace(/\s+/g, ' ').trim();
  return t.length > n ? `${t.slice(0, n)}…` : t;
}
