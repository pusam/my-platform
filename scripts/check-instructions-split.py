#!/usr/bin/env python3
"""작업 지침(CLAUDE.md / AGENTS.md)과 docs/rules/ 의 짜임을 확인한다.

왜: 2026-11-11 에 CLAUDE.md(323KB, 세션마다 약 6만 토큰)를 '짧은 규칙 + docs/rules 의 경위 원문'으로
나눴다. 나눌 때 글자를 잃지 않았다는 것을 보이고(--migration), 그 뒤에도 규칙 줄이 다시 긴 문단으로
자라거나 번호가 끊기지 않게 한다(--structure).

쓰는 법 (저장소 어디서든):
  python scripts/check-instructions-split.py --structure            # 지침을 고친 뒤 매번
  python scripts/check-instructions-split.py --migration --base 0ff01c23   # 옮김 증명(옛 파일과 대조)
  python scripts/check-instructions-split.py --migration --base 0ff01c23 --report out.md
                                                                     # + 검토용 '의심 지점' 목록
  python scripts/check-instructions-split.py --self-test            # 이 검사기 자체가 잡아야 할 것을 잡는지

규약 (CLAUDE.md '이 파일 읽는 법' 절과 같은 내용):
  - docs/rules 의 문단 앞에는 번호 제목 '#### §4c.13' 이 있다. 번호 = 옛 CLAUDE.md 의 절과 그 절 안 항목 순서
    (하위 항목은 '§7.8-2'). CLAUDE.md 의 규칙 줄은 '(§4c.13)' 처럼 그 번호를 적는다.
  - 번호 제목 바로 아래의 '정정(날짜): …' 줄은 옮긴 문단 밖에 붙인 새 줄이다(문단 자체는 손대지 않는다).

--migration 은 일회성 증명이다. 옮긴 뒤 docs/rules 문단을 고치면 옛 파일과 어긋나는 게 정상이다.
"""
import argparse
import pathlib
import re
import shutil
import subprocess
import sys
import tempfile

try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:  # pragma: no cover - 오래된 파이썬
    pass

ROOT = pathlib.Path(__file__).resolve().parent.parent
MARKER = "설계 불변식"            # InvariantParser 의 SECTION_MARKER 와 같은 문구
SIZE_BUDGET = 120 * 1024          # CLAUDE.md 상한(LF 기준 바이트) — 처음 110KB 로 잡았다가 독립 검토가 찾은 금지 사항을 살리느라 120KB 로(2026-11-11)
LINE_BUDGET = 1200                # 불변식 절 한 줄 상한(바이트) — 넘으면 경위는 docs/rules 로
ID_PAT = r"§(?:\d+[a-e]?|ia)\.\d+(?:-\d+)?"
ID_RE = re.compile(ID_PAT)
HEAD_ID_RE = re.compile(r"^#### (" + ID_PAT + r")\s*$")
PROHIBIT_RE = re.compile(r"말 것|금지|되돌리지|되살리지")


# ---------------------------------------------------------------- 읽기

def norm_lines(raw: bytes):
    return raw.decode("utf-8").replace("\r\n", "\n").split("\n")


def read_lines(path: pathlib.Path):
    return norm_lines(path.read_bytes())


def rule_docs(root: pathlib.Path):
    base = root / "docs" / "rules"
    return sorted(base.rglob("*.md")) if base.is_dir() else []


def load_base(spec: str, root: pathlib.Path):
    p = pathlib.Path(spec)
    if p.is_file():
        return read_lines(p)
    raw = subprocess.run(["git", "show", f"{spec}:CLAUDE.md"], cwd=root,
                         capture_output=True, check=True).stdout
    return norm_lines(raw)


# ---------------------------------------------------------------- 옛 파일의 번호·절

def section_of(line: str, current):
    """h2/h3 를 만나면 바뀐 절 이름을, 아니면 None."""
    m = re.match(r"^### (\d+[a-e]?)\.", line)
    if m:
        return m.group(1)
    if line.startswith("## "):
        if line.startswith("## 프론트 IA"):
            return "ia"
        if line.startswith("## 코드 위치"):
            return "hints"
        if line.startswith("## 빌드"):
            return "build"
        if line.startswith("## 작업 완료"):
            return "done"
        return "h2:" + line
    return None


def assign_ids(lines):
    """옛 CLAUDE.md 의 줄 → 번호. 절 안 맨 위 항목은 §절.n, 들여쓴 하위 줄은 §절.n-k."""
    out, sec, n, sub = {}, "intro", 0, 0
    for i, l in enumerate(lines):
        s = section_of(l, sec)
        if s is not None:
            sec, n, sub = s, 0, 0
            continue
        if sec.startswith("h2:") or sec == "intro":
            continue
        if l.startswith("- ") or re.match(r"^\d+\. ", l):
            n += 1
            sub = 0
            out[i] = f"§{sec}.{n}"
        elif l.startswith("  ") and l.strip() and n > 0:
            sub += 1
            out[i] = f"§{sec}.{n}-{sub}"
    return out


def sections(lines):
    """각 줄이 속한 절(h3 번호 또는 h2 제목)."""
    out, sec = [], "intro"
    for l in lines:
        s = section_of(l, sec)
        if s is not None:
            sec = s
        out.append(sec)
    return out


def invariant_headings(lines):
    """InvariantParser 와 같은 규칙: '설계 불변식' h2 안의 '### ' 줄."""
    res, inside = [], False
    for raw in lines:
        l = raw.strip()
        if l.startswith("## "):
            inside = MARKER in l
            continue
        if inside and l.startswith("### "):
            res.append(raw)
    return res


def id_section(id_: str):
    return id_[1:].split(".")[0]


# ---------------------------------------------------------------- 토큰

TOKEN_PATTERNS = {
    "백틱 식별자": re.compile(r"`([^`\n]+)`"),
    "날짜": re.compile(r"20\d\d-\d\d-\d\d|(?<![\d/.])\d{1,2}/\d{1,2}(?![\d/])"),
    "시각": re.compile(r"(?<![\d:])\d{1,2}:\d{2}(?::\d{2})?(?![\d:])"),
    "V 번호": re.compile(r"\bV\d{2,3}\b"),
    "tr_id": re.compile(r"\b[A-Z]{4,6}\d{5,8}[A-Z0-9]?\b"),
    "숫자+단위": re.compile(r"\d[\d,.]*\s?(?:%p|%|억|조|원|종목|행|건|분|초|시간|회|개|줄|바이트|KB|MB|GB|MiB|배)"),
}
JAVA_TEST_RE = re.compile(r"\b[A-Z][A-Za-z0-9]*Test\b")
JS_TEST_RE = re.compile(r"[\w./-]+\.test\.js")


def test_names(text: str):
    names = set(JAVA_TEST_RE.findall(text)) | set(JS_TEST_RE.findall(text))
    # '프론트 `A`·`B` 테스트' 처럼 프론트 테스트를 화면 이름으로 적은 경우
    for m in re.finditer(r"프론트", text):
        seg = text[m.end(): m.end() + 600]
        end = seg.find("테스트")
        if end < 0:
            continue
        seg = seg[:end]
        if ". " in seg:
            continue
        names |= set(re.findall(r"`([^`\n]+)`", seg))
    return names


# ---------------------------------------------------------------- 검사: 옮김 증명

def check_migration(root: pathlib.Path, old):
    errors, notes = [], []
    claude = read_lines(root / "CLAUDE.md")
    docs = {p: read_lines(p) for p in rule_docs(root)}
    files = {root / "CLAUDE.md": claude, **docs}

    old_ids = assign_ids(old)
    old_secs = sections(old)

    # 새 파일의 줄 → 위치 목록
    where = {}
    for path, lines in files.items():
        for j, l in enumerate(lines):
            where.setdefault(l, []).append((path, j))

    new_secs = sections(claude)
    # 문서 안 각 줄 앞의 번호 제목
    head_of = {}
    for path, lines in docs.items():
        cur = None
        for j, l in enumerate(lines):
            m = HEAD_ID_RE.match(l)
            if m:
                cur = m.group(1)
            head_of[(path, j)] = cur

    moved_ids = set()
    order = {}
    for i, l in enumerate(old):
        if not l.strip() or l.strip() == "---":
            continue
        locs = where.get(l, [])
        if not locs:
            errors.append(f"잃음: 옛 {i + 1}줄 — {l[:70]}")
            continue
        if len(locs) > 1:
            errors.append(f"중복: 옛 {i + 1}줄이 {len(locs)}곳 — " +
                          ", ".join(f"{p.relative_to(root)}:{j + 1}" for p, j in locs))
            continue
        path, j = locs[0]
        if path == root / "CLAUDE.md":
            if new_secs[j] != old_secs[i]:
                errors.append(f"절이 바뀜: 옛 {i + 1}줄({old_secs[i]}) → CLAUDE.md {j + 1}줄({new_secs[j]})")
        else:
            hid = head_of.get((path, j))
            want = old_ids.get(i)
            if want is None:
                errors.append(f"번호 없는 줄이 docs 로 감: 옛 {i + 1}줄 → {path.relative_to(root)}:{j + 1}")
            elif hid != want:
                errors.append(f"번호 제목 틀림: 옛 {i + 1}줄은 {want} 인데 {path.relative_to(root)}:{j + 1} 앞 제목은 {hid}")
            else:
                moved_ids.add(want)
            order.setdefault(path, []).append((j, i))
    for path, pairs in order.items():
        idx = [i for _, i in sorted(pairs)]
        if idx != sorted(idx):
            errors.append(f"순서 뒤집힘: {path.relative_to(root)} 안의 옮긴 문단이 옛 순서가 아니다")

    if invariant_headings(old) != invariant_headings(claude):
        errors.append("### 소제목 목록이 옛 파일과 다르다(관제실 불변식 목록이 바뀐다)")
    if old[0] != claude[0]:
        errors.append("1번째 줄(제목)이 옛 파일과 다르다")

    new_all = "\n".join("\n".join(v) for v in files.values())
    old_text = "\n".join(old)
    for kind, pat in TOKEN_PATTERNS.items():
        missing = sorted({t for t in pat.findall(old_text) if t not in new_all})
        if missing:
            errors.append(f"토큰 없음({kind}) {len(missing)}개: " + ", ".join(missing[:20]))

    claude_text = "\n".join(claude)
    tests = test_names(old_text)
    missing_tests = sorted(t for t in tests if t not in claude_text)
    if missing_tests:
        errors.append(f"규칙 줄에 없는 회귀 테스트 {len(missing_tests)}개: " + ", ".join(missing_tests))

    referenced = set(ID_RE.findall(claude_text))
    for id_ in sorted(moved_ids - referenced):
        errors.append(f"규칙 없는 문단: {id_} 를 가리키는 줄이 CLAUDE.md 에 없다")

    notes.append(f"옛 줄 {sum(1 for l in old if l.strip() and l.strip() != '---')}개 대조, "
                 f"docs/rules 로 옮긴 문단 {len(moved_ids)}개, 회귀 테스트 이름 {len(tests)}개")
    return errors, notes


def check_added(root: pathlib.Path, old, later):
    """옮긴 뒤 다른 브랜치가 옛 CLAUDE.md 에 더한 줄(later 에만 있는 줄)도 새 파일들 어딘가에 정확히 한 번.

    번호는 옮길 때(--base) 고정했다 — 나중 줄 때문에 다시 매기면 기존 번호가 밀린다. 그래서 더한 줄은
    아무 번호 제목 아래에 있어도 되고(새 번호를 붙인다), 그 번호가 규칙 줄에서 가리켜지는지는 --structure 가 본다.
    """
    errors, notes = [], []
    base = set(old)
    added = [l for l in later if l.strip() and l.strip() != "---" and l not in base]
    files = [root / "CLAUDE.md", *rule_docs(root)]
    texts = {p: read_lines(p) for p in files}
    for l in added:
        hits = [(p, j) for p, lines in texts.items() for j, x in enumerate(lines) if x == l]
        if not hits:
            errors.append(f"더한 줄을 잃음: {l[:70]}")
        elif len(hits) > 1:
            errors.append(f"더한 줄 중복 {len(hits)}곳: {l[:70]}")
        else:
            later_tests = test_names(l)
            claude_text = "\n".join(texts[root / "CLAUDE.md"])
            for t in sorted(later_tests):
                if t not in claude_text:
                    errors.append(f"더한 줄의 회귀 테스트가 규칙 줄에 없다: {t}")
    notes.append(f"옮긴 뒤 더해진 줄 {len(added)}개 대조")
    return errors, notes


# ---------------------------------------------------------------- 검사: 상시 구조

def check_structure(root: pathlib.Path, run_sync=True):
    errors, notes = [], []
    claude_path = root / "CLAUDE.md"
    raw = claude_path.read_bytes().replace(b"\r\n", b"\n")
    claude = norm_lines(raw)
    text = "\n".join(claude)

    if len(raw) > SIZE_BUDGET:
        errors.append(f"CLAUDE.md {len(raw):,}바이트 > 상한 {SIZE_BUDGET:,} — 경위·실측은 docs/rules 로 옮길 것")
    heads = invariant_headings(claude)
    if len(heads) < 11 or not heads[0].startswith("### 1. 시세는 단일 경로"):
        errors.append(f"'{MARKER}' 절의 ### 소제목이 {len(heads)}개 — 관제실 불변식 목록이 깨진다")

    inside = False
    for j, l in enumerate(claude):
        if l.startswith("## "):
            inside = MARKER in l
        elif inside and len(l.encode()) > LINE_BUDGET:
            errors.append(f"CLAUDE.md {j + 1}줄 {len(l.encode())}바이트 > {LINE_BUDGET} — 규칙은 한두 줄, 경위는 docs/rules 로")

    no_code = re.sub(r"`[^`\n]*`", "", text)
    # 파일을 가리키는 @ 만 잡는다(`@vue/test-utils` 같은 패키지 이름은 아니다)
    for m in re.finditer(r"(?<![\w`])@(?:\.{1,2}/|~/|docs/|scripts/|backend/|frontend/|[\w./-]*\.md\b)[\w./-]*", no_code):
        errors.append(f"@ 가져오기 금지(문서가 통째로 다시 실린다): {m.group(0)}")

    doc_ids = {}
    for p in rule_docs(root):
        for l in read_lines(p):
            m = HEAD_ID_RE.match(l)
            if m:
                if m.group(1) in doc_ids:
                    errors.append(f"번호 제목 중복: {m.group(1)} ({p.relative_to(root)}, {doc_ids[m.group(1)]})")
                doc_ids[m.group(1)] = p.relative_to(root)
    referenced = set(ID_RE.findall(text))
    for id_ in sorted(referenced - set(doc_ids)):
        errors.append(f"끊긴 번호: CLAUDE.md 가 {id_} 를 가리키는데 docs/rules 에 그 제목이 없다")
    for id_ in sorted(set(doc_ids) - referenced):
        errors.append(f"고아 문단: docs/rules 의 {id_} ({doc_ids[id_]}) 를 가리키는 규칙 줄이 없다")

    for path in sorted(set(re.findall(r"docs/rules/[\w./-]+\.md", text))):
        if not (root / path).is_file():
            errors.append(f"없는 문서를 가리킨다: {path}")

    if run_sync:
        bash = shutil.which("bash")
        sync = root / "scripts" / "check-agent-instructions-sync.sh"
        if not bash:
            errors.append("bash 가 없어 AGENTS.md 동기화를 확인하지 못했다")
        else:
            r = subprocess.run([bash, str(sync)], cwd=root, capture_output=True)
            if r.returncode != 0:
                errors.append("AGENTS.md 동기화 실패:\n" + r.stdout.decode("utf-8", "replace")[-1500:])

    notes.append(f"CLAUDE.md {len(raw):,}바이트(LF 기준) · 줄 {len(claude)} · ### {len(heads)}개 · "
                 f"docs/rules 문단 번호 {len(doc_ids)}개 · 규칙 줄이 가리키는 번호 {len(referenced)}개")
    return errors, notes


# ---------------------------------------------------------------- 검토용 목록(실패시키지 않음)

def prohibition_report(root: pathlib.Path):
    """옮긴 문단의 '…말 것·금지·되돌리지·되살리지' 문장에서, 대응 규칙 줄에 없는 백틱 식별자."""
    claude = read_lines(root / "CLAUDE.md")
    rule_lines = {}
    for l in claude:
        for id_ in set(ID_RE.findall(l)):
            rule_lines.setdefault(id_, []).append(l)
    out = ["# 검토용 의심 지점 — 금지 문장 속 식별자 중 규칙 줄에 없는 것", "",
           "자동 추출이다. 없는 게 빈틈이라는 뜻은 아니다(규칙이 다른 말로 같은 것을 막을 수 있다).", ""]
    total = 0
    for p in rule_docs(root):
        cur = None
        for l in read_lines(p):
            m = HEAD_ID_RE.match(l)
            if m:
                cur = m.group(1)
                continue
            if cur is None or not l.strip() or l.startswith("#") or l.startswith(">") or l.startswith("정정("):
                continue
            ids_here = {cur} | {i for i in rule_lines if i.split("-")[0] == cur.split("-")[0]}
            rules = "\n".join(x for i in ids_here for x in rule_lines.get(i, []))
            parts = re.split(r"(?<=[가-힣)\]])\.\s+|\s+(?=[①-⑳㉑-㉟] )", l)
            for s in parts:
                if not PROHIBIT_RE.search(s):
                    continue
                miss = sorted({t for t in re.findall(r"`([^`\n]+)`", s) if t not in rules})
                if miss:
                    total += 1
                    out.append(f"- {cur} ({p.relative_to(root)}): \"{s[:90]}…\" → 규칙에 없는 식별자: " +
                               ", ".join(f"`{t}`" for t in miss))
    out.insert(4, f"모두 {total}건.")
    return "\n".join(out) + "\n"


# ---------------------------------------------------------------- 자체 시험

def self_test():
    old = ("# 제목\n\n## 빌드 / 테스트 명령\n- `./gradlew test`\n\n"
           "## 절대 건드리면 안 되는 설계 불변식 (의도)\n\n### 1. 시세는 단일 경로\n- 짧은 줄 `A`\n\n"
           "### 4c. 데이터 없음\n- **긴 문단** `FooService` 를 되돌리지 말 것. 회귀 `FooTest`. 2026-11-11 3,000종목\n"
           "  - 하위 `Bar` 금지\n- 둘째 긴 문단 `Baz` 15%\n\n## 코드 위치 힌트\n- 점수: `X`\n")
    old_lines = old.split("\n")
    lines = old_lines

    def build(tmp, claude, docs):
        (tmp / "docs" / "rules").mkdir(parents=True, exist_ok=True)
        (tmp / "CLAUDE.md").write_text(claude, encoding="utf-8")
        for name, body in docs.items():
            (tmp / "docs" / "rules" / name).write_text(body, encoding="utf-8")

    heads = "\n".join(f"### {n}" for n in range(1, 12))  # 상시 검사의 '11개' 조건 채우기용(구조 시험만)
    good_claude = ("# 제목\n\n## 빌드 / 테스트 명령\n- `./gradlew test`\n\n"
                   "## 절대 건드리면 안 되는 설계 불변식 (의도)\n\n### 1. 시세는 단일 경로\n- 짧은 줄 `A`\n\n"
                   "### 4c. 데이터 없음\n- **`FooService` 되돌리지 말 것.** 회귀 `FooTest`. (§4c.1, §4c.1-1)\n"
                   "- 둘째 (§4c.2)\n\n## 코드 위치 힌트\n- 점수: `X`\n")
    good_doc = ("# 경위\n\n#### §4c.1\n\n" + lines[11] + "\n\n#### §4c.1-1\n정정(2026-11-11): 예시\n\n" +
                lines[12] + "\n\n#### §4c.2\n\n" + lines[13] + "\n")
    cases = {
        "정상": (good_claude, {"4c.md": good_doc}, None),
        "잃음": (good_claude, {"4c.md": good_doc.replace(lines[13], "")}, "잃음"),
        "중복": (good_claude + lines[13] + "\n", {"4c.md": good_doc}, "중복"),
        "번호 제목 틀림": (good_claude, {"4c.md": good_doc.replace("#### §4c.2", "#### §4c.3")}, "번호 제목 틀림"),
        "순서 뒤집힘": (good_claude, {"4c.md": "# 경위\n\n#### §4c.2\n\n" + lines[13] + "\n\n#### §4c.1\n\n" +
                                       lines[11] + "\n\n#### §4c.1-1\n\n" + lines[12] + "\n"}, "순서 뒤집힘"),
        "소제목": (good_claude.replace("### 1. 시세는 단일 경로", "### 1. 시세"), {"4c.md": good_doc}, "소제목"),
        "테스트 이름": (good_claude.replace("회귀 `FooTest`. ", ""), {"4c.md": good_doc}, "회귀 테스트"),
        "규칙 없는 문단": (good_claude.replace("- 둘째 (§4c.2)\n", ""), {"4c.md": good_doc}, "규칙 없는 문단"),
    }
    failures = []
    for name, (claude, docs, expect) in cases.items():
        with tempfile.TemporaryDirectory() as d:
            tmp = pathlib.Path(d)
            build(tmp, claude, docs)
            errs, _ = check_migration(tmp, old_lines)
            joined = "\n".join(errs)
            if expect is None and errs:
                failures.append(f"[옮김:{name}] 오류가 없어야 하는데: {joined}")
            if expect is not None and expect not in joined:
                failures.append(f"[옮김:{name}] '{expect}' 를 잡지 못함: {joined or '(오류 없음)'}")

    struct_claude = good_claude.replace("### 4c. 데이터 없음", heads + "\n### 4c. 데이터 없음")
    s_cases = {
        "정상": (struct_claude, {"4c.md": good_doc}, None),
        "끊긴 번호": (struct_claude.replace("(§4c.2)", "(§4c.9)"), {"4c.md": good_doc}, "끊긴 번호"),
        "고아 문단": (struct_claude.replace("- 둘째 (§4c.2)\n", ""), {"4c.md": good_doc}, "고아 문단"),
        "줄 예산": (struct_claude.replace("- 둘째 (§4c.2)", "- " + "가" * 500 + " (§4c.2)"), {"4c.md": good_doc}, "규칙은 한두 줄"),
        "@ 가져오기": (struct_claude + "@docs/rules/4c.md\n", {"4c.md": good_doc}, "@ 가져오기"),
        "번호 중복": (struct_claude, {"4c.md": good_doc, "x.md": "#### §4c.2\n"}, "번호 제목 중복"),
        "없는 문서": (struct_claude + "- `docs/rules/none.md`\n", {"4c.md": good_doc}, "없는 문서"),
        "크기": (struct_claude + "x" * (SIZE_BUDGET + 10) + "\n", {"4c.md": good_doc}, "상한"),
    }
    for name, (claude, docs, expect) in s_cases.items():
        with tempfile.TemporaryDirectory() as d:
            tmp = pathlib.Path(d)
            build(tmp, claude, docs)
            errs, _ = check_structure(tmp, run_sync=False)
            joined = "\n".join(errs)
            if expect is None and errs:
                failures.append(f"[구조:{name}] 오류가 없어야 하는데: {joined}")
            if expect is not None and expect not in joined:
                failures.append(f"[구조:{name}] '{expect}' 를 잡지 못함: {joined or '(오류 없음)'}")

    later = old_lines[:14] + ["- 나중에 더한 문단 `Qux` 회귀 `QuxTest`"] + old_lines[14:]
    for name, (claude, doc, expect) in {
        "더한 줄 있음": (good_claude.replace("회귀 `FooTest`.", "회귀 `FooTest`·`QuxTest`."),
                     good_doc + "\n#### §4c.3\n\n" + later[14] + "\n", None),
        "더한 줄 잃음": (good_claude, good_doc, "더한 줄을 잃음"),
        "더한 줄 테스트": (good_claude, good_doc + "\n#### §4c.3\n\n" + later[14] + "\n", "규칙 줄에 없다"),
    }.items():
        with tempfile.TemporaryDirectory() as d:
            tmp = pathlib.Path(d)
            build(tmp, claude, {"4c.md": doc})
            errs, _ = check_added(tmp, old_lines, later)
            joined = "\n".join(errs)
            if expect is None and errs:
                failures.append(f"[더한 줄:{name}] 오류가 없어야 하는데: {joined}")
            if expect is not None and expect not in joined:
                failures.append(f"[더한 줄:{name}] '{expect}' 를 잡지 못함: {joined or '(오류 없음)'}")

    with tempfile.TemporaryDirectory() as d:
        tmp = pathlib.Path(d)
        build(tmp, good_claude, {"4c.md": good_doc})
        rep = prohibition_report(tmp)
        if "`Bar`" not in rep or "`FooService`" in rep:
            failures.append("[목록] 규칙에 없는 `Bar` 만 나와야 한다:\n" + rep)
    return failures


# ---------------------------------------------------------------- 실행

def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--migration", action="store_true", help="옛 CLAUDE.md 와 대조해 잃은 것이 없는지")
    ap.add_argument("--base", help="옛 CLAUDE.md — git 참조(커밋·브랜치) 또는 파일 경로. 번호는 이 파일 기준으로 고정")
    ap.add_argument("--also", action="append", default=[],
                    help="옮긴 뒤 옛 CLAUDE.md 에 줄을 더한 다른 브랜치의 참조·파일(여러 번 가능) — 더한 줄도 대조")
    ap.add_argument("--structure", action="store_true", help="번호 연결·크기 예산·@ 금지·AGENTS 동기화")
    ap.add_argument("--report", help="검토용 '금지 문장 속 식별자' 목록을 이 파일에 쓴다(실패시키지 않음)")
    ap.add_argument("--self-test", action="store_true", help="이 검사기가 잡아야 할 것을 잡는지")
    a = ap.parse_args()
    if not (a.migration or a.structure or a.self_test or a.report):
        ap.error("--migration / --structure / --self-test / --report 중 하나 이상")

    failed = False
    if a.self_test:
        f = self_test()
        print("자체 시험:", "통과" if not f else f"실패 {len(f)}건")
        for x in f:
            print("  " + x)
        failed |= bool(f)
    if a.migration:
        if not a.base:
            ap.error("--migration 에는 --base 가 필요하다")
        old_lines = load_base(a.base, ROOT)
        errs, notes = check_migration(ROOT, old_lines)
        for ref in a.also:
            e2, n2 = check_added(ROOT, old_lines, load_base(ref, ROOT))
            errs += e2
            notes += [f"{x} (--also {ref})" for x in n2]
        print(f"옮김 증명(--base {a.base}):", "통과" if not errs else f"실패 {len(errs)}건")
        for x in notes + errs:
            print("  " + x)
        failed |= bool(errs)
    if a.structure:
        errs, notes = check_structure(ROOT)
        print("구조 검사:", "통과" if not errs else f"실패 {len(errs)}건")
        for x in notes + errs:
            print("  " + x)
        failed |= bool(errs)
    if a.report:
        pathlib.Path(a.report).write_text(prohibition_report(ROOT), encoding="utf-8")
        print(f"검토용 목록: {a.report}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
