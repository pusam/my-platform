// GitHub MCP 인증 헤더 — 이 PC 의 git 로그인(Git Credential Manager 등)에 저장된 GitHub 토큰을 재사용한다.
//
// .mcp.json 의 headersHelper 가 접속할 때마다 실행한다. 토큰은 표준출력(JSON 헤더 한 줄)으로만 내보내고 파일·로그에
// 남기지 않는다. 그래서 PC 마다 토큰을 발급·복사할 필요가 없다 — GitHub 에 푸시되는 PC 면 그대로 붙는다.
// 로그인이 없으면 창을 띄우지 않고 실패(exit 1)한다 — 그 PC 에서는 GitHub MCP 만 연결되지 않는다.
//
// ⚠ git 로그인 토큰은 푸시 권한이 있는 토큰이다. .mcp.json 은 GitHub MCP 를 읽기 전용 주소(/x/all/readonly)로만
// 연결한다 — 이 스크립트를 다른 주소의 서버에 붙이지 말 것.
import { execFileSync } from 'node:child_process';

try {
  const out = execFileSync('git', ['credential', 'fill'], {
    input: 'protocol=https\nhost=github.com\n\n',
    env: { ...process.env, GIT_TERMINAL_PROMPT: '0', GCM_INTERACTIVE: 'never' },
    stdio: ['pipe', 'pipe', 'ignore'],
    timeout: 15000,
  }).toString();
  const token = /^password=(.+)$/m.exec(out)?.[1]?.trim();
  if (!token) process.exit(1);
  process.stdout.write(JSON.stringify({ Authorization: `Bearer ${token}` }));
} catch {
  process.exit(1);
}
