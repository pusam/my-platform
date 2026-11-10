# 서버 감시·백업 보강 — 설치 안내 (2026-11-10, 개선 후보 5번)

세 가지를 더한다. 전부 **알리기·확인하기만** 하고 운영 상태를 바꾸지 않는다.

| 무엇 | 파일 | 언제 |
|---|---|---|
| 백엔드 5분 감시 — 상태가 바뀐 순간에만 텔레그램 | `scripts/watch-backend.sh` | 5분마다(크론) |
| DB 복구 리허설 — 최신 덤프를 임시 컨테이너에 복원해 행 수 확인 | `scripts/restore-drill.sh` | 달 1회(크론, 선택) 또는 손으로 |
| 백업 외부 사본 — 세 방법과 추천 | 이 문서 §3 | 사용자가 고른다 |

두 스크립트가 같이 쓰는 설정 읽기·텔레그램 발송은 `scripts/ops-notify-lib.sh` 에 있다(혼자 실행하면 아무것도 안 한다).

> ⚠ **배포는 스크립트를 서버에 복사만 한다.** `scripts/*.sh`(한 단계 아래 파일만)는 배포 때마다 서버의 `scripts/` 로 복사되지만, **복사됐다고 돌지는 않는다** — 아래 §1 의 크론 줄을 사용자가 서버의 `crontab -e` 로 직접 등록해야 시작된다. 테스트(`scripts/tests/`)는 와일드카드가 한 단계만 보므로 서버로 복사되지 않는다(서버에서 돌릴 필요가 없다 — 저장소에서 돌린다, §5).
>
> 배포는 같은 이름의 파일을 덮어쓴다. 서버에서 스크립트를 고치지 말고, 값은 환경변수나 크론 줄로 넘긴다.

---

## 1. 크론 등록 (사용자가 서버에서)

서버에서 `crontab -e` 를 열고 아래 줄을 더한다. `$HOME` 은 크론이 채운다. 저장소가 홈 아래 `my-platform` 이 아니면 그 경로로 바꾼다.

```cron
# 백엔드 5분 감시 — 정상이면 로그도 안 남는다(상태가 바뀌었을 때만 한 줄)
*/5 * * * * bash $HOME/my-platform/scripts/watch-backend.sh >> $HOME/logs/watch-backend.log 2>&1

# DB 복구 리허설 — 매달 1일 04:30 (03:00 백업 뒤). 결과 요약 1통. (선택)
30 4 1 * * bash $HOME/my-platform/scripts/restore-drill.sh --notify >> $HOME/logs/restore-drill.log 2>&1
```

처음 한 번:

```bash
mkdir -p ~/logs
bash ~/my-platform/scripts/watch-backend.sh --test-send   # 텔레그램 리스크 채널에 시험 메시지 1통 — 오면 경로가 산다
bash ~/my-platform/scripts/watch-backend.sh --status      # 지금 판정·상태 파일(발송 없음)
```

- 크론 줄에 `. ./.env` 를 넣지 않는다. 스크립트가 저장소 `.env` 에서 텔레그램 키 네 개(`TELEGRAM_BOT_TOKEN_RISK`·`TELEGRAM_BOT_TOKEN`·`TELEGRAM_CHAT_ID_RISK`·`TELEGRAM_CHAT_ID`)만 골라 읽는다 — 파일을 실행하지 않는다. 다른 `.env` 를 쓰려면 `OPS_ENV_FILE=<경로>` 를 크론 줄 앞에 붙인다.
- 크론 사용자는 `docker` 를 쓸 수 있어야 한다(03:00 백업 크론과 같은 사용자면 된다).
- 로그는 사건이 있을 때만 쌓여 작다. 리허설 로그는 달 1회 수십 줄이다.
- 리허설은 운영 컨테이너와 같은 이미지로 네트워크 없는 임시 컨테이너를 띄운다(CPU 1개·메모리 1GB 제한, 덤프 약 140MB 기준 수 분). 장중을 피해 새벽에 둔다.

### 크론 줄에 비밀값이 평문으로 든 줄이 있으면

`crontab -l` 은 그 사용자라면 누구나 볼 수 있고 백업·복사에 딸려 다닌다. 확인:

```bash
crontab -l | grep -nE 'TOKEN|PASSWORD|SECRET|KEY=' || echo "평문 비밀값 줄 없음"
```

있으면 그 값을 파일로 옮긴다:

1. `install -m 600 /dev/null ~/.config/myplatform-cron.env` 로 본인만 읽는 파일을 만들고 `KEY=값` 줄로 옮긴다.
2. 크론 줄에서 값을 지우고 그 파일을 읽게 한다. 이 감시·리허설은 `OPS_ENV_FILE=$HOME/.config/myplatform-cron.env bash …` 처럼 넘기면 된다(키만 골라 읽는다). 다른 스크립트가 `. 파일` 로 읽어야 한다면 POSIX 형 `.` 을 쓴다(`source` 는 크론의 `/bin/sh` 에서 죽는다 — `server-health-check.sh` 헤더).
3. 저장소 `.env` 자체도 `stat -c %a ~/my-platform/.env` 가 `600` 인지 본다.

---

## 2. 백엔드 5분 감시 (`watch-backend.sh`)

### 보는 것

- 도커: `myplatform-backend` 가 `running` 이고 health 가 `unhealthy` 가 아닌가
- API: `WATCH_HEALTH_URL`(기본 서버 안의 `/api/health`)이 10초 안에 200 인가

둘 다 보는 이유: 9월 사고에선 JVM 은 살아 있고 HTTP 받는 부분만 죽어, 프로세스·배치는 멀쩡해 보였다.

### 언제 보내나

| 사건 | 보내는 것 |
|---|---|
| 나쁨이 연속 2회(약 10분) | 🚨 죽음 1통 — 사유·시작 시각·확인할 것 |
| 계속 나쁨 | 🚨 재알림 — 직전 알림 뒤 60분마다 1통(경과 시간 포함) |
| 다시 정상 | ✅ 복구 1통 — 나빴던 시간 |
| 도커가 같은 컨테이너를 스스로 다시 띄움(재시작 횟수 증가) | 🔁 1통(60분 안의 추가 재시작은 다음 알림 횟수에 담김) |
| 정상 | 아무것도 안 보낸다 |

- **순간 실패 흡수**: 한 번의 실패로는 안 보낸다(GC 멈춤·짧은 네트워크 지연).
- **기동 유예**: 새로 만들어진 컨테이너(재시작 횟수 0)는 기동 뒤 10분 동안 실패를 세지 않는다 — 배포가 backend 를 매번 다시 만들고 재부팅 직후도 같다. 도커가 스스로 다시 띄운 컨테이너는 유예하지 않는다(기동→사망을 되풀이하면 영영 유예에 머물기 때문).
- **09:00 일일 점검과 안 겹치게**: 09:00 `server-health-check.sh` 가 unhealthy 컨테이너를 따로 알린다. 재알림이 08:51~09:09 에 걸리면 09:10 이후로 미룬다. 처음 죽음·복구 알림은 미루지 않는다(상태 변화는 바로 알린다) — 그래서 08:55 무렵 처음 죽으면 같은 아침에 두 통(이 감시의 죽음 알림 + 일일 점검의 경고)이 올 수 있다.
- **발송 실패**: 텔레그램이 실패하면 상태를 넘기지 않는다 — 다음 회차(5분 뒤)가 다시 보낸다. 보내려던 내용은 로그에 남고 종료코드 1.

### 상태 파일

`~/.local/state/myplatform/watch-backend.state`(`XDG_STATE_HOME` 이 있으면 그 아래). `key=value` 줄이고 실행하지 않고 읽는다.

- **`/tmp` 가 아닌 이유**: 재부팅 때 비워진다. 죽은 채 재부팅되면 '이미 알렸다'와 다음 재알림 시각을 잃는다.
- **파일이 없을 때(처음 설치·재부팅 뒤 지워짐·누가 지움)**: 직전 상태를 정상으로 놓고 지금부터 센다. 그때 이미 죽어 있으면 연속 2회 뒤 알린다 — 같은 사고를 두 번 알릴 수는 있어도 놓치지는 않는 쪽이다. 로그에 '상태 파일 없음' 한 줄이 남는다.
- **깨진 파일**: 없는 것으로 본다(로그 한 줄).
- 처음부터 다시 세고 싶으면 이 파일을 지우면 된다.

### 설정 (환경변수 — 크론 줄 앞에 `이름=값`)

| 변수 | 기본 | 뜻 |
|---|---|---|
| `WATCH_REALERT_MINUTES` | 60 | 죽어 있는 동안 재알림 간격(분) |
| `WATCH_FAIL_THRESHOLD` | 2 | 연속 몇 회 나쁨이면 죽음으로 보나 |
| `WATCH_START_GRACE_MINUTES` | 10 | 새 컨테이너 기동 유예(분) |
| `WATCH_DAILY_CHECK_HHMM` | 0900 | 일일 점검 시각(서버 로컬 시각 — 크론과 같은 시계) |
| `WATCH_DAILY_QUIET_MINUTES` | 10 | 그 앞뒤 재알림 보류 폭(분), 0 이면 끈다 |
| `WATCH_CONTAINER` | myplatform-backend | 볼 컨테이너 |
| `WATCH_HEALTH_URL` | 서버 안 `/api/health` | 볼 주소 |
| `WATCH_STATE_DIR` | `~/.local/state/myplatform` | 상태 파일 위치 |
| `OPS_ENV_FILE` | 저장소 `.env` | 텔레그램 키를 읽을 파일 |

### 토큰

`.env` 에서 키만 골라 읽고, curl 에는 명령줄 인자가 아니라 표준입력(`-K -`)으로 넘긴다 — `ps` 에도, 로그·출력·상태 파일에도 남지 않는다(테스트가 확인한다). 텔레그램 실패 메시지에 섞여 나오면 `***` 로 가린다.

### 자동 재시작을 넣지 않은 이유 (넣을 경우의 장단점)

이 감시는 알리기만 한다. 넣는다면 "죽음이 N회 이어지면 `docker restart myplatform-backend`" 정도가 된다.

- 장점: 사람이 못 보는 밤·주말에도 몇 분 안에 살아난다. 9월처럼 알림을 지나쳐도 며칠씩 죽어 있지는 않는다.
- 단점:
  - **원인 증거가 사라진다** — 재시작하면 그 순간의 스레드·소켓 상태를 못 본다. 진단은 죽어 있는 상태에서 해야 했다(CLAUDE.md §5).
  - **장중 재시작은 판단을 흔든다** — 봇 정합성 점검·일봉 수집·메모리 캐시가 끊기고, 표본 시작일 규칙(재시작 없는 거래일)에 걸린다. 재시작 시각은 사람이 정하는 편이 낫다.
  - **되풀이 재시작이 문제를 덮는다** — 근본 원인(메모리 등)이 그대로면 재시작이 반복되고 겉으론 '살아 있음'이 된다.
  - **감시가 운영을 바꾸게 된다** — 지금은 이 스크립트가 틀려도 알림이 하나 더 가거나 덜 갈 뿐이다. 재시작 권한을 주면 오판 한 번이 운영 중단이다.
  - OOM 은 이미 JVM 이 스스로 죽고 도커가 다시 띄운다(ExitOnOutOfMemoryError) — 가장 흔한 경우는 자동 재시작이 이미 있고, 이 감시는 그것을 🔁 로 알린다.

---

## 3. 백업 외부 사본 — 세 방법

지금 백업(매일 03:00, 30일 보관, 1개 약 140MB)은 서버와 같은 디스크에만 있다. 디스크 고장·서버 분실이면 백업도 같이 사라진다. 시그널 성적·확정 수급·등락 수처럼 다시 받을 수 없는 데이터가 있다.

| | ① 사용자 PC 가 끌어가기 (**추천**) | ② 서버가 클라우드로 밀어 올리기 | ③ 같은 집 안 두 번째 저장소(NAS·USB 디스크) |
|---|---|---|---|
| 서버에 새 비밀값 | **없음** — PC 가 이미 쓰는 SSH 키로 읽기만 한다 | 클라우드 쓰기 키가 생긴다(쓰기 전용·삭제 불가 키로 좁혀야) | NAS 가 끌어가면 없음 / 서버가 밀면 생김 |
| 서버와 다른 장소 | 예(PC 가 다른 곳에 있으면) | 예 | 아니오 — 같은 집(화재·도난엔 같이 잃음), 디스크 고장은 막는다 |
| 자동성 | PC 가 켜져 있을 때만(놓친 날은 다음에 켤 때 따라잡게 둔다) | 서버만 살아 있으면 매일 | 장비가 켜져 있으면 매일 |
| 비용·설치 | 없음 · 작업 스케줄러 1개 | 저장 비용 · rclone 같은 도구 + 암호화 설정 | 장비 값 |
| 덤프의 개인 데이터 | PC 디스크 암호화(BitLocker)로 보호 | 올리기 전에 암호화(age·gpg 공개키 — 비밀키는 서버 밖에) 필수 | 장비 암호화 |
| 서버가 털렸을 때 | 서버가 PC 사본을 못 지운다 | 쓰기 키로 덮어쓰기·삭제가 되면 사본도 위험 — 버전 보존·삭제 금지 설정 필요 | 서버가 밀면 같이 위험 |

**추천은 ①** — 서버에 새 비밀값을 두지 않고, 서버가 사본을 지울 길도 없다. 매일 최신 1개만 가져가고 PC 에서 14개쯤 보관하면 약 2GB 다. 서버가 털릴 것까지 대비하면 ① 에 주 1회 USB 로 옮기는 것을 더한다.

### ① PC 쪽 예시 (Windows PowerShell — 값 자리는 비워 둠)

전제: PC 의 `~/.ssh/config` 에 서버 접속 항목이 있다(주소·포트·키는 거기에만 — 아래 스크립트엔 별칭만 쓴다). 이 예시는 저장소에서 서버 없이 실행해 본 적이 없다 — 처음 한 번은 손으로 돌려 결과를 확인할 것.

```powershell
# pull-latest-backup.ps1 — 서버의 가장 새 DB 덤프 1개를 PC 로 가져온다(이미 있으면 건너뜀)
$Remote = '<~/.ssh/config 의 Host 별칭>'
$Dest   = '<보관 폴더, 예: D:\myplatform-backups>'
$Keep   = 14
$ErrorActionPreference = 'Stop'
New-Item -ItemType Directory -Force $Dest | Out-Null
$latest = (ssh $Remote 'ls -1t ~/backups/db-*.sql.gz | head -n 1').Trim()
if (-not $latest) { throw '서버에서 덤프를 찾지 못함' }
$name  = Split-Path $latest -Leaf
$local = Join-Path $Dest $name
if (-not (Test-Path $local)) {
  scp "${Remote}:$latest" "$local.part"
  if ($LASTEXITCODE -ne 0) { throw 'scp 실패' }
  $remoteSize = [int64](ssh $Remote "stat -c %s '$latest'").Trim()
  if ((Get-Item "$local.part").Length -ne $remoteSize) { Remove-Item "$local.part"; throw '크기가 다르다 — 받다 끊김' }
  Move-Item "$local.part" $local
}
Get-ChildItem $Dest -Filter 'db-*.sql.gz' | Sort-Object Name -Descending | Select-Object -Skip $Keep | Remove-Item
"$(Get-Date -Format s) 보관: $name"
```

작업 스케줄러 등록(PC 를 켤 때 + 매일 09:30, 놓친 날은 켤 때 따라잡음):

```powershell
$a = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument '-NoProfile -ExecutionPolicy Bypass -File "<스크립트 경로>\pull-latest-backup.ps1"'
$t = @((New-ScheduledTaskTrigger -Daily -At 9:30), (New-ScheduledTaskTrigger -AtLogOn))
Register-ScheduledTask -TaskName 'myplatform-backup-pull' -Action $a -Trigger $t -Settings (New-ScheduledTaskSettingsSet -StartWhenAvailable)
```

가져간 사본도 복원되는지 가끔 본다: PC 에 docker 가 있으면 저장소에서 `BACKUP_DIR=<보관 폴더> bash scripts/restore-drill.sh`(Git Bash 면 앞에 `MSYS_NO_PATHCONV=1`).

---

## 4. DB 복구 리허설 (`restore-drill.sh`)

```bash
bash scripts/restore-drill.sh                  # ~/backups 의 가장 새 db-*.sql.gz
bash scripts/restore-drill.sh <덤프 파일>        # 지정
bash scripts/restore-drill.sh --notify         # 끝나면 요약 1통
bash scripts/restore-drill.sh --cleanup        # 예전 리허설이 중간에 죽어 남긴 컨테이너·볼륨 정리
```

하는 일: gzip 무결성 확인 → 운영과 같은 이미지로 **네트워크 없는** 임시 컨테이너 + 임시 볼륨 → 덤프 복원(`mysql` 계정 표 구간은 빼고 — 운영 계정이 옮겨 가지 않게) → 테이블 수·flyway 최신 버전·주요 테이블 행 수와 최신 날짜 출력 → 임시 컨테이너·볼륨 삭제.

- **운영 DB 는 건드리지 않는다** — 운영 컨테이너에 접속하지 않고 덤프 파일만 읽는다.
- **덤프에는 개인 데이터가 있다** — 성공·실패·중단 모두 끝에 지운다. 지우는 것은 이름이 `myplatform-restore-drill-` 로 시작하고 라벨 `myplatform.restore-drill=1` 이 붙은 것뿐이다. 남았으면 로그가 그렇게 말하고 종료코드 1 — `--cleanup` 으로 지운다. 임시 파일은 만들지 않는다.
- 리허설용 root 비밀번호는 실행마다 새로 만들고 명령줄 인자에 싣지 않는다.
- 실패: 덤프 없음·gzip 손상·복원 실패·주요 테이블 없음·0행·정리 실패 → 종료코드 1(`--notify` 면 ❌ 요약).
- 보는 테이블: `signal_outcome`·`investor_stock_daily`·`investor_daily_trade`·`market_daily_status`·`stock_price_history`·`stock_financial_data`·`recommendation_snapshot`·`virtual_trade_history`·`users`. 늘리려면 스크립트의 `DRILL_TABLES`.
- 행 수를 지난달 결과와 견줘 크게 줄었으면 백업이 일부만 담기고 있는 것이다.

---

## 5. 테스트 (저장소에서 — 리눅스·Git Bash)

```bash
bash scripts/tests/watch-backend.test.sh         # 가짜 curl·docker, 시각 고정 (약 1분)
bash scripts/tests/restore-drill.test.sh         # 가짜 docker — 필터·정리·판정
bash scripts/tests/restore-drill.test.sh --real  # 진짜 docker — 테스트용 작은 덤프를 만들어 실제 복원 (약 30초 + 이미지 받기)
```

- `watch-backend.test.sh`: 죽음 1회 · 계속 죽어 있을 때 60분 간격 재알림 · 복구 1회 · 정상일 때 무발송 · 순간 실패 무발송 · 09:00 창 보류 · 기동 유예 · 스스로 재시작 · 발송 실패 뒤 재시도 · 텔레그램 미설정 · 깨진 상태 파일 · 토큰이 인자·출력·상태 파일에 없음.
- `restore-drill.test.sh --real` 은 운영 덤프를 쓰지 않는다 — 그 자리에서 작은 DB 를 만들어 덤프한다. 만든 컨테이너·볼륨은 리허설 라벨이 붙어 끝에 지워진다.
- 테스트는 `scripts/tests/` 아래라 서버로 복사되지 않는다.

---

## 6. 사용자 결정 (2026-11-10 정리)

| 결정 | 지금 | 바꾸려면 |
|---|---|---|
| 백업 외부 사본 방법 | ① PC 가 끌어가기를 추천 — 고르기·설치는 사용자 | §3 |
| 감시의 자동 재시작 | 넣지 않음(알리기만) | §2 장단점을 보고 판단 |
| 재알림 간격 | 60분 | 크론 줄 앞에 `WATCH_REALERT_MINUTES=<분>` |
| 크론 등록 | 사용자가 서버에서 | §1 |
| 리허설 주기 | 달 1회 새벽 추천(선택) | §1 둘째 줄 |
