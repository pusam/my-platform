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

### 4. 의존성 설치 및 실행

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

### 5. 접속 정보

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
