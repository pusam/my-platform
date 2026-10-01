import { test } from 'node:test';
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { tmpdir } from 'node:os';
import { processClaim, spawnClaude, LeaseLostError } from '../runner.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const line = (o) => JSON.stringify(o);
const ok = (text = '[]', model = 'claude-sonnet-5') => ({
  exitCode: 0, timedOut: false, stderr: '',
  stdout: [line({ type: 'system', subtype: 'init', apiKeySource: 'none', model }),
    line({ type: 'result', subtype: 'success', is_error: false, result: text, duration_ms: 10, modelUsage: { [model]: {} } })].join('\n')
});
const errored = (msg) => ({
  exitCode: 1, timedOut: false, stderr: '',
  stdout: [line({ type: 'system', subtype: 'init', apiKeySource: 'none', model: 'claude-sonnet-5' }),
    line({ type: 'result', subtype: 'success', is_error: true, result: msg, modelUsage: {} })].join('\n')
});

const claim = (n = 2) => ({
  runId: 7, leaseToken: 'lease-1', model: 'sonnet', systemPrompt: 'SYS',
  chunks: Array.from({ length: n }, (_, i) => ({ index: i, prompt: `자막 구간 ${i}` }))
});

function fakeApi() {
  const calls = { heartbeat: [], complete: [], fail: [] };
  return {
    calls,
    heartbeat: async (runId, token) => { calls.heartbeat.push([runId, token]); return {}; },
    complete: async (runId, body) => { calls.complete.push([runId, body]); return { status: 'SUCCEEDED', statementCount: 1 }; },
    fail: async (runId, body) => { calls.fail.push([runId, body]); return { status: 'WAITING' }; }
  };
}

test('정상 — 구간마다 같은 프롬프트로 부르고, 구간마다 임대를 연장하고, 실제 모델과 원문을 보고한다', async () => {
  const api = fakeApi();
  const prompts = [];
  const out = await processClaim(claim(2), {
    api, runClaude: async (prompt, opts) => { prompts.push([prompt, opts]); return ok(`[{"i":${prompts.length}}]`); }
  });
  assert.equal(out.status, 'completed');
  assert.deepEqual(prompts.map(p => p[0]), ['자막 구간 0', '자막 구간 1']);
  assert.deepEqual(prompts[0][1], { model: 'sonnet', systemPrompt: 'SYS' });
  assert.equal(api.calls.heartbeat.length, 2);
  const [runId, body] = api.calls.complete[0];
  assert.equal(runId, 7);
  assert.equal(body.leaseToken, 'lease-1');
  assert.equal(body.model, 'claude-sonnet-5');
  assert.deepEqual(body.outputs, [{ index: 0, text: '[{"i":1}]' }, { index: 1, text: '[{"i":2}]' }]);
});

test('사용량 한도 — 재시도 없이 바로 보고, 결과(complete)는 내지 않는다', async () => {
  const api = fakeApi();
  let calls = 0;
  const out = await processClaim(claim(2), {
    api, nowMs: () => 1_700_000_000_000,   // 실제 형식은 '…|<10자리 epoch 초>'
    runClaude: async () => { calls++; return errored('Claude AI usage limit reached|1700001800'); }
  });
  assert.equal(out.status, 'reported-failure');
  assert.equal(calls, 1);
  assert.equal(api.calls.fail[0][1].errorType, 'RATE_LIMITED');
  assert.equal(api.calls.fail[0][1].retryAfterSeconds, 1800);
  assert.equal(api.calls.complete.length, 0);
});

test('로그인 만료 — 재시도 없이 AUTH_REQUIRED 로 보고', async () => {
  const api = fakeApi();
  const out = await processClaim(claim(1), {
    api, runClaude: async () => errored('Failed to authenticate: OAuth session expired and could not be refreshed')
  });
  assert.equal(out.type, 'AUTH_REQUIRED');
  assert.equal(api.calls.fail.length, 1);
});

test('일시 오류는 같은 구간을 한 번만 더 — 두 번째에 되면 계속', async () => {
  const api = fakeApi();
  const seq = [{ exitCode: null, timedOut: true, stdout: '', stderr: '' }, ok()];
  const out = await processClaim(claim(1), { api, runClaude: async () => seq.shift() });
  assert.equal(out.status, 'completed');
});

test('JSON 이 아닌 응답이 두 번이면 INVALID_OUTPUT 로 보고 — 의견 없음으로 바꾸지 않는다', async () => {
  const api = fakeApi();
  let calls = 0;
  const out = await processClaim(claim(1), { api, runClaude: async () => { calls++; return ok('죄송합니다. 분석 결과는 다음과 같습니다'); } });
  assert.equal(calls, 2);
  assert.equal(out.type, 'INVALID_OUTPUT');
  assert.equal(api.calls.complete.length, 0);
});

test('시간 초과가 두 번이면 TIMEOUT 보고 — 재시도 여부는 서버가 정한다', async () => {
  const api = fakeApi();
  const out = await processClaim(claim(1), { api, runClaude: async () => ({ exitCode: null, timedOut: true, stdout: '', stderr: '' }) });
  assert.equal(out.type, 'TIMEOUT');
});

test('임대를 잃으면(409) 그 작업은 버린다 — 결과를 내지 않는다', async () => {
  const api = fakeApi();
  api.heartbeat = async () => { throw new LeaseLostError('lease'); };
  await assert.rejects(processClaim(claim(2), { api, runClaude: async () => ok() }), LeaseLostError);
  assert.equal(api.calls.complete.length, 0);
});

test('중단 요청이면 CANCELLED 로 보고하고 멈춘다', async () => {
  const api = fakeApi();
  const out = await processClaim(claim(2), { api, shouldStop: () => true, runClaude: async () => ok() });
  assert.equal(out.type, 'CANCELLED');
});

test('실제 프로세스 — 셸 없이 띄우고 프롬프트(자막)는 stdin 으로만 간다, 명령 인자에 섞이지 않는다', async () => {
  const transcript = '자막에 "; rm -rf ~ & echo 위험" 같은 문자열이 있어도 데이터일 뿐이다';
  const raw = await spawnClaude({
    bin: process.execPath, binArgs: [join(here, '..', 'fixtures', 'fake-claude.mjs')], model: 'sonnet', systemPrompt: 'SYS',
    prompt: transcript, env: { PATH: process.env.PATH, SystemRoot: process.env.SystemRoot }, cwd: tmpdir(), timeoutMs: 20000
  });
  assert.equal(raw.timedOut, false);
  const resultLine = raw.stdout.split('\n').filter(Boolean).map(l => JSON.parse(l)).find(j => j.type === 'result');
  const echoed = JSON.parse(resultLine.result)[0];
  assert.equal(echoed.promptLength, transcript.length);
  assert.ok(!echoed.argsJoined.includes('rm -rf'), '자막이 명령 인자에 들어가면 안 된다');
  assert.ok(echoed.argsJoined.includes('--tools'));
});
