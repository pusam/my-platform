// 테스트용 가짜 claude — stdin 으로 받은 프롬프트 길이와 받은 인자를 stream-json 으로 돌려준다.
// 프롬프트(자막)가 명령 인자에 섞이지 않았는지 확인하는 데 쓴다.
let input = '';
process.stdin.setEncoding('utf8');
process.stdin.on('data', d => { input += d; });
process.stdin.on('end', () => {
  const args = process.argv.slice(2);
  console.log(JSON.stringify({ type: 'system', subtype: 'init', apiKeySource: 'none', model: 'claude-sonnet-5' }));
  console.log(JSON.stringify({
    type: 'result', subtype: 'success', is_error: false, duration_ms: 5,
    modelUsage: { 'claude-sonnet-5': {} },
    result: JSON.stringify([{ promptLength: input.length, promptEcho: input.slice(0, 40), argCount: args.length,
      argsJoined: args.join(' ') }])
  }));
});
