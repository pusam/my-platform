'use client';

import React, { useState, useMemo } from 'react';
import {
  Search, ChevronUp, ChevronDown, Sparkles, ArrowUpRight,
  TrendingUp, Diamond, Crown, Star, BarChart3, Info,
} from 'lucide-react';

// ═══════════════════════════════════════════════════════════════════
//  1. 유틸리티 함수
// ═══════════════════════════════════════════════════════════════════

/** Magic Formula Rank → 0~100 Smart Score 변환 */
function calculateSmartScore(rank, totalStocks) {
  if (!rank || !totalStocks || totalStocks <= 1) return rank === 1 ? 100 : 0;
  return Math.round(100 - ((rank - 1) / (totalStocks - 1)) * 100);
}

/** Smart Score → 등급 정보 */
function getGrade(score) {
  if (score >= 90) return {
    grade: 'S', label: 'SUPREME',
    bg: 'bg-violet-500/10', border: 'border-violet-500/30',
    text: 'text-violet-400', ring: 'stroke-violet-500',
    gradient: 'from-violet-500 to-purple-600',
    glow: 'shadow-violet-500/20', icon: Diamond,
  };
  if (score >= 80) return {
    grade: 'A', label: 'EXCELLENT',
    bg: 'bg-amber-500/10', border: 'border-amber-500/30',
    text: 'text-amber-400', ring: 'stroke-amber-400',
    gradient: 'from-amber-400 to-yellow-500',
    glow: 'shadow-amber-500/20', icon: Crown,
  };
  if (score >= 60) return {
    grade: 'B', label: 'GOOD',
    bg: 'bg-sky-500/10', border: 'border-sky-500/30',
    text: 'text-sky-400', ring: 'stroke-sky-400',
    gradient: 'from-sky-400 to-cyan-500',
    glow: 'shadow-sky-500/10', icon: Star,
  };
  return {
    grade: 'C', label: 'NORMAL',
    bg: 'bg-slate-500/10', border: 'border-slate-500/20',
    text: 'text-slate-400', ring: 'stroke-slate-500',
    gradient: 'from-slate-400 to-gray-500',
    glow: '', icon: BarChart3,
  };
}

// ═══════════════════════════════════════════════════════════════════
//  2. getBadges(stock) — 스마트 뱃지 자동 생성
// ═══════════════════════════════════════════════════════════════════

/**
 * 재무 데이터를 분석해 뱃지를 자동으로 생성합니다.
 * @param {Object} stock - ScreenerResultDto 형태의 종목 데이터
 * @returns {Array<{emoji, label, color, tip}>}
 */
export function getBadges(stock) {
  const badges = [];

  // 🛡️ 저평가: PER < 10 && PBR < 1
  if (stock.per != null && stock.per > 0 && stock.per < 10
      && stock.pbr != null && stock.pbr > 0 && stock.pbr < 1) {
    badges.push({
      emoji: '🛡️', label: '저평가',
      color: 'bg-blue-500/15 text-blue-300 border-blue-400/25',
      tip: `PER ${stock.per.toFixed(1)} · PBR ${stock.pbr.toFixed(2)}`,
    });
  }

  // 💰 고마진: 영업이익률 > 20%
  if (stock.operatingMargin != null && stock.operatingMargin > 20) {
    badges.push({
      emoji: '💰', label: '고마진',
      color: 'bg-emerald-500/15 text-emerald-300 border-emerald-400/25',
      tip: `영업이익률 ${stock.operatingMargin.toFixed(1)}%`,
    });
  }

  // 🚀 성장주: ROE > 15%
  if (stock.roe != null && stock.roe > 15) {
    badges.push({
      emoji: '🚀', label: '성장주',
      color: 'bg-rose-500/15 text-rose-300 border-rose-400/25',
      tip: `ROE ${stock.roe.toFixed(1)}%`,
    });
  }

  // 🏦 기관PICK: 최근 기관 수급 유입 (volumeRatio > 200% 또는 별도 플래그)
  if (stock.institutionPick || (stock.volumeRatio != null && stock.volumeRatio > 200)) {
    badges.push({
      emoji: '🏦', label: '기관PICK',
      color: 'bg-purple-500/15 text-purple-300 border-purple-400/25',
      tip: '최근 기관 순매수 유입',
    });
  }

  // 💎 고배당: 배당수익률 > 3%
  if (stock.dividendYield != null && stock.dividendYield > 3) {
    badges.push({
      emoji: '💎', label: '고배당',
      color: 'bg-cyan-500/15 text-cyan-300 border-cyan-400/25',
      tip: `배당수익률 ${stock.dividendYield.toFixed(1)}%`,
    });
  }

  // ⚡ 턴어라운드: 순이익 증가율 > 50%
  if (stock.profitGrowth != null && stock.profitGrowth > 50) {
    badges.push({
      emoji: '⚡', label: '턴어라운드',
      color: 'bg-orange-500/15 text-orange-300 border-orange-400/25',
      tip: `순이익 +${stock.profitGrowth.toFixed(0)}%`,
    });
  }

  // 🔥 저PEG 성장: PEG < 0.8
  if (stock.peg != null && stock.peg > 0 && stock.peg < 0.8) {
    badges.push({
      emoji: '🔥', label: '저PEG',
      color: 'bg-pink-500/15 text-pink-300 border-pink-400/25',
      tip: `PEG ${stock.peg.toFixed(2)} (저평가 성장주)`,
    });
  }

  return badges;
}

// ═══════════════════════════════════════════════════════════════════
//  3. AI 한 줄 요약 생성
// ═══════════════════════════════════════════════════════════════════

function generateInsight(stock) {
  if (stock.aiInsight) return stock.aiInsight;

  const parts = [];
  if (stock.per != null && stock.per < 8) parts.push('실적 대비 극심한 저평가 구간');
  else if (stock.per != null && stock.per < 12) parts.push('실적 대비 저평가 구간');

  if (stock.roe != null && stock.roe > 20) parts.push('높은 자본효율성 보유');
  else if (stock.roe != null && stock.roe > 15) parts.push('양호한 수익성');

  if (stock.operatingMargin != null && stock.operatingMargin > 25) parts.push('독보적 마진 경쟁력');
  else if (stock.operatingMargin != null && stock.operatingMargin > 15) parts.push('안정적 마진 구조');

  if (stock.dividendYield != null && stock.dividendYield > 3) parts.push('배당 매력 보유');
  if (stock.profitGrowth != null && stock.profitGrowth > 30) parts.push('가파른 이익 성장세');
  if (stock.pbr != null && stock.pbr < 0.7) parts.push('순자산 대비 할인 거래 중');

  return parts.length > 0
    ? parts.slice(0, 2).join(', ')
    : '실적 대비 저평가 구간, 배당 매력 보유';
}

// ═══════════════════════════════════════════════════════════════════
//  4. 포맷터
// ═══════════════════════════════════════════════════════════════════

function fmtCap(v) {
  if (v == null) return '-';
  if (v >= 10000) return `${(v / 10000).toFixed(1)}조`;
  if (v >= 1000) return `${(v / 1000).toFixed(0)}천억`;
  return `${v.toFixed(0)}억`;
}

function fmtPrice(v) {
  if (v == null) return '-';
  return v.toLocaleString('ko-KR') + '원';
}

// ═══════════════════════════════════════════════════════════════════
//  5. 서브 컴포넌트
// ═══════════════════════════════════════════════════════════════════

/** 도넛 형태 Score Ring */
function ScoreRing({ score, size = 52 }) {
  const grade = getGrade(score);
  const r = (size - 8) / 2;
  const c = 2 * Math.PI * r;
  const offset = c - (score / 100) * c;

  return (
    <div className="relative flex items-center justify-center shrink-0"
         style={{ width: size, height: size }}>
      <svg width={size} height={size} className="absolute -rotate-90">
        <circle cx={size / 2} cy={size / 2} r={r}
                fill="none" strokeWidth="3.5" className="stroke-white/[0.06]" />
        <circle cx={size / 2} cy={size / 2} r={r}
                fill="none" strokeWidth="3.5" strokeLinecap="round"
                strokeDasharray={c} strokeDashoffset={offset}
                className={`${grade.ring} transition-all duration-700 ease-out`} />
      </svg>
      <div className="flex flex-col items-center leading-none z-10">
        <span className={`text-[11px] font-black tracking-wide ${grade.text}`}>
          {grade.grade}
        </span>
        <span className="text-[9px] text-white/30 mt-px tabular-nums">{score}</span>
      </div>
    </div>
  );
}

/** 뱃지 칩 */
function BadgeChip({ badge }) {
  return (
    <span title={badge.tip}
          className={`inline-flex items-center gap-0.5 px-2 py-[3px] rounded-full
                      text-[10.5px] font-medium border whitespace-nowrap
                      transition-transform hover:scale-105 cursor-default ${badge.color}`}>
      <span className="leading-none">{badge.emoji}</span>
      <span>{badge.label}</span>
    </span>
  );
}

/** 숫자 셀 — good 방향에 따라 색상 강조 */
function NumCell({ value, suffix = '', good = 'high', thresholds = {} }) {
  if (value == null) return <span className="text-white/15">-</span>;

  const { excellent, ok, bad } = thresholds;
  let cls = 'text-white/50';

  if (good === 'high') {
    if (excellent != null && value >= excellent) cls = 'text-emerald-400 font-semibold';
    else if (ok != null && value >= ok) cls = 'text-emerald-400/70';
    else if (bad != null && value <= bad) cls = 'text-rose-400/60';
  } else {
    if (excellent != null && value <= excellent) cls = 'text-emerald-400 font-semibold';
    else if (ok != null && value <= ok) cls = 'text-emerald-400/70';
    else if (bad != null && value >= bad) cls = 'text-rose-400/60';
  }

  return (
    <span className={`tabular-nums ${cls}`}>
      {value.toFixed(suffix === '%' ? 1 : value < 10 ? 2 : 1)}{suffix}
    </span>
  );
}

/** Score 프로그레스 바 (Top 3 카드 내부) */
function ScoreBar({ score }) {
  const grade = getGrade(score);
  return (
    <div className="w-full h-1.5 rounded-full bg-white/[0.06] overflow-hidden">
      <div className={`h-full rounded-full bg-gradient-to-r ${grade.gradient} transition-all duration-1000 ease-out`}
           style={{ width: `${score}%` }} />
    </div>
  );
}

/** 확장 영역 디테일 행 */
function DetailRow({ label, value, suffix = '' }) {
  return (
    <div className="flex justify-between items-center py-1">
      <span className="text-white/25 text-xs">{label}</span>
      <span className="text-white/60 text-xs tabular-nums font-medium">
        {value != null ? `${value}${suffix}` : '-'}
      </span>
    </div>
  );
}

// ═══════════════════════════════════════════════════════════════════
//  6. 메인 컴포넌트
// ═══════════════════════════════════════════════════════════════════

export default function MagicFormulaSmartTable({
  stocks = MOCK_DATA,
  title = '마법의 공식',
}) {
  const [query, setQuery] = useState('');
  const [sortKey, setSortKey] = useState('rank');
  const [sortDir, setSortDir] = useState('asc');
  const [expanded, setExpanded] = useState(null);

  const total = stocks.length;

  // ---- enriched 데이터 ----
  const enriched = useMemo(() =>
    stocks.map(s => {
      const score = calculateSmartScore(s.magicFormulaRank, total);
      return { ...s, smartScore: score, grade: getGrade(score), badges: getBadges(s), insight: generateInsight(s) };
    }), [stocks, total]);

  // ---- 검색 + 정렬 ----
  const sorted = useMemo(() => {
    let list = enriched;
    if (query) {
      const q = query.toLowerCase();
      list = list.filter(s =>
        s.stockName?.toLowerCase().includes(q)
        || s.stockCode?.includes(q)
        || s.sector?.toLowerCase().includes(q));
    }
    list = [...list].sort((a, b) => {
      const pick = (s) => {
        switch (sortKey) {
          case 'score': return s.smartScore;
          case 'per': return s.per ?? 9999;
          case 'pbr': return s.pbr ?? 9999;
          case 'roe': return s.roe ?? -9999;
          case 'margin': return s.operatingMargin ?? -9999;
          case 'cap': return s.marketCap ?? 0;
          default: return s.magicFormulaRank ?? 9999;
        }
      };
      const diff = pick(a) - pick(b);
      return sortDir === 'asc' ? diff : -diff;
    });
    return list;
  }, [enriched, query, sortKey, sortDir]);

  const toggleSort = (key) => {
    if (sortKey === key) setSortDir(d => d === 'asc' ? 'desc' : 'asc');
    else {
      setSortKey(key);
      setSortDir(['roe', 'margin', 'score', 'cap'].includes(key) ? 'desc' : 'asc');
    }
  };

  const SortIcon = ({ field }) => {
    if (sortKey !== field) return <ChevronDown className="w-3 h-3 opacity-20" />;
    return sortDir === 'asc'
      ? <ChevronUp className="w-3 h-3 text-violet-400" />
      : <ChevronDown className="w-3 h-3 text-violet-400" />;
  };

  // Top 3 카드
  const top3 = enriched.filter(s => s.magicFormulaRank <= 3).slice(0, 3);

  return (
    <div className="min-h-screen bg-[#080816] text-white antialiased">
      <div className="max-w-[1440px] mx-auto px-6 py-8 space-y-7">

        {/* ═══ Header ═══ */}
        <header className="flex flex-col sm:flex-row sm:items-end sm:justify-between gap-4">
          <div>
            <div className="flex items-center gap-3 mb-1.5">
              <div className="p-2.5 rounded-2xl bg-gradient-to-br from-violet-500/20 to-purple-600/10 border border-violet-500/15">
                <Sparkles className="w-5 h-5 text-violet-400" />
              </div>
              <h1 className="text-[26px] font-extrabold tracking-tight
                             bg-gradient-to-r from-white via-white/90 to-white/50 bg-clip-text text-transparent">
                {title}
              </h1>
            </div>
            <p className="text-[13px] text-white/35 pl-[52px]">
              영업이익률 + ROE + 저PER 복합 랭킹 · AI 매력 점수 기반 추천
            </p>
          </div>

          <div className="relative">
            <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-white/25" />
            <input
              value={query}
              onChange={e => setQuery(e.target.value)}
              placeholder="종목명 · 코드 · 섹터"
              className="w-64 pl-10 pr-4 py-2.5 rounded-xl text-sm bg-white/[0.04]
                         border border-white/[0.08] placeholder:text-white/25
                         focus:outline-none focus:border-violet-500/40 focus:ring-1 focus:ring-violet-500/20
                         transition-all"
            />
          </div>
        </header>

        {/* ═══ Top 3 Picks ═══ */}
        <section className="grid grid-cols-1 sm:grid-cols-3 gap-4">
          {top3.map((s, idx) => {
            const g = s.grade;
            return (
              <div key={s.stockCode}
                   className={`relative overflow-hidden rounded-2xl border ${g.border} ${g.bg}
                               p-5 transition-all duration-300 hover:scale-[1.02]
                               hover:${g.glow} hover:shadow-lg cursor-pointer group`}>
                {/* 순위 뱃지 */}
                <div className={`absolute top-4 right-4 w-9 h-9 rounded-full
                                 bg-gradient-to-br ${g.gradient}
                                 flex items-center justify-center
                                 text-white text-sm font-black shadow-lg`}>
                  {idx + 1}
                </div>
                {/* 글로우 */}
                <div className={`absolute -top-16 -right-16 w-44 h-44 rounded-full
                                 bg-gradient-to-br ${g.gradient} opacity-[0.04]
                                 blur-3xl group-hover:opacity-[0.08] transition-opacity duration-500`} />

                <div className="relative z-10">
                  {/* 스코어 + 종목명 */}
                  <div className="flex items-center gap-3 mb-2.5">
                    <ScoreRing score={s.smartScore} />
                    <div className="min-w-0">
                      <h3 className="font-bold text-[15px] text-white truncate">{s.stockName}</h3>
                      <p className="text-[11px] text-white/30">{s.stockCode} · {s.sector || '-'}</p>
                    </div>
                  </div>

                  {/* 프로그레스 바 */}
                  <ScoreBar score={s.smartScore} />

                  {/* 인사이트 */}
                  <p className="text-[11px] text-white/25 mt-2.5 mb-3 line-clamp-1">{s.insight}</p>

                  {/* 뱃지 */}
                  <div className="flex flex-wrap gap-1.5 mb-3.5">
                    {s.badges.slice(0, 3).map((b, i) => <BadgeChip key={i} badge={b} />)}
                  </div>

                  {/* 핵심 지표 3종 */}
                  <div className="grid grid-cols-3 gap-2 text-center">
                    {[
                      { label: 'PER', val: s.per, fmt: v => v.toFixed(1) },
                      { label: 'ROE', val: s.roe, fmt: v => v.toFixed(1) + '%' },
                      { label: '영업이익률', val: s.operatingMargin, fmt: v => v.toFixed(1) + '%' },
                    ].map(m => (
                      <div key={m.label} className="bg-white/[0.04] rounded-lg py-1.5 px-1">
                        <div className="text-[9px] text-white/25 mb-0.5">{m.label}</div>
                        <div className="text-[13px] font-semibold tabular-nums text-white/80">
                          {m.val != null ? m.fmt(m.val) : '-'}
                        </div>
                      </div>
                    ))}
                  </div>
                </div>
              </div>
            );
          })}
        </section>

        {/* ═══ 메인 테이블 ═══ */}
        <div className="rounded-2xl border border-white/[0.06] bg-white/[0.015] overflow-hidden">

          {/* 테이블 헤더 */}
          <div className="grid grid-cols-[48px_52px_1.4fr_180px_80px_80px_80px_90px_90px_96px]
                          items-center px-4 py-2.5 border-b border-white/[0.06] bg-white/[0.02]
                          text-[11px] text-white/35 font-medium uppercase tracking-wider">
            <button onClick={() => toggleSort('rank')} className="flex items-center justify-center gap-0.5 hover:text-white/60">
              # <SortIcon field="rank" />
            </button>
            <button onClick={() => toggleSort('score')} className="flex items-center justify-center gap-0.5 hover:text-white/60">
              등급 <SortIcon field="score" />
            </button>
            <div>종목</div>
            <div>뱃지</div>
            {[
              { l: 'PER', k: 'per' },
              { l: 'PBR', k: 'pbr' },
              { l: 'ROE', k: 'roe' },
              { l: '영업이익률', k: 'margin' },
              { l: '시가총액', k: 'cap' },
            ].map(c => (
              <button key={c.k}
                      onClick={() => toggleSort(c.k)}
                      className="flex items-center justify-end gap-0.5 hover:text-white/60">
                {c.l} <SortIcon field={c.k} />
              </button>
            ))}
            <div className="text-right">현재가</div>
          </div>

          {/* 테이블 본문 */}
          <div className="divide-y divide-white/[0.04]">
            {sorted.map(s => {
              const g = s.grade;
              const isOpen = expanded === s.stockCode;
              const isTop = s.smartScore >= 80;

              return (
                <React.Fragment key={s.stockCode}>
                  {/* 행 */}
                  <div
                    onClick={() => setExpanded(isOpen ? null : s.stockCode)}
                    className={`grid grid-cols-[48px_52px_1.4fr_180px_80px_80px_80px_90px_90px_96px]
                                items-center px-4 py-3 cursor-pointer transition-colors duration-150
                                hover:bg-white/[0.025]
                                ${isTop ? 'bg-gradient-to-r from-transparent via-white/[0.008] to-transparent' : ''}`}>
                    {/* # */}
                    <div className="text-center">
                      <span className={`text-sm font-bold tabular-nums
                        ${s.magicFormulaRank <= 3 ? g.text : 'text-white/40'}`}>
                        {s.magicFormulaRank}
                      </span>
                    </div>

                    {/* 등급 도넛 */}
                    <div className="flex justify-center">
                      <ScoreRing score={s.smartScore} size={40} />
                    </div>

                    {/* 종목명 + 인사이트 */}
                    <div className="min-w-0 pr-2">
                      <div className="flex items-center gap-2">
                        <span className="font-semibold text-[13px] text-white truncate">{s.stockName}</span>
                        <span className="text-[10px] text-white/20 tabular-nums shrink-0">{s.stockCode}</span>
                        {s.market && (
                          <span className="text-[9px] px-1.5 py-[1px] rounded bg-white/[0.04] text-white/25 shrink-0">
                            {s.market}
                          </span>
                        )}
                      </div>
                      <p className="text-[10.5px] text-white/20 mt-0.5 truncate">{s.insight}</p>
                    </div>

                    {/* 뱃지 */}
                    <div className="flex flex-wrap gap-1 overflow-hidden">
                      {s.badges.slice(0, 2).map((b, i) => <BadgeChip key={i} badge={b} />)}
                      {s.badges.length > 2 && (
                        <span className="text-[9px] text-white/15 self-center ml-0.5">
                          +{s.badges.length - 2}
                        </span>
                      )}
                    </div>

                    {/* PER */}
                    <div className="text-right text-[13px]">
                      <NumCell value={s.per} good="low"
                               thresholds={{ excellent: 6, ok: 10, bad: 25 }} />
                    </div>

                    {/* PBR */}
                    <div className="text-right text-[13px]">
                      <NumCell value={s.pbr} good="low"
                               thresholds={{ excellent: 0.5, ok: 1, bad: 3 }} />
                    </div>

                    {/* ROE */}
                    <div className="text-right text-[13px]">
                      <NumCell value={s.roe} suffix="%" good="high"
                               thresholds={{ excellent: 20, ok: 15, bad: 5 }} />
                    </div>

                    {/* 영업이익률 */}
                    <div className="text-right text-[13px]">
                      <NumCell value={s.operatingMargin} suffix="%" good="high"
                               thresholds={{ excellent: 25, ok: 15, bad: 5 }} />
                    </div>

                    {/* 시가총액 */}
                    <div className="text-right text-[12px] text-white/40">
                      {fmtCap(s.marketCap)}
                    </div>

                    {/* 현재가 */}
                    <div className="text-right text-[13px] font-medium text-white/60 tabular-nums">
                      {fmtPrice(s.currentPrice)}
                    </div>
                  </div>

                  {/* 확장 디테일 */}
                  {isOpen && (
                    <div className="px-5 py-4 bg-white/[0.015] border-t border-white/[0.04]
                                    grid grid-cols-2 md:grid-cols-4 gap-5
                                    animate-in fade-in slide-in-from-top-1 duration-200">
                      <div>
                        <h4 className="text-[10px] text-white/30 font-semibold tracking-wider uppercase mb-1.5">밸류에이션</h4>
                        <DetailRow label="PER" value={s.per?.toFixed(1)} />
                        <DetailRow label="PBR" value={s.pbr?.toFixed(2)} />
                        <DetailRow label="PEG" value={s.peg?.toFixed(2)} />
                        <DetailRow label="EPS" value={s.eps?.toLocaleString()} suffix="원" />
                        <DetailRow label="BPS" value={s.bps?.toLocaleString()} suffix="원" />
                      </div>
                      <div>
                        <h4 className="text-[10px] text-white/30 font-semibold tracking-wider uppercase mb-1.5">수익성</h4>
                        <DetailRow label="ROE" value={s.roe?.toFixed(1)} suffix="%" />
                        <DetailRow label="영업이익률" value={s.operatingMargin?.toFixed(1)} suffix="%" />
                        <DetailRow label="순이익률" value={s.netMargin?.toFixed(1)} suffix="%" />
                        <DetailRow label="배당수익률" value={s.dividendYield?.toFixed(1)} suffix="%" />
                      </div>
                      <div>
                        <h4 className="text-[10px] text-white/30 font-semibold tracking-wider uppercase mb-1.5">성장성</h4>
                        <DetailRow label="EPS 성장률" value={s.epsGrowth?.toFixed(1)} suffix="%" />
                        <DetailRow label="매출 성장률" value={s.revenueGrowth?.toFixed(1)} suffix="%" />
                        <DetailRow label="이익 성장률" value={s.profitGrowth?.toFixed(1)} suffix="%" />
                      </div>
                      <div>
                        <h4 className="text-[10px] text-white/30 font-semibold tracking-wider uppercase mb-1.5">랭킹 상세</h4>
                        <DetailRow label="영업이익률 순위" value={s.operatingMarginRank ? `#${s.operatingMarginRank}` : null} />
                        <DetailRow label="ROE 순위" value={s.roeRank ? `#${s.roeRank}` : null} />
                        <DetailRow label="PER 순위" value={s.perRank ? `#${s.perRank}` : null} />
                        <DetailRow label="복합 점수" value={s.magicFormulaScore} />
                        <DetailRow label="Smart Score" value={`${s.smartScore}/100`} />
                      </div>
                    </div>
                  )}
                </React.Fragment>
              );
            })}

            {sorted.length === 0 && (
              <div className="py-16 text-center text-white/20 text-sm">
                검색 결과가 없습니다.
              </div>
            )}
          </div>
        </div>

        {/* Footer */}
        <footer className="text-center text-[11px] text-white/15 pb-4">
          총 {sorted.length}개 종목 · Smart Score = 마법의 공식 순위 기반 0~100 환산
        </footer>
      </div>
    </div>
  );
}

// ═══════════════════════════════════════════════════════════════════
//  7. Mock Data (실제 사용 시 API 데이터로 교체)
// ═══════════════════════════════════════════════════════════════════

const MOCK_DATA = [
  {
    stockCode: '005930', stockName: '삼성전자', market: 'KOSPI', sector: '반도체',
    currentPrice: 71000, marketCap: 423700, per: 9.8, pbr: 0.95, roe: 18.2,
    operatingMargin: 22.5, netMargin: 16.8, eps: 7245, bps: 74736,
    epsGrowth: 25.3, peg: 0.39, dividendYield: 2.1, profitGrowth: 30.2, revenueGrowth: 12.5,
    magicFormulaRank: 1, magicFormulaScore: 12,
    operatingMarginRank: 3, roeRank: 4, perRank: 5, volumeRatio: 250,
  },
  {
    stockCode: '000660', stockName: 'SK하이닉스', market: 'KOSPI', sector: '반도체',
    currentPrice: 178000, marketCap: 129500, per: 5.2, pbr: 1.45, roe: 28.9,
    operatingMargin: 35.1, netMargin: 26.4, eps: 34230, bps: 122758,
    epsGrowth: 180.2, peg: 0.03, dividendYield: 1.1, profitGrowth: 210.5, revenueGrowth: 68.3,
    magicFormulaRank: 2, magicFormulaScore: 15,
    operatingMarginRank: 1, roeRank: 1, perRank: 13, volumeRatio: 320,
  },
  {
    stockCode: '035420', stockName: 'NAVER', market: 'KOSPI', sector: '인터넷',
    currentPrice: 215000, marketCap: 35200, per: 18.5, pbr: 1.1, roe: 11.3,
    operatingMargin: 18.7, netMargin: 12.1, eps: 11621, bps: 195454,
    epsGrowth: 15.8, peg: 1.17, dividendYield: 0.5, profitGrowth: 22.3, revenueGrowth: 11.7,
    magicFormulaRank: 3, magicFormulaScore: 22,
    operatingMarginRank: 5, roeRank: 8, perRank: 9,
  },
  {
    stockCode: '005380', stockName: '현대차', market: 'KOSPI', sector: '자동차',
    currentPrice: 247000, marketCap: 52800, per: 5.1, pbr: 0.62, roe: 13.1,
    operatingMargin: 9.8, netMargin: 7.2, eps: 48431, bps: 398387,
    epsGrowth: 8.5, peg: 0.6, dividendYield: 3.6, profitGrowth: 12.4, revenueGrowth: 6.8,
    magicFormulaRank: 4, magicFormulaScore: 28,
    operatingMarginRank: 12, roeRank: 9, perRank: 7,
  },
  {
    stockCode: '006400', stockName: '삼성SDI', market: 'KOSPI', sector: '2차전지',
    currentPrice: 385000, marketCap: 26400, per: 22.3, pbr: 1.35, roe: 6.1,
    operatingMargin: 5.3, netMargin: 3.8, eps: 17264, bps: 285185,
    epsGrowth: -35.2, peg: null, dividendYield: 0.3, profitGrowth: -40.1, revenueGrowth: -8.5,
    magicFormulaRank: 5, magicFormulaScore: 35,
    operatingMarginRank: 18, roeRank: 14, perRank: 3,
  },
  {
    stockCode: '068270', stockName: '셀트리온', market: 'KOSPI', sector: '바이오',
    currentPrice: 192000, marketCap: 45200, per: 25.3, pbr: 3.8, roe: 16.2,
    operatingMargin: 32.1, netMargin: 24.5, eps: 7588, bps: 50526,
    epsGrowth: 42.1, peg: 0.6, dividendYield: 0.4, profitGrowth: 55.3, revenueGrowth: 28.9,
    magicFormulaRank: 6, magicFormulaScore: 38,
    operatingMarginRank: 2, roeRank: 6, perRank: 30,
  },
  {
    stockCode: '051910', stockName: 'LG화학', market: 'KOSPI', sector: '화학',
    currentPrice: 295000, marketCap: 20800, per: 42.1, pbr: 0.85, roe: 2.1,
    operatingMargin: 3.2, netMargin: 1.5, eps: 7007, bps: 347058,
    epsGrowth: -62.0, peg: null, dividendYield: 2.5, profitGrowth: -58.3, revenueGrowth: -4.2,
    magicFormulaRank: 7, magicFormulaScore: 42,
    operatingMarginRank: 22, roeRank: 18, perRank: 2,
  },
  {
    stockCode: '003550', stockName: 'LG', market: 'KOSPI', sector: '지주',
    currentPrice: 78500, marketCap: 12800, per: 4.8, pbr: 0.38, roe: 8.2,
    operatingMargin: 12.5, netMargin: 9.8, eps: 16354, bps: 206578,
    epsGrowth: 5.2, peg: 0.92, dividendYield: 4.2, profitGrowth: 8.1, revenueGrowth: 3.5,
    magicFormulaRank: 8, magicFormulaScore: 45,
    operatingMarginRank: 8, roeRank: 12, perRank: 25,
  },
  {
    stockCode: '034730', stockName: 'SK', market: 'KOSPI', sector: '지주',
    currentPrice: 165000, marketCap: 11500, per: 7.2, pbr: 0.42, roe: 6.0,
    operatingMargin: 8.5, netMargin: 5.2, eps: 22916, bps: 392857,
    epsGrowth: 12.8, peg: 0.56, dividendYield: 3.8, profitGrowth: 15.2, revenueGrowth: 5.1,
    magicFormulaRank: 9, magicFormulaScore: 48,
    operatingMarginRank: 15, roeRank: 15, perRank: 18,
  },
  {
    stockCode: '105560', stockName: 'KB금융', market: 'KOSPI', sector: '금융',
    currentPrice: 82000, marketCap: 32500, per: 6.3, pbr: 0.58, roe: 9.8,
    operatingMargin: null, netMargin: 18.5, eps: 13015, bps: 141379,
    epsGrowth: 18.5, peg: 0.34, dividendYield: 4.5, profitGrowth: 20.1, revenueGrowth: 8.2,
    magicFormulaRank: 10, magicFormulaScore: 52,
    operatingMarginRank: null, roeRank: 10, perRank: 22,
  },
];
