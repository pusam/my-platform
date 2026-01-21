# My Platform

Spring Boot와 Vue.js 기반의 풀스택 웹 플랫폼입니다.

## 🛠️ 기술 스택

### 백엔드
- **Framework:** Spring Boot 3.x
- **Language:** Java 17
- **Security:** Spring Security + JWT
- **Database:** Spring Data JPA + MariaDB
- **API Docs:** Swagger/OpenAPI 3.0
- **Build:** Gradle (Multi-module)

### 프론트엔드
- **Framework:** Vue 3 (Composition API)
- **Build Tool:** Vite 5
- **Router:** Vue Router 4
- **HTTP Client:** Axios

## 🖥️ 시스템 요구사항

| 항목 | 버전 | 다운로드 링크 |
|------|------|--------------|
| **JDK** | 17 이상 | [Adoptium](https://adoptium.net/) |
| **Node.js** | 18 이상 (LTS 권장) | [Node.js](https://nodejs.org/) |
| **MariaDB** | 10.6 이상 | [MariaDB](https://mariadb.org/download/) |

---

## 🚀 빠른 시작

### 1. 프로젝트 클론
```bash
git clone <레포지토리_주소>
cd myplatform
```

### 2. 데이터베이스 설정

#### 로컬 MariaDB 사용 시
제공된 SQL 스크립트를 사용하여 데이터베이스와 초기 데이터를 설정합니다.

```bash
# 1. 데이터베이스 및 테이블 생성 (스키마 설정)
mysql -u root -p < setup-database.sql

# 2. 기본 데이터(사용자, 샘플 게시글 등) 삽입
mysql -u root -p myplatform < insert-default-data.sql
```

#### 원격 서버 MariaDB 사용 시 (SSH 터널)
```bash
# SSH 터널 생성 (별도 터미널에서 실행)
ssh -p 9922 -i "~/.ssh/id_ed25519" -N -L 3307:localhost:3306 dev@your-server-ip

# 로컬 포트 3307로 접속하여 스크립트 실행
mysql -h 127.0.0.1 -P 3307 -u root -p < setup-database.sql
mysql -h 127.0.0.1 -P 3307 -u root -p myplatform < insert-default-data.sql
```

#### 기본 계정 정보
| 아이디 | 비밀번호 | 역할 | 상태 |
|--------|----------|------|------|
| admin  | admin    | ADMIN | APPROVED |
| user   | admin    | USER  | APPROVED |
| test   | admin    | USER  | APPROVED |

> **참고:** 
> - `setup-database.sql`은 데이터베이스, 사용자, 테이블을 생성합니다.
> - `insert-default-data.sql`은 기본 사용자 계정과 샘플 게시글을 삽입합니다.
> - 회원가입 기능을 통해 새로운 사용자를 등록할 수 있으며, 관리자 승인이 필요합니다.

#### 3. 백엔드 설정
로컬 환경에서는 `application-local.properties` 파일이 활성화됩니다. 이 파일에 데이터베이스 접속 정보와 JWT 비밀 키를 설정해야 합니다.

**파일 위치:** `backend/src/main/resources/application-local.properties`

```properties
# 데이터베이스 접속 정보
spring.datasource.url=jdbc:mariadb://localhost:3306/myplatform
spring.datasource.username=myplatform
spring.datasource.password=<DB_비밀번호> # 실제 데이터베이스 비밀번호를 입력하세요.

# JWT 비밀 키
jwt.secret=<매우_긴_무작위_JWT_비밀_키> # 보안을 위해 길고 복잡한 문자열을 사용하세요.
```
> **⚠️ 중요**: `application-local.properties`와 같이 민감한 정보가 포함된 파일은 Git에 커밋해서는 안 됩니다. `.gitignore`에 반드시 추가하세요.

### 4. 환경 변수 설정

프로젝트에서는 다음과 같은 환경 변수가 필요합니다:

#### 필수 환경 변수

| 환경 변수 | 설명 | 예시 |
|-----------|------|------|
| `DB_URL` | 데이터베이스 접속 URL | `jdbc:mariadb://localhost:3306/myplatform` |
| `DB_USERNAME` | 데이터베이스 사용자명 | `myplatform` |
| `DB_PASSWORD` | 데이터베이스 비밀번호 | `your_db_password` |
| `JWT_SECRET` | JWT 서명 비밀키 (최소 32자) | `your_very_long_secret_key_here_at_least_32_chars` |
| `FILE_UPLOAD_DIR` | 파일 업로드 경로 | `C:/uploads` (Windows) 또는 `/var/uploads` (Linux) |
| `MAIL_USERNAME` | Gmail SMTP 사용자명 | `yourname@gmail.com` |
| `MAIL_PASSWORD` | Gmail 앱 비밀번호 (16자리) | `abcdefghijklmnop` |

#### 선택 환경 변수 (API 기능 사용 시)

| 환경 변수 | 설명 | 비고 |
|-----------|------|------|
| `GOLD_API_KEY` | GoldAPI.io API 키 | 금 시세 조회 기능 |
| `SILVER_API_KEY` | GoldAPI.io API 키 | 은 시세 조회 기능 |
| `KIS_APP_KEY` | 한국투자증권 API 앱키 | 주식 거래 기능 |
| `KIS_APP_SECRET` | 한국투자증권 API 앱시크릿 | 주식 거래 기능 |
| `KIS_BASE_URL` | 한국투자증권 API URL | 기본값: `https://openapi.koreainvestment.com:9443` |
| `KIS_ACCOUNT_NUMBER` | 한국투자증권 계좌번호 | 주식 거래 기능 |
| `KIS_ACCOUNT_PRODUCT_CODE` | 한국투자증권 상품코드 | 주식 거래 기능 |
| `OLLAMA_URL` | Ollama AI 서버 URL | 기본값: `http://localhost:11434` |
| `OLLAMA_MODEL` | Ollama 모델명 | 기본값: `gemma2:2b` |

#### Gmail 앱 비밀번호 생성 방법:
1. Google 계정 → 보안 → 2단계 인증 활성화
2. 보안 → 앱 비밀번호 → "기타(맞춤 이름)" 선택
3. 이름 입력 후 생성 → **16자리 비밀번호 복사** (띄어쓰기 제거)

#### 한국투자증권 API 키 발급 방법:
1. [한국투자증권 오픈API](https://apiportal.koreainvestment.com/) 접속
2. 회원가입 및 로그인
3. API 신청 → 앱 등록
4. 발급된 `앱키(App Key)`와 `앱시크릿(App Secret)` 복사

#### 환경 변수 설정 방법:

**Windows (PowerShell - 임시):**
```powershell
$env:DB_URL="jdbc:mariadb://localhost:3306/myplatform"
$env:DB_USERNAME="myplatform"
$env:DB_PASSWORD="your_password"
$env:JWT_SECRET="your_very_long_secret_key_here_at_least_32_chars"
$env:FILE_UPLOAD_DIR="C:/uploads"
$env:MAIL_USERNAME="yourname@gmail.com"
$env:MAIL_PASSWORD="abcdefghijklmnop"
$env:GOLD_API_KEY="your_gold_api_key"
$env:SILVER_API_KEY="your_silver_api_key"
$env:KIS_APP_KEY="your_kis_app_key"
$env:KIS_APP_SECRET="your_kis_app_secret"
$env:KIS_ACCOUNT_NUMBER="12345678"
$env:KIS_ACCOUNT_PRODUCT_CODE="01"
```

**Windows (영구 설정 - 관리자 권한 필요):**
```powershell
[System.Environment]::SetEnvironmentVariable("DB_URL", "jdbc:mariadb://localhost:3306/myplatform", "User")
[System.Environment]::SetEnvironmentVariable("DB_USERNAME", "myplatform", "User")
[System.Environment]::SetEnvironmentVariable("DB_PASSWORD", "your_password", "User")
[System.Environment]::SetEnvironmentVariable("JWT_SECRET", "your_very_long_secret_key", "User")
[System.Environment]::SetEnvironmentVariable("FILE_UPLOAD_DIR", "C:/uploads", "User")
[System.Environment]::SetEnvironmentVariable("MAIL_USERNAME", "yourname@gmail.com", "User")
[System.Environment]::SetEnvironmentVariable("MAIL_PASSWORD", "abcdefghijklmnop", "User")
[System.Environment]::SetEnvironmentVariable("GOLD_API_KEY", "your_gold_api_key", "User")
[System.Environment]::SetEnvironmentVariable("SILVER_API_KEY", "your_silver_api_key", "User")
[System.Environment]::SetEnvironmentVariable("KIS_APP_KEY", "your_kis_app_key", "User")
[System.Environment]::SetEnvironmentVariable("KIS_APP_SECRET", "your_kis_app_secret", "User")
```

**Linux/macOS:**
```bash
export DB_URL="jdbc:mariadb://localhost:3306/myplatform"
export DB_USERNAME="myplatform"
export DB_PASSWORD="your_password"
export JWT_SECRET="your_very_long_secret_key"
export FILE_UPLOAD_DIR="/var/uploads"
export MAIL_USERNAME="yourname@gmail.com"
export MAIL_PASSWORD="abcdefghijklmnop"
export GOLD_API_KEY="your_gold_api_key"
export SILVER_API_KEY="your_silver_api_key"
export KIS_APP_KEY="your_kis_app_key"
export KIS_APP_SECRET="your_kis_app_secret"

# 영구 설정 (~/.bashrc 또는 ~/.zshrc에 추가)
echo 'export DB_URL="jdbc:mariadb://localhost:3306/myplatform"' >> ~/.bashrc
echo 'export DB_USERNAME="myplatform"' >> ~/.bashrc
# ... (나머지 변수들도 동일하게 추가)
source ~/.bashrc
```

**IntelliJ IDEA 실행 설정:**
1. Run → Edit Configurations
2. Environment variables 입력:
   ```
   DB_URL=jdbc:mariadb://localhost:3306/myplatform;DB_USERNAME=myplatform;DB_PASSWORD=your_password;JWT_SECRET=your_secret;FILE_UPLOAD_DIR=C:/uploads;MAIL_USERNAME=yourname@gmail.com;MAIL_PASSWORD=abcdefghijklmnop;GOLD_API_KEY=your_gold_api_key;SILVER_API_KEY=your_silver_api_key;KIS_APP_KEY=your_kis_app_key;KIS_APP_SECRET=your_kis_app_secret
   ```

> **⚠️ 보안 주의사항:**
> - 환경 변수에 설정한 값은 절대 Git에 커밋하지 마세요.
> - 프로덕션 환경에서는 시스템 환경 변수나 비밀 관리 도구를 사용하세요.
> - JWT_SECRET은 최소 32자 이상의 복잡한 문자열을 사용하세요.

### 5. 의존성 설치 및 실행

**터미널 1: 백엔드 실행**
```bash
# 프론트엔드 의존성 설치 (빌드 시 필요)
cd frontend
npm install
cd ..

# 'local' 프로파일로 Spring Boot 애플리케이션 실행
./gradlew :backend:bootRun --args='--spring.profiles.active=local'
```

**터미널 2: 프론트엔드 실행**
```bash
cd frontend
npm run dev
```

### 6. 접속 정보

| 서비스 | URL | 설명 |
|---|---|---|
| 프론트엔드 (개발) | http://localhost:5173 | Vite 개발 서버 (Hot Reload 지원) |
| 백엔드 API | http://localhost:8080 | Spring Boot REST API |
| Swagger UI | http://localhost:8080/swagger-ui.html | API 문서 및 테스트 도구 |

---

## 📁 프로젝트 구조

```
myplatform/
├── backend/              # Spring Boot 백엔드
├── frontend/             # Vue.js 프론트엔드
├── core/                 # 공통 유틸리티 모듈
├── jwt-redis/            # JWT 인증 모듈
├── setup-database.sql    # 데이터베이스 스키마 생성 스크립트
├── insert-default-data.sql # 기본 데이터 삽입 스크립트
└── README.md             # 이 파일
```

---

## ✨ 주요 기능

- **인증 및 권한**
  - JWT 토큰 기반의 Stateless 인증 시스템
  - 역할 기반 접근 제어 (Admin, User)
  - 회원가입 및 관리자 승인 시스템
  
- **게시판**
  - 게시글 작성, 수정, 삭제
  - 파일 첨부 기능
  - Rich Text 에디터 지원

- **관리자 기능**
  - 회원가입 승인/거부 관리
  - 전체 게시글 관리
  - 시스템 통계

- **개발 환경**
  - 멀티 모듈 Gradle 프로젝트 아키텍처
  - Vite 기반의 빠른 프론트엔드 개발 환경
  - Swagger UI를 통한 API 문서 자동화
  - 데이터베이스 스키마 및 데이터 초기화 스크립트 제공

---

## 📄 라이선스

이 프로젝트는 내부 사용을 목적으로 합니다.
