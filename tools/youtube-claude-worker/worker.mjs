#!/usr/bin/env node
// 유튜브 의견 Claude 작업자 — 구독 로그인이 된 로컬 PC 에서 돈다. 서버 관리자 API 로 작업을 임대해 같은 프롬프트로 Claude 를
// 돌리고 응답 원문만 돌려준다(해석·검증·저장은 서버). 사용법·주의는 README.md.
//
//   node worker.mjs          대기열을 계속 본다(Ctrl+C 로 멈춤)
//   node worker.mjs --once   작업 한 건만 처리하고 끝낸다(없으면 바로 끝)
import { mkdirSync, openSync, closeSync, readFileSync, writeFileSync, unlinkSync } from 'node:fs';
import { tmpdir, hostname } from 'node:os';
import { join } from 'node:path';
import { sanitizeEnv } from './lib.mjs';
import { processClaim, spawnClaude, resolveClaudeBin, preflightAuth, LeaseLostError } from './runner.mjs';

const ONCE = process.argv.includes('--once');
const cfg = {
  apiBase: (process.env.YT_WORKER_API_BASE || '').replace(/\/+$/, ''),
  username: process.env.YT_WORKER_USERNAME || '',
  password: process.env.YT_WORKER_PASSWORD || '',
  workerId: process.env.YT_WORKER_ID || hostname(),
  claudeBin: process.env.YT_WORKER_CLAUDE_BIN || '',
  claudeConfigDir: process.env.YT_WORKER_CLAUDE_CONFIG_DIR || '',
  callTimeoutMs: Math.max(30, Number(process.env.YT_WORKER_CALL_TIMEOUT_SEC || 180)) * 1000,
  pollMs: Math.max(15, Number(process.env.YT_WORKER_POLL_SEC || 60)) * 1000
};

const log = (msg) => console.log(`[${new Date().toISOString()}] ${msg}`);
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

function fatal(msg, code = 1) {
  console.error(`작업자 중지: ${msg}`);
  releaseLock();
  process.exit(code);
}

// ---- 같은 PC 에 작업자 둘 금지(서버 임대가 1차 방어, 이건 2차) ----
const LOCK = join(tmpdir(), 'yt-claude-worker.lock');
let lockHeld = false;
function acquireLock() {
  try {
    const fd = openSync(LOCK, 'wx');
    writeFileSync(fd, String(process.pid));
    closeSync(fd);
    lockHeld = true;
    return;
  } catch {
    let pid = 0;
    try { pid = Number(readFileSync(LOCK, 'utf8')); } catch { /* 읽지 못하면 아래에서 새로 잡는다 */ }
    let alive = false;
    try { if (pid) { process.kill(pid, 0); alive = true; } } catch { alive = false; }
    if (alive) fatal(`이미 작업자가 돌고 있다(pid ${pid}) — ${LOCK}`);
    unlinkSync(LOCK);
    return acquireLock();
  }
}
function releaseLock() {
  if (!lockHeld) return;
  try { unlinkSync(LOCK); } catch { /* 이미 없음 */ }
  lockHeld = false;
}

// ---- 서버 API(관리자 로그인 재사용 — 토큰은 출력하지 않는다) ----
let accessToken = null, refreshToken = null;
async function login() {
  const r = await fetch(`${cfg.apiBase}/api/auth/login`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: cfg.username, password: cfg.password })
  });
  const j = await r.json().catch(() => ({}));
  if (!r.ok || !j.success || !j.token) fatal(`서버 로그인 실패(HTTP ${r.status}) — 관리자 계정·주소 확인`);
  accessToken = j.token;
  refreshToken = j.refreshToken || null;
}
async function refresh() {
  if (!refreshToken) return login();
  const r = await fetch(`${cfg.apiBase}/api/auth/refresh`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ refreshToken })
  });
  const j = await r.json().catch(() => ({}));
  if (!r.ok || !j.success || !j.token) return login();
  accessToken = j.token;
  if (j.refreshToken) refreshToken = j.refreshToken;
}
async function call(path, body, retried = false) {
  const r = await fetch(`${cfg.apiBase}/api/admin/youtube-opinions/worker${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${accessToken}` },
    body: JSON.stringify(body || {})
  });
  if (r.status === 401 && !retried) { await refresh(); return call(path, body, true); }
  const j = await r.json().catch(() => ({}));
  if (r.status === 409) throw new LeaseLostError(j.message || '임대가 유효하지 않음');
  if (!r.ok || j.success === false) throw new Error(`서버 ${path} 실패(HTTP ${r.status}): ${j.message || ''}`);
  return j.data;
}
const api = {
  claim: () => call('/claim', { workerId: cfg.workerId }),
  heartbeat: (runId, leaseToken) => call(`/runs/${runId}/heartbeat`, { leaseToken }),
  complete: (runId, body) => call(`/runs/${runId}/complete`, body),
  fail: (runId, body) => call(`/runs/${runId}/fail`, body)
};

// ---- 실행 ----
let stopping = false;
let current = null;
process.on('SIGINT', () => { stopping = true; log('중단 요청 — 현재 구간이 끝나면 서버에 중단을 보고하고 멈춘다'); });
process.on('SIGTERM', () => { stopping = true; });

async function main() {
  if (!cfg.apiBase || !cfg.username || !cfg.password) {
    fatal('YT_WORKER_API_BASE · YT_WORKER_USERNAME · YT_WORKER_PASSWORD 환경변수가 필요하다(README.md)');
  }
  acquireLock();
  const bin = resolveClaudeBin(cfg.claudeBin);
  const workdir = join(tmpdir(), 'yt-claude-worker-cwd');   // 빈 작업 폴더 — 프로젝트 설정·CLAUDE.md 를 읽지 않게
  mkdirSync(workdir, { recursive: true });
  const env = sanitizeEnv(process.env, { claudeConfigDir: cfg.claudeConfigDir || undefined });

  const auth = preflightAuth({ bin, env, cwd: workdir });
  if (!auth.ok) fatal(`Claude 인증 확인 실패 — ${auth.reason}`, 2);
  await login();
  log(`작업자 시작 — ${cfg.workerId}, claude ${bin}, 호출 제한 ${cfg.callTimeoutMs / 1000}초`);

  while (!stopping) {
    let claim;
    try {
      claim = await api.claim();
    } catch (e) {
      log(`임대 요청 실패: ${e.message}`);
      if (ONCE) break;
      await sleep(cfg.pollMs);
      continue;
    }
    if (!claim) {
      if (ONCE) { log('대기열이 비었다'); break; }
      await sleep(cfg.pollMs);
      continue;
    }
    current = claim;
    log(`run ${claim.runId}(영상 ${claim.videoId}) 임대 — 구간 ${claim.chunks.length}개, 모델 ${claim.model}, 시도 ${claim.attempt}/${claim.maxAttempts}`);
    let outcome;
    try {
      outcome = await processClaim(claim, {
        api, log, shouldStop: () => stopping,
        runClaude: (prompt, { model, systemPrompt }) =>
          spawnClaude({ bin, model, systemPrompt, prompt, env, cwd: workdir, timeoutMs: cfg.callTimeoutMs })
      });
    } catch (e) {
      if (e instanceof LeaseLostError) { log(`run ${claim.runId} 임대를 잃었다 — 결과를 버린다: ${e.message}`); current = null; continue; }
      log(`run ${claim.runId} 처리 오류: ${e.message}`);
      try {
        await api.fail(claim.runId, { leaseToken: claim.leaseToken, errorType: 'CLI_ERROR', message: e.message, retryAfterSeconds: null });
      } catch { /* 임대 만료가 대기열로 돌린다 */ }
      outcome = { status: 'reported-failure', type: 'CLI_ERROR' };
    }
    current = null;
    if (outcome.status === 'completed') {
      log(`run ${claim.runId} → ${outcome.result && outcome.result.status} · 발언 ${outcome.result && outcome.result.statementCount}건`);
    } else {
      log(`run ${claim.runId} 보고: ${outcome.type}`);
      if (outcome.type === 'AUTH_REQUIRED') fatal('Claude 로그인이 필요하다 — claude auth login 뒤 다시 실행', 2);
      if (outcome.type === 'AUTH_PATH_NOT_SUBSCRIPTION') fatal('구독 로그인이 아닌 인증 경로가 잡혔다 — API 키 설정을 지우고 다시 실행', 2);
      if (outcome.type === 'RATE_LIMITED') {
        const waitMs = Math.max(cfg.pollMs, (outcome.retryAfterSeconds || 3600) * 1000);
        if (ONCE) break;
        log(`사용량 한도 — ${Math.round(waitMs / 60000)}분 뒤 다시 본다`);
        await sleep(waitMs);
      }
    }
    if (ONCE) break;
  }
  releaseLock();
}

main().catch((e) => fatal(e && e.message ? e.message : String(e)));
