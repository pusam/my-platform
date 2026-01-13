-- 기본 사용자 데이터 삽입
-- 실행 방법: mysql -u myplatform -p myplatform < insert-default-data.sql
-- 또는: mysql -u root -p myplatform < insert-default-data.sql

USE myplatform;

-- 기존 데이터 삭제 (선택사항 - 필요시 주석 해제)
DELETE FROM board_file;
DELETE FROM board;
DELETE FROM users;

-- 관리자 계정 (비밀번호: admin123)
INSERT INTO users (username, password, name, role, status)
VALUES (
    'admin',
    '$2a$10$MICn2deUJNhh/4L1ntVTuOxkeuFnKk/6SgzTIg2ja.Q5Usf4yrwbS',
    '관리자',
    'ADMIN',
    'APPROVED'
) ON DUPLICATE KEY UPDATE
    password = VALUES(password),
    name = VALUES(name),
    role = VALUES(role),
    status = VALUES(status);

-- 일반 사용자 계정 (비밀번호: user123)
INSERT INTO users (username, password, name, role, status)
VALUES (
    'user',
    '$2a$10$26A.FAj11yLQh.Rv9K6wRu8IVj8629qer7QJZgQzUWciTLPIU3XO.',
    '사용자',
    'USER',
    'APPROVED'
) ON DUPLICATE KEY UPDATE
    password = VALUES(password),
    name = VALUES(name),
    role = VALUES(role),
    status = VALUES(status);

-- 샘플 게시글 (선택사항)
INSERT INTO board (title, content, author, author_name, views)
VALUES
(
    '환영합니다!',
    '<h1>My Platform 게시판에 오신 것을 환영합니다!</h1><p>이 게시판에서는 다양한 정보를 공유할 수 있습니다.</p><ul><li>자유로운 글 작성</li><li>파일 첨부 기능</li><li>Rich Text 에디터 지원</li></ul>',
    'admin',
    '관리자',
    0
),
(
    '게시판 사용 방법',
    '<h2>게시판 사용 방법</h2><p><strong>글 작성:</strong> 상단의 "글쓰기" 버튼을 클릭하세요.</p><p><strong>파일 첨부:</strong> 글 작성 시 파일을 선택할 수 있습니다. (최대 10MB/파일)</p><p><strong>검색:</strong> 제목이나 내용으로 검색이 가능합니다.</p>',
    'admin',
    '관리자',
    0
);

-- 삽입된 데이터 확인
SELECT '=== 사용자 목록 ===' as Info;
SELECT username, name, role, status, created_at FROM users;

SELECT '=== 게시글 목록 ===' as Info;
SELECT id, title, author_name, views, created_at FROM board;

-- 완료 메시지
SELECT '✅ 기본 데이터가 성공적으로 삽입되었습니다!' as Result;
SELECT '📝 기본 계정 정보:' as Info;
SELECT '   - admin/admin123 (관리자)' as Account1;
SELECT '   - user/user123 (일반 사용자)' as Account2;
