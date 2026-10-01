import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  buildClaudeArgs, sanitizeEnv, classifyRun, isJsonArrayText, modelMatches, checkAuthStatus,
  parseRetryAfterSeconds, FORBIDDEN_FLAGS, shouldRetry
} from '../lib.mjs';

const line = (o) => JSON.stringify(o);
const init = (extra = {}) => line({ type: 'system', subtype: 'init', apiKeySource: 'none', model: 'claude-sonnet-5',
  tools: [], mcp_servers: [], ...extra });
const result = (extra = {}) => line({ type: 'result', subtype: 'success', is_error: false, result: '[]', duration_ms: 900,
  modelUsage: { 'claude-sonnet-5': { inputTokens: 10 } }, ...extra });

test('분석 호출 인자 — 도구·MCP·슬래시·세션 저장·사용자 설정을 끄고, 자동 모델 전환·API 키 전용 모드는 없다', () => {
  const args = buildClaudeArgs({ model: 'sonnet', systemPrompt: 'SYS' });
  const at = (flag) => args[args.indexOf(flag) + 1];
  assert.equal(args[0], '-p');
  assert.equal(at('--tools'), '');
  assert.ok(args.includes('--strict-mcp-config'));
  assert.equal(at('--mcp-config'), '{"mcpServers":{}}');
  assert.equal(at('--setting-sources'), 'project');
  assert.ok(args.includes('--no-session-persistence'));
  assert.ok(args.includes('--disable-slash-commands'));
  assert.equal(at('--system-prompt'), 'SYS');
  assert.equal(at('--model'), 'sonnet');
  for (const f of FORBIDDEN_FLAGS) assert.ok(!args.includes(f), `${f} 가 들어가면 안 된다`);
});

test('자식 환경변수 — API 키·토큰·게이트웨이·클라우드 공급자·작업자 비밀번호는 넘기지 않는다', () => {
  const env = sanitizeEnv({
    PATH: 'C:/bin', USERPROFILE: 'C:/Users/u', APPDATA: 'C:/a',
    ANTHROPIC_API_KEY: 'sk-x', ANTHROPIC_AUTH_TOKEN: 't', ANTHROPIC_BASE_URL: 'http://proxy',
    CLAUDE_CODE_OAUTH_TOKEN: 'o', CLAUDE_CODE_USE_BEDROCK: '1', CLAUDE_CODE_USE_VERTEX: '1', CLAUDECODE: '1',
    CLAUDE_CODE_SESSION_ID: 's', AWS_ACCESS_KEY_ID: 'a', YT_WORKER_PASSWORD: 'pw', ANTHROPIC_PROFILE: 'p'
  });
  assert.deepEqual(Object.keys(env).sort(), ['APPDATA', 'PATH', 'USERPROFILE']);
  assert.equal(sanitizeEnv({ PATH: 'x' }, { claudeConfigDir: 'C:/w' }).CLAUDE_CONFIG_DIR, 'C:/w');
});

test('실측 재현(2026-10-01) — 로그인 만료 응답은 AUTH_REQUIRED, 의견 없음이 아니다', () => {
  // 이 PC 에서 실제로 받은 출력(subtype 은 success 인데 is_error 가 true 다)
  const stdout = [
    init(),
    line({ type: 'assistant' }),
    line({ type: 'result', subtype: 'success', is_error: true,
      result: 'Failed to authenticate: OAuth session expired and could not be refreshed',
      duration_ms: 173, total_cost_usd: 0, modelUsage: {}, num_turns: 1 })
  ].join('\n');
  const v = classifyRun({ exitCode: 1, timedOut: false, stdout, stderr: '', requestedModel: 'sonnet' });
  assert.equal(v.ok, false);
  assert.equal(v.type, 'AUTH_REQUIRED');
});

test('구독이 아닌 인증 경로(apiKeySource≠none)면 결과가 성공이어도 쓰지 않는다', () => {
  const stdout = [init({ apiKeySource: 'ANTHROPIC_API_KEY' }), result()].join('\n');
  const v = classifyRun({ exitCode: 0, timedOut: false, stdout, stderr: '', requestedModel: 'sonnet' });
  assert.equal(v.type, 'AUTH_PATH_NOT_SUBSCRIPTION');
});

test('사용량 한도 — epoch 형식은 남은 초를 계산한다', () => {
  const now = Date.UTC(2026, 9, 1, 3, 0, 0);
  const reset = Math.round(now / 1000) + 5400;
  const stdout = [init(), result({ is_error: true, result: `Claude AI usage limit reached|${reset}` })].join('\n');
  const v = classifyRun({ exitCode: 1, timedOut: false, stdout, stderr: '', requestedModel: 'sonnet', nowMs: now });
  assert.equal(v.type, 'RATE_LIMITED');
  assert.equal(v.retryAfterSeconds, 5400);
});

test('사용량 한도 — "resets 3pm" 형식도 읽고, 모르면 null(지어내지 않는다)', () => {
  const now = new Date(2026, 9, 1, 13, 30, 0).getTime();
  assert.equal(parseRetryAfterSeconds('5-hour limit reached ∙ resets 3pm', now), 90 * 60);
  assert.equal(parseRetryAfterSeconds('limit reached', now), null);
});

test('다른 모델이 응답했으면 MODEL_MISMATCH — 자동 전환을 받아들이지 않는다', () => {
  const stdout = [init(), result({ modelUsage: { 'claude-haiku-4-5': { inputTokens: 5 } } })].join('\n');
  const v = classifyRun({ exitCode: 0, timedOut: false, stdout, stderr: '', requestedModel: 'sonnet' });
  assert.equal(v.type, 'MODEL_MISMATCH');
});

test('정상 — 응답 텍스트와 실제 모델을 돌려준다', () => {
  const stdout = [init(), result({ result: '[{"stockName":"삼성전자"}]' })].join('\n');
  const v = classifyRun({ exitCode: 0, timedOut: false, stdout, stderr: '', requestedModel: 'sonnet' });
  assert.equal(v.ok, true);
  assert.equal(v.model, 'claude-sonnet-5');
  assert.equal(v.text, '[{"stockName":"삼성전자"}]');
});

test('시간 초과·결과 없음은 일시 오류로 — 재시도 대상', () => {
  assert.equal(classifyRun({ exitCode: null, timedOut: true, stdout: '', stderr: '' }).type, 'TIMEOUT');
  assert.equal(classifyRun({ exitCode: 1, timedOut: false, stdout: 'garbage', stderr: 'boom' }).type, 'CLI_ERROR');
  assert.ok(shouldRetry('TIMEOUT') && shouldRetry('CLI_ERROR') && shouldRetry('INVALID_OUTPUT'));
  assert.ok(!shouldRetry('RATE_LIMITED') && !shouldRetry('AUTH_REQUIRED') && !shouldRetry('MODEL_MISMATCH'));
});

test('JSON 배열 판정 — 코드 울타리는 벗기고, 설명문·객체는 아니다', () => {
  assert.ok(isJsonArrayText('[]'));
  assert.ok(isJsonArrayText('```json\n[{"a":1}]\n```'));
  assert.ok(!isJsonArrayText('분석 결과입니다: []'));
  assert.ok(!isJsonArrayText('{"a":1}'));
});

test('모델 일치 규칙은 서버와 같다', () => {
  assert.ok(modelMatches('sonnet', 'claude-sonnet-5'));
  assert.ok(!modelMatches('sonnet', 'claude-haiku-4-5'));
  assert.ok(modelMatches('claude-sonnet-5', 'claude-sonnet-5-20261001'));
  assert.ok(!modelMatches('claude-sonnet-5', 'claude-sonnet-5-5'));
  assert.ok(!modelMatches('sonnet', ''));
});

test('로그인 확인 — 실측 로그아웃 상태는 거절, 구독 로그인만 통과, API 키·타사 공급자는 거절', () => {
  // 이 PC 의 실제 claude auth status 출력(2026-10-01)
  assert.equal(checkAuthStatus({ loggedIn: false, authMethod: 'none', apiProvider: 'firstParty' }).ok, false);
  assert.equal(checkAuthStatus({ loggedIn: true, authMethod: 'claude.ai', apiProvider: 'firstParty' }).ok, true);
  assert.equal(checkAuthStatus({ loggedIn: true, authMethod: 'api_key', apiProvider: 'firstParty' }).ok, false);
  assert.equal(checkAuthStatus({ loggedIn: true, authMethod: 'oauth', apiProvider: 'bedrock' }).ok, false);
  assert.equal(checkAuthStatus(null).ok, false);
});
