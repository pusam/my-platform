// 작업 한 건 처리 + claude 실행 래퍼. 처리 순서는 테스트가 가짜 실행기·가짜 API 로 고정한다(test/runner.test.mjs).
import { spawn, execFileSync } from 'node:child_process';
import { existsSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { buildClaudeArgs, classifyRun, isJsonArrayText, shouldRetry, checkAuthStatus } from './lib.mjs';

/** 임대를 잃음(만료 뒤 다른 작업자가 집음·이미 끝남) — 이 작업은 버리고 다음으로 간다. */
export class LeaseLostError extends Error {}

/**
 * 임대한 작업 한 건 — 구간마다 Claude 를 부르고(같은 구간 일시 오류·JSON 아님은 1번 더), 구간을 끝낼 때마다 임대를 연장한다.
 * 실패는 종류 그대로 서버에 보고한다 — 대기/재시도/실패를 정하는 건 서버다. 일부 구간만 성공해도 결과를 내지 않는다.
 *
 * @param {object} claim 서버 임대 응답(runId, leaseToken, model, systemPrompt, chunks[{index, prompt}])
 * @param {{runClaude: Function, api: {heartbeat: Function, complete: Function, fail: Function}, log?: Function,
 *          nowMs?: Function, shouldStop?: Function}} deps
 */
export async function processClaim(claim, deps) {
  const log = deps.log || (() => {});
  const now = deps.nowMs || (() => Date.now());
  const started = now();
  const outputs = [];
  let actualModel = null;
  const total = claim.chunks.length;
  for (const chunk of claim.chunks) {
    let attempt = 0;
    let verdict;
    for (;;) {
      if (deps.shouldStop && deps.shouldStop()) {
        await deps.api.fail(claim.runId, { leaseToken: claim.leaseToken, errorType: 'CANCELLED',
          message: '작업자 중단', retryAfterSeconds: null });
        return { status: 'reported-failure', type: 'CANCELLED' };
      }
      attempt++;
      const raw = await deps.runClaude(chunk.prompt, { model: claim.model, systemPrompt: claim.systemPrompt });
      verdict = classifyRun({ ...raw, requestedModel: claim.model, nowMs: now() });
      if (verdict.ok && !isJsonArrayText(verdict.text)) {
        verdict = { ok: false, type: 'INVALID_OUTPUT', message: `JSON 배열이 아님: ${verdict.text.slice(0, 80)}` };
      }
      if (verdict.ok) break;
      if (shouldRetry(verdict.type) && attempt < 2) {
        log(`run ${claim.runId} 구간 ${chunk.index + 1}/${total}: ${verdict.type} — 한 번 더 시도`);
        continue;
      }
      await deps.api.fail(claim.runId, {
        leaseToken: claim.leaseToken, errorType: verdict.type,
        message: `구간 ${chunk.index + 1}/${total}: ${verdict.message}`,
        retryAfterSeconds: verdict.retryAfterSeconds ?? null
      });
      return { status: 'reported-failure', type: verdict.type, retryAfterSeconds: verdict.retryAfterSeconds ?? null };
    }
    outputs.push({ index: chunk.index, text: verdict.text });
    actualModel = actualModel || verdict.model;
    log(`run ${claim.runId} 구간 ${chunk.index + 1}/${total} 완료(${verdict.durationMs ?? '?'}ms)`);
    await deps.api.heartbeat(claim.runId, claim.leaseToken);   // 409 면 LeaseLostError
  }
  const result = await deps.api.complete(claim.runId, {
    leaseToken: claim.leaseToken, model: actualModel, outputs, durationMs: now() - started
  });
  return { status: 'completed', result };
}

/**
 * claude 실행 — 셸 없이(shell:false), 프롬프트는 stdin 으로만. 시간이 넘으면 끊는다.
 * @returns {Promise<{exitCode: number|null, timedOut: boolean, stdout: string, stderr: string}>}
 */
export function spawnClaude({ bin, binArgs = [], model, systemPrompt, prompt, env, cwd, timeoutMs }) {
  return new Promise((resolve) => {
    const args = [...binArgs, ...buildClaudeArgs({ model, systemPrompt })];   // binArgs: 테스트용 가짜 실행기 스크립트 경로
    const child = spawn(bin, args, { env, cwd, shell: false, windowsHide: true, stdio: ['pipe', 'pipe', 'pipe'] });
    let stdout = '', stderr = '', timedOut = false, settled = false;
    const timer = setTimeout(() => { timedOut = true; child.kill(); }, timeoutMs);
    child.stdout.setEncoding('utf8');
    child.stderr.setEncoding('utf8');
    child.stdout.on('data', d => { stdout += d; });
    child.stderr.on('data', d => { stderr += d; });
    const done = (exitCode) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      resolve({ exitCode, timedOut, stdout, stderr });
    };
    child.on('error', (e) => { stderr += String(e && e.message); done(null); });
    child.on('close', (code) => done(code));
    child.stdin.on('error', () => {});
    child.stdin.end(Buffer.from(String(prompt), 'utf8'));
  });
}

/**
 * claude 실행 파일 — YT_WORKER_CLAUDE_BIN 이 있으면 그것, 없으면 PATH 의 claude 를 찾되 Windows npm 래퍼(.cmd)는
 * 셸 없이 못 띄우므로 같은 설치의 bin/claude.exe 를 쓴다.
 */
export function resolveClaudeBin(envBin, platform = process.platform) {
  if (envBin) {
    if (!existsSync(envBin)) throw new Error(`YT_WORKER_CLAUDE_BIN 이 없는 파일이다: ${envBin}`);
    return envBin;
  }
  const finder = platform === 'win32' ? 'where' : 'which';
  let found = [];
  try {
    found = execFileSync(finder, ['claude'], { encoding: 'utf8', windowsHide: true }).split(/\r?\n/).filter(Boolean);
  } catch {
    throw new Error('PATH 에서 claude 를 찾지 못했다 — YT_WORKER_CLAUDE_BIN 에 실행 파일 경로를 넣을 것');
  }
  for (const p of found) {
    if (/\.exe$/i.test(p)) return p;
    if (/\.cmd$/i.test(p)) {
      const exe = join(dirname(p), 'node_modules', '@anthropic-ai', 'claude-code', 'bin', 'claude.exe');
      if (existsSync(exe)) return exe;
    }
    if (platform !== 'win32') return p;
  }
  throw new Error('셸 없이 띄울 수 있는 claude 실행 파일을 찾지 못했다 — YT_WORKER_CLAUDE_BIN 을 지정할 것');
}

/** 시작 전 로그인 확인 — 모델을 부르지 않는다(claude auth status). */
export function preflightAuth({ bin, env, cwd }) {
  let out;
  try {
    out = execFileSync(bin, ['auth', 'status'], { env, cwd, encoding: 'utf8', windowsHide: true, timeout: 30000 });
  } catch (e) {
    out = (e && e.stdout) ? String(e.stdout) : '';
  }
  let json = null;
  try { json = JSON.parse(out); } catch { /* 아래에서 실패로 */ }
  return checkAuthStatus(json);
}
