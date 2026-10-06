<template>
  <div class="today-tab">
    <!-- ① 시장 한 줄 -->
    <div v-if="hasMarketData" class="today-market">
      <span class="tm-item" :class="changeClass(marketData.kospiChangeRate)">
        KOSPI {{ marketData.kospiIndex }} ({{ signed(marketData.kospiChangeRate) }}%)
      </span>
      <span class="tm-item" :class="changeClass(marketData.kosdaqChangeRate)">
        KOSDAQ {{ marketData.kosdaqIndex }} ({{ signed(marketData.kosdaqChangeRate) }}%)
      </span>
      <!-- 진단 문구(marketStatus)가 이미 "종합 ADR(20일): 84.7 …"을 담으면 숫자를 따로 또 쓰지 않는다(같은 값이 두 번 보였다) -->
      <span v-if="marketData.adr && !statusHasAdr" class="tm-item tm-adr">ADR {{ marketData.adr }}</span>
      <span v-if="marketData.marketStatus" class="tm-status">{{ marketData.marketStatus }}</span>
    </div>

    <!-- ①-b 간밤 미국장 (보조 tilt · 미검증 참고 · regime 산식 미편입, 독립 표시) -->
    <div v-if="overnightAvailable && overnight" class="today-overnight">
      <span class="ov-label">🌙 간밤 미국장</span>
      <span class="ov-tilt" :class="overnightTiltClass(overnight.tilt)">{{ overnightTiltLabel(overnight.tilt) }}</span>
      <span v-if="overnight.drivers && overnight.drivers.length" class="ov-drivers">{{ overnight.drivers.join(' · ') }}</span>
      <span class="badge-unverified ov-beta">미검증 참고</span>
    </div>

    <!-- ①-c 매크로 tilt (VKOSPI·국고3년·SOX 추세 · 미검증 참고 · regime 산식 미편입, 독립 표시 · P3-7) -->
    <div v-if="macroAvailable && macroTilt" class="today-macro">
      <span class="ov-label">🌐 매크로</span>
      <span class="ov-tilt" :class="macroTiltClass(macroTilt.tilt)">{{ macroTiltLabel(macroTilt.tilt) }}</span>
      <span v-if="macroTilt.drivers && macroTilt.drivers.length" class="ov-drivers">{{ macroTilt.drivers.join(' · ') }}</span>
      <span class="badge-unverified ov-beta">미검증 참고</span>
    </div>

    <!-- ② 오늘의 매수 후보 -->
    <div class="today-section">
      <div class="ts-title-row">
        <h2>📌 오늘의 매수 후보</h2>
        <span class="ts-hint">종합추천 중 BUY 컷(55점) 이상만</span>
        <span v-if="recDataTime" class="ts-asof">{{ recDataTime }}</span>
        <span v-if="!recRealtime" class="ts-stale">실시간 아님 · 마지막 계산 스냅샷</span>
      </div>

      <!-- 신뢰도(실측) — 후보 바로 위에서 "이 점수를 얼마나 믿어도 되나"를 먼저 보여준다.
           소스: accuracy-by-band = 현재 산식 표본(표본 시작일 이후·교정 D+3·종목·날짜당 최초 기록, 2026-10-01).
           시작일이 미정이거나 평가된 표본이 없으면 옛 수치로 채우지 않고 '검증 중'을 말한다(§4c).
           조회 실패도 숨기지 않는다(2026-10-07) — 스트립이 사라지면 '강력 매수' 배지만 설명 없이 남았다. -->
      <div v-if="bandAccuracy || trustFailed" class="today-trust">
        <div class="tt-row">
          <span class="tt-icon">📊</span>
          <template v-if="!bandAccuracy">
            <span class="tt-title tt-pending tt-failed">적중률을 불러오지 못했습니다 — 이 점수를 얼마나 믿어도 되는지 지금은 확인할 수 없습니다. 점수는 참고용으로만 보세요.</span>
            <button type="button" class="retry-btn" @click="loadTrust">다시 시도</button>
          </template>
          <template v-else-if="trustBands.length">
            <span class="tt-title">현재 산식 실측 <template v-if="trustSince">({{ trustSince }}~{{ trustProvisional ? ' · 잠정' : '' }} · D+3 종가)</template></span>
            <span v-for="b in trustBands" :key="b.band" class="tt-chip" :class="{ 'tt-weak': b.totalSignals < 30 }">
              {{ b.band }}점 {{ b.hitRate }}%
              <em>({{ b.totalSignals }}건{{ b.totalSignals < 30 ? '·표본부족' : '' }})</em>
            </span>
          </template>
          <span v-else class="tt-title tt-pending">{{ trustPendingText }}</span>
        </div>
        <div v-if="trustCaution" class="tt-caution">⚠ {{ trustCaution }}</div>
      </div>

      <div v-if="candidatesLoading" class="ts-state">후보 분석 중...</div>
      <div v-else-if="candidatesFailed" class="ts-state failed">
        후보 데이터를 불러오지 못했습니다 — 오늘 판단을 내릴 수 없습니다.
        <button class="retry-btn" @click="loadCandidates">다시 시도</button>
      </div>
      <div v-else-if="buyCandidates.length === 0" class="ts-state empty">
        오늘은 매수 컷(55점)을 넘은 후보가 없습니다 — 관망이 결론입니다.
      </div>
      <div v-else class="candidate-list">
        <div v-for="(c, i) in buyCandidates" :key="c.stockCode" class="candidate-item">
          <!-- 카드 전체 클릭은 마우스 편의, 키보드·보조기기는 종목명 버튼으로 연다(2026-10-01) — 카드를 role="button"
               으로 두면 안의 근거 기사 링크가 '버튼 안의 링크'가 되고(링크 Enter 가 카드까지 번짐) Space 도 안 먹었다 -->
          <div class="candidate-card" @click="$emit('open-stock', c.stockCode)">
            <span class="cc-rank">#{{ i + 1 }}</span>
            <div class="cc-main">
              <div class="cc-head">
                <button type="button" class="cc-name cc-open" @click.stop="$emit('open-stock', c.stockCode)">{{ c.stockName }}</button>
                <span class="cc-grade" :class="gradeClass(c.totalScore)">{{ gradeLabel(c.totalScore) }}</span>
                <span class="cc-score">{{ c.totalScore }}점</span>
              </div>
              <div class="cc-tags">
                <span v-if="catalysts[c.stockCode]" class="cc-catalyst" :class="'cat-' + catalysts[c.stockCode].direction.toLowerCase()">
                  🔥 재료: {{ catalysts[c.stockCode].typeLabel }}({{ directionLabel(catalysts[c.stockCode].direction) }}){{ catalystAgeLabel(catalysts[c.stockCode]) }}
                  <!-- 근거 기사 링크 (V53) — 카드 클릭(종목 열기)과 분리(@click.stop). newsLink 없으면 생략(§4c) -->
                  <a v-if="catalysts[c.stockCode].newsLink" class="cc-cat-link"
                     :href="catalysts[c.stockCode].newsLink" target="_blank" rel="noopener noreferrer"
                     :title="catalysts[c.stockCode].headline || '근거 기사 보기'" @click.stop>📰</a>
                </span>
                <span v-for="(tag, ti) in displayTags(c)" :key="ti" class="cc-tag" :class="{ 'cc-tag-warn': tag.startsWith('⚠') }">{{ tag }}</span>
              </div>
            </div>
            <div class="cc-price">
              <span v-if="c.currentPrice" class="cc-price-num">{{ Number(c.currentPrice).toLocaleString('ko-KR') }}원</span>
              <span v-if="c.changeRate != null" class="cc-change" :class="changeClass(c.changeRate)">
                {{ signed(c.changeRate) }}%
              </span>
            </div>
          </div>
          <!-- 📺 유튜브 참고 의견 — 카드(추천 근거·종목 열기) 밖에 따로 둔다: 점수·순위와 무관한 외부 참고라
               시각적으로도 분리하고, 버튼 클릭이 종목 열기로 번지지 않게. 의견이 있는 후보만 한 줄. -->
          <div v-if="ytItem(c.stockCode)" class="cc-yt">
            <span class="cc-yt-label">📺 유튜브 참고</span>
            <span class="cc-yt-text">{{ summaryText(ytItem(c.stockCode).summary) }}</span>
            <span v-if="ytItem(c.stockCode).summary.latestPublishedAt" class="cc-yt-date">
              최근 {{ formatKst(ytItem(c.stockCode).summary.latestPublishedAt) }}
            </span>
            <button type="button" class="cc-yt-toggle" :aria-expanded="ytOpen[c.stockCode] ? 'true' : 'false'"
                    @click.stop="toggleYoutube(c.stockCode)" @keydown.enter.stop>
              {{ ytOpen[c.stockCode] ? '접기' : '상세' }}
            </button>
          </div>
          <div v-if="ytOpen[c.stockCode]" class="cc-yt-detail">
            <p v-if="ytDetail[c.stockCode] === 'loading'" class="cc-yt-state">불러오는 중…</p>
            <p v-else-if="ytDetail[c.stockCode] === 'failed'" class="cc-yt-state">⚠ 상세를 불러오지 못했습니다.</p>
            <YoutubeOpinionList v-else-if="ytDetail[c.stockCode]" :view="ytDetail[c.stockCode]" compact />
          </div>
        </div>
      </div>
      <!-- 유튜브 참고 범위 — 목록 전체에 한 줄(후보마다 '없음'을 반복하지 않는다). 조회 실패·분석 영상 없음·
           언급 없음을 구분해서 말한다(§4c). 기능이 꺼져 있으면 아무것도 안 그린다. -->
      <p v-if="youtubeNote" class="cc-yt-note" :class="{ 'cc-yt-note-warn': youtubeUnavailable }">{{ youtubeNote }}</p>
    </div>

    <!-- ③ 모의투자 포지션 요약 — 후보 바로 다음(위). 이 목록은 모의투자(가상) 계좌다 — 예전 이름 '내 포지션'은 실계좌처럼
         읽혔다. 평가손익은 서버가 현재가로 갱신한 값이고(refresh), 시세 기준 시각을 같이 보인다(2026-10-03). -->
    <div class="today-section" v-if="portfolioError">
      <p class="ts-pos-failed" role="status">모의투자 포지션을 불러오지 못했습니다 — 매매 탭에서 다시 확인하세요.</p>
    </div>
    <div class="today-section" v-else-if="portfolio.length">
      <div class="ts-title-row">
        <h2>💼 모의투자 포지션 {{ portfolio.length }}종목</h2>
        <span class="ts-pl" :class="totalProfitLoss >= 0 ? 'positive' : 'negative'">
          평가손익 {{ signed(totalProfitLoss, true) }}원
        </span>
      </div>
      <p v-if="portfolioAsOf" class="ts-pos-asof">{{ portfolioAsOf }} 시세 기준</p>
      <div class="position-list">
        <div v-for="p in portfolio.slice(0, 3)" :key="p.stockCode"
             class="position-row" role="button" tabindex="0"
             @click="$emit('open-stock', p.stockCode)"
             @keydown.enter.self="$emit('open-stock', p.stockCode)"
             @keydown.space.self.prevent="$emit('open-stock', p.stockCode)">
          <span class="pr-name">{{ p.stockName }}</span>
          <span class="pr-qty">{{ p.quantity }}주</span>
          <span class="pr-rate" :class="Number(p.profitRate) >= 0 ? 'positive' : 'negative'">
            {{ signed(p.profitRate) }}%
          </span>
        </div>
      </div>
      <button class="ts-more" @click="$emit('navigate', 'trade')">매매 탭에서 전체 보기 →</button>
    </div>

    <!-- ④ 시간대 신호(장전 주목주/장후 마감) — 발굴에서 이동(2026-07-01). hub 슬롯 주입(데이터/CSS=hub scope). -->
    <slot name="phase-signals" />

    <!-- ⑤ 관심종목(접힘 기본) — 발굴에서 이동(2026-07-01). hub 슬롯 주입. -->
    <slot name="watchlist" />

    <!-- ⑥ 차트 신호 관찰 — momentum 후보와 별도 모듈. 백테스트(P2-12) 결과 적중률 31%·점수 역상관
         (승격불가)이라 '매수 후보' 아님. 맨 아래 + 접기 기본(우선순위 최하) + 점수 미표시(역상관 오해 방지). -->
    <div class="today-section today-observe" v-if="timingCandidates.length || timingLoading || !timingAvailable">
      <div class="ts-title-row">
        <h2>🪝 차트 타이밍 관찰</h2>
        <span class="badge-unverified ts-beta ts-poor">예측력 미검증</span>
        <button class="btn-ghost ts-toggle" :aria-expanded="timingExpanded ? 'true' : 'false'" @click="timingExpanded = !timingExpanded">
          {{ timingExpanded ? '접기' : '펼치기' }}
        </button>
      </div>
      <!-- 접힘: 백테스트 실측 한 줄(매수 신호 아님) -->
      <div v-if="!timingExpanded" class="observe-collapsed">
        백테스트 과거 적중률 <strong>31%</strong> · 점수–수익 <strong>무관(역상관)</strong> —
        매수 신호 아님, 패턴 관찰용.
      </div>
      <template v-else>
        <div class="beta-banner beta-poor">
          ⚠ <strong>매수 신호 아님 · 관찰용</strong> — 백테스트 결과 과거 적중률 <strong>31%</strong>,
          점수와 수익이 <strong>무관(오히려 역상관)</strong>이라 점수가 높다고 좋은 자리가 아닙니다.
          정배열·눌림목 <strong>패턴이 잡힌 종목 관찰용</strong>일 뿐, 실거래·봇 신호가 아닙니다.
        </div>
        <div v-if="timingLoading" class="ts-state">타이밍 분석 중...</div>
        <!-- 분석서버(python) 미가용 — '신호 없음'과 구분해 명시 -->
        <div v-else-if="!timingAvailable" class="ts-state">⚠ 분석서버 일시 미가용 — 잠시 후 다시 확인해 주세요.</div>
        <div v-else class="candidate-list">
          <div v-for="(c, i) in timingCandidates" :key="c.code"
               class="candidate-card observe-card" role="button" tabindex="0"
               @click="$emit('open-stock', c.code)"
               @keydown.enter.self="$emit('open-stock', c.code)"
               @keydown.space.self.prevent="$emit('open-stock', c.code)">
            <span class="cc-rank">#{{ i + 1 }}</span>
            <div class="cc-main">
              <div class="cc-head">
                <span class="cc-name">{{ c.name }}</span>
                <!-- 점수(timingScore)는 백테스트상 수익과 역상관이라 미표시 — '높을수록 좋음' 오해 방지 -->
              </div>
              <div class="cc-tags">
                <span v-for="(tag, ti) in (c.signals || []).slice(0, 4)" :key="ti" class="cc-tag">{{ tag }}</span>
              </div>
            </div>
          </div>
        </div>
      </template>
    </div>

  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue';
import apiClient, { recommendationAPI, paperTradingAPI, youtubeOpinionAPI } from '../../utils/api';
import YoutubeOpinionList from './YoutubeOpinionList.vue';
import { summaryText, formatKst, coverageText } from '../../utils/youtubeOpinion';

const props = defineProps({
  marketData: { type: Object, default: null }
});

defineEmits(['open-stock', 'navigate']);

const BUY_CUT = 55;
const STRONG_BUY_CUT = 75;
const MAX_CANDIDATES = 5;

const candidatesLoading = ref(false);
// 조회 실패 여부 — '컷 통과 0건(관망)'과 구분해 표시하기 위함(§4c)
const candidatesFailed = ref(false);
const buyCandidates = ref([]);
const timingLoading = ref(false);
const timingCandidates = ref([]);    // 차트 신호 관찰 — momentum 과 별도. 백테스트 부진(승격불가)이라 매수 신호 아님
const timingAvailable = ref(true);   // dataAvailable=false → 분석서버 미가용(빈 결과와 구분)
const timingExpanded = ref(false);   // 백테스트 부진이라 기본 접힘(우선순위 낮춤). 펼쳐야 종목 표시
const catalysts = ref({});           // stockCode → catalyst dto
const bandAccuracy = ref(null);      // accuracy-by-band(보드 격리 + phase-38 컷오프 forward 실측)
const trustFailed = ref(false);      // 적중률 조회 실패 — '아직 성적 없음(검증 중)'과 다르다(§4c)
const recDataTime = ref(null);       // 후보 계산 기준 시각(백엔드 dataTime) — as-of 정직 표시
const recRealtime = ref(true);       // false = 마지막 계산 스냅샷(전일 마감 등)
const portfolio = ref([]);
const portfolioError = ref(false);
// 평가손익의 시세 기준 시각 = 행 갱신 시각 중 가장 최근(서버가 현재가로 갱신할 때 같이 바뀐다). 모르면 생략.
const portfolioAsOf = computed(() => {
  const times = portfolio.value.map(p => p && p.updatedAt).filter(t => typeof t === 'string' && t.length >= 16).sort();
  if (!times.length) return '';
  const t = times[times.length - 1];
  return `${t.slice(5, 7)}/${t.slice(8, 10)} ${t.slice(11, 16)}`;
});
const overnight = ref(null);          // 간밤 미국장 tilt(미검증 참고 · regime 산식 미편입)
const overnightAvailable = ref(true); // dataAvailable=false → Yahoo 미가용
const macroTilt = ref(null);          // 매크로 tilt(P3-7 · 미검증 참고 · regime 산식 미편입)
const macroAvailable = ref(true);     // dataAvailable=false → 3축 전부 미수집

const hasMarketData = computed(() =>
  !!(props.marketData && props.marketData.kospiIndex));
const statusHasAdr = computed(() => String(props.marketData?.marketStatus || '').includes('ADR'));

const totalProfitLoss = computed(() =>
  portfolio.value.reduce((sum, p) => sum + Number(p.profitLoss || 0), 0));

// "이 점수를 얼마나 믿어도 되나" — accuracy-by-band 실측(보드 신호 격리 + phase-38 컷오프 이후).
// 이전엔 /signal-outcomes/accuracy(전 시그널 혼합·컷오프 없음) + /backtest/performance(mark-to-market,
// 백테스트 아님)를 합성해 보여줬다 — 현재 산식 성적이 아닌 숫자라 교체(2026-07-28).
const trustBands = computed(() =>
  (bandAccuracy.value?.bands || []).filter(b => Number(b.totalSignals) > 0));
const trustSince = computed(() => bandAccuracy.value?.since || null);
const trustProvisional = computed(() => bandAccuracy.value?.sampleStatus === 'PROVISIONAL');
// 아직 현재 산식 성적이 없을 때의 문구 — 시작일 미정 vs 시작했지만 첫 평가 전(3거래일 미도래)을 구분한다.
const trustPendingText = computed(() => {
  const since = bandAccuracy.value?.sampleSince;
  if (!since || bandAccuracy.value?.sampleStatus === 'UNSET') {
    return '적중률 검증 중 — 현재 산식 표본 시작일 미정(수정 반영 확인 후 시작). 이전 산식 성적은 쓰지 않는다.';
  }
  return `적중률 검증 중 — 현재 산식 표본 ${since}부터 기록, 첫 평가는 3거래일 뒤`;
});
// 표본 충분 밴드가 하나도 없으면 경고를 '유지'한다(2026-08-05 감사 — 폴백 극성 수정).
// 예전엔 전체 밴드로 폴백해 최대 hitRate 로 판정했는데, n=1 짜리 밴드의 우연한 100% 가
// "50% 미만" 경고를 지웠다. 표본 부족은 '안심해도 된다'가 아니라 '아직 모른다'다(§4c).
const TRUST_MIN_SAMPLE = 30;
const trustCaution = computed(() => {
  if (!trustBands.value.length) return null;
  const solid = trustBands.value.filter(b => Number(b.totalSignals) >= TRUST_MIN_SAMPLE);
  if (!solid.length) {
    return `아직 판단할 표본이 없습니다(밴드별 ${TRUST_MIN_SAMPLE}건 미만) — 점수는 참고용으로만 쓰고, 손절 계획 없는 매수는 하지 마세요.`;
  }
  const best = Math.max(...solid.map(b => Number(b.hitRate) || 0));
  return best < 50
    ? '현재까지 실측 적중률이 50% 미만입니다 — 점수는 참고용으로만 쓰고, 손절 계획 없는 매수는 하지 마세요.'
    : null;
});

// 진행 중 요청 — 60초 폴링·탭 복귀·수동 재시도가 겹쳐도 한 번만 부른다.
let candidatesInFlight = false;
const loadCandidates = async () => {
  if (candidatesInFlight) return;
  candidatesInFlight = true;
  // '후보 분석 중' 자리표시는 보여줄 목록이 없을 때만 — 60초 갱신마다 기존 후보를 지우고
  // 자리표시로 바꾸면 화면이 분마다 깜빡인다. 새 결과(실패 포함)는 도착한 뒤에 교체한다.
  if (buyCandidates.value.length === 0) candidatesLoading.value = true;
  candidatesFailed.value = false;
  try {
    const { data } = await recommendationAPI.getTop5();
    if (data?.success === false || data?.dataAvailable === false || !Array.isArray(data?.data)) {
      throw new Error('후보 데이터 미가용');
    }
    const items = data.data;
    recDataTime.value = data?.dataTime || null;
    recRealtime.value = data?.realtime !== false;
    buyCandidates.value = items
      .filter(r => Number(r.totalScore) >= BUY_CUT)
      .slice(0, MAX_CANDIDATES);
    loadCatalysts();
    loadYoutubeSummary();
  } catch (e) {
    // 조회 실패를 '관망'으로 말하면 안 된다(2026-08-05 감사) — '컷 통과 0건'은 시장 판단이고
    // 조회 실패는 판단 불가다. 둘을 같은 문구로 덮으면 장애 중에도 화면이 결론을 단정한다(§4c).
    buyCandidates.value = [];
    recDataTime.value = null;
    recRealtime.value = false;
    candidatesFailed.value = true;
  } finally {
    candidatesLoading.value = false;
    candidatesInFlight = false;
  }
};

/** 재료 경과일 — catalystDate 없으면 null(미상). */
const catalystAgeDays = (cat) => {
  if (!cat?.catalystDate) return null;
  const d = new Date(cat.catalystDate);
  if (Number.isNaN(d.getTime())) return null;
  return Math.floor((Date.now() - d.getTime()) / 86400000);
};
const catalystAgeLabel = (cat) => {
  const age = catalystAgeDays(cat);
  if (age == null || age <= 0) return '';
  return ` · ${age}일 전`;
};

// 후보별 재료 배지 — 일캐시 read-only lookup(stockName 미전달 = 신규 Gemini 분류 트리거 안 함,
// 종합판단 보드와 동일 규약). 2일 초과 경과 재료는 배지 생략(보드 표시창과 동기), 1일+ 는 경과일 표기 —
// 옛 뉴스가 "오늘 재료"처럼 보이지 않게(§4c). 실패/재료없음(NONE)이면 배지 생략.
const loadCatalysts = async () => {
  for (const c of buyCandidates.value) {
    try {
      const { data } = await apiClient.get(`/stock/${c.stockCode}/catalyst`);
      const cat = data?.data;
      if (cat && cat.catalystType !== 'NONE') {
        const age = catalystAgeDays(cat);
        if (age != null && age > 2) continue;
        catalysts.value = { ...catalysts.value, [c.stockCode]: cat };
      }
    } catch (e) { /* 배지 생략 */ }
  }
};

// ── 유튜브 참고 의견 — 후보 전체를 한 번에 조회(종목마다 부르지 않는다). 후보 순서·점수는 이 값과 무관하다.
// 60초 갱신마다 부르지 않게 같은 후보 묶음은 5분 안에 다시 읽지 않는다. 실패해도 후보 표시는 그대로.
const YT_REFRESH_MS = 5 * 60 * 1000;
const youtubeSummary = ref(null);    // SummaryViewDto
const youtubeFailed = ref(false);
const ytOpen = ref({});              // stockCode → 펼침
const ytDetail = ref({});            // stockCode → StockViewDto | 'loading' | 'failed'
let ytLoadedKey = null;
let ytLoadedAt = 0;

const loadYoutubeSummary = async () => {
  const codes = buyCandidates.value.map(c => c.stockCode);
  if (!codes.length) return;
  const key = codes.join(',');
  if (key === ytLoadedKey && Date.now() - ytLoadedAt < YT_REFRESH_MS) return;
  try {
    const { data } = await youtubeOpinionAPI.getSummary(codes);
    if (!data?.success) throw new Error('유튜브 의견 요약 미가용');
    youtubeSummary.value = data.data;
    youtubeFailed.value = false;
    ytLoadedKey = key;
    ytLoadedAt = Date.now();
  } catch (e) {
    youtubeSummary.value = null;
    youtubeFailed.value = true;
  }
};

/** 의견이 있는 후보만 요약 한 줄을 받는다(없음·미분석은 목록 한 줄 안내로). */
const ytItem = (code) => {
  const s = youtubeSummary.value;
  if (!s || s.enabled === false || s.dataAvailable === false) return null;
  const item = s.items?.[code];
  return item && item.status === 'HAS_OPINIONS' && item.summary ? item : null;
};

// 조회 실패 — 요청 자체가 실패했거나, 서버가 조회 실패(dataAvailable=false)로 답했거나. 문구와 경고 표시가 같은 판정을 쓴다.
const youtubeUnavailable = computed(() => youtubeFailed.value || youtubeSummary.value?.dataAvailable === false);

const youtubeNote = computed(() => {
  if (!buyCandidates.value.length) return null;
  const s = youtubeSummary.value;
  if (youtubeUnavailable.value) {
    return '📺 유튜브 참고 의견을 불러오지 못했습니다 — 의견이 없는 것이 아니라 확인하지 못한 것입니다(후보 판단과 무관).';
  }
  if (!s || s.enabled === false) return null;
  const head = `📺 유튜브 참고 의견 · ${s.scope || '분석된 영상 기준'} · 최근 ${s.windowDays}일 · 추천 점수·순위와 무관`;
  const cov = s.coverage;
  if (!cov || !cov.analyzedVideos) {
    return `${head} — 분석을 마친 영상이 없습니다${cov ? `(${coverageText(cov)})` : ''}.`;
  }
  const withOpinion = buyCandidates.value.filter(c => ytItem(c.stockCode)).length;
  const without = buyCandidates.value.length - withOpinion;
  return `${head} — ${coverageText(cov)} · 의견 있는 후보 ${withOpinion} · 언급 없음 ${without}`;
});

const toggleYoutube = async (code) => {
  const open = !ytOpen.value[code];
  ytOpen.value = { ...ytOpen.value, [code]: open };
  const cached = ytDetail.value[code];
  if (!open || (cached && cached !== 'failed')) return;
  ytDetail.value = { ...ytDetail.value, [code]: 'loading' };
  try {
    const { data } = await youtubeOpinionAPI.getStock(code);
    if (!data?.success || data.data?.enabled === false || data.data?.dataAvailable === false) throw new Error('상세 미가용');
    ytDetail.value = { ...ytDetail.value, [code]: data.data };
  } catch (e) {
    ytDetail.value = { ...ytDetail.value, [code]: 'failed' };
  }
};

/** 태그 표시 — ⚠ 경고 태그 우선(잘림 방지), 최대 3개. */
const displayTags = (c) => {
  const tags = c.tags || [];
  const warn = tags.filter(t => typeof t === 'string' && t.startsWith('⚠'));
  const rest = tags.filter(t => !warn.includes(t));
  return [...warn, ...rest].slice(0, 3);
};

// 차트 신호 관찰 — momentum 과 정반대 objective(추세 안 눌림목). 별도 모듈.
// ⚠ 백테스트(P2-12) 결과 적중률 31%·점수 역상관(승격불가)이라 '매수 후보' 아닌 '관찰용'으로만 노출(접기 기본).
// best-effort: 후보없음이면 숨김. dataAvailable=false(분석서버 다운)는 '신호 없음'과 구분해 표기.
const loadTimingCandidates = async () => {
  timingLoading.value = true;
  try {
    const { data } = await recommendationAPI.getTrendPullbackTop10();
    const items = data?.data || [];
    timingCandidates.value = items.slice(0, MAX_CANDIDATES);
    timingAvailable.value = data?.dataAvailable !== false;   // 명시적 false 만 미가용
  } catch (e) {
    timingCandidates.value = [];
    timingAvailable.value = false;   // 네트워크 실패 = 미가용
  } finally {
    timingLoading.value = false;
  }
};

// 실패는 숨기지 않는다(2026-10-07) — 예전엔 스트립째 숨겨 후보 카드의 '강력 매수' 배지만 설명 없이 남았다.
// 성적을 지어내지는 않는다: 실패면 '확인할 수 없다'고만 말하고, 직전에 받은 값이 있으면 그대로 둔다.
const loadTrust = async () => {
  try {
    const { data } = await apiClient.get('/signal-outcomes/accuracy-by-band', { params: { days: 90 } });
    if (data?.success === false || !data?.data) throw new Error('적중률 미가용');
    bandAccuracy.value = data.data;
    trustFailed.value = false;
  } catch (e) {
    trustFailed.value = true;
  }
};

const loadPortfolio = async () => {
  try {
    const { data } = await paperTradingAPI.getPortfolio(true);
    const list = data?.data;
    portfolio.value = Array.isArray(list) ? list : [];
    portfolioError.value = false;
  } catch (e) {
    portfolio.value = [];
    portfolioError.value = true; // 조회 실패는 '보유 없음'이 아니다 — 숨기지 않고 말한다(§4c)
  }
};

// 간밤 미국장 보조 tilt — regime 산식 미편입, 참고 표시 전용(미검증). best-effort.
const loadOvernight = async () => {
  try {
    const { data } = await apiClient.get('/global-futures/overnight-us');
    overnight.value = data?.data || null;
    overnightAvailable.value = !!(overnight.value && overnight.value.dataAvailable !== false);
  } catch (e) {
    overnight.value = null;
    overnightAvailable.value = false;
  }
};

// 매크로 보조 tilt(P3-7) — VKOSPI·국고3년·SOX 추세. regime 산식 미편입, 참고 표시 전용(미검증). best-effort.
const loadMacroTilt = async () => {
  try {
    const { data } = await apiClient.get('/macro-tilt');
    macroTilt.value = data?.data || null;
    macroAvailable.value = !!(macroTilt.value && macroTilt.value.dataAvailable !== false);
  } catch (e) {
    macroTilt.value = null;
    macroAvailable.value = false;
  }
};

const gradeLabel = (score) => (Number(score) >= STRONG_BUY_CUT ? '강력 매수' : '매수');
const gradeClass = (score) => (Number(score) >= STRONG_BUY_CUT ? 'grade-strong' : 'grade-buy');
const directionLabel = (d) => ({ POSITIVE: '호재', NEGATIVE: '악재', NEUTRAL: '중립' }[d] || d);
const overnightTiltLabel = (t) => ({ BULL: '강세', NEUTRAL: '중립', BEAR: '약세' }[t] || t);
const overnightTiltClass = (t) => (t === 'BULL' ? 'positive' : t === 'BEAR' ? 'negative' : '');
const macroTiltLabel = (t) => ({ RISK_ON: '위험선호', NEUTRAL: '중립', RISK_OFF: '위험회피' }[t] || t);
const macroTiltClass = (t) => (t === 'RISK_ON' ? 'positive' : t === 'RISK_OFF' ? 'negative' : '');
const changeClass = (v) => (Number(v) > 0 ? 'positive' : Number(v) < 0 ? 'negative' : '');
const signed = (v, grouping = false) => {
  if (v == null) return '—';
  const n = Number(v);
  const text = grouping ? Math.abs(n).toLocaleString('ko-KR') : Math.abs(n);
  return `${n > 0 ? '+' : n < 0 ? '-' : ''}${text}`;
};

// 부모의 60초 폴링·탭 복귀 갱신을 공유한다. 진행 중이면 loadCandidates 가 스스로 무시한다.
// 60초 갱신 — 후보를 다시 읽고, 적중률을 못 받았으면 그것도 다시 시도한다(받은 뒤엔 마운트 1회 그대로)
const refresh = () => {
  if (trustFailed.value) loadTrust();
  return loadCandidates();
};
defineExpose({ refresh });

onMounted(() => {
  loadCandidates();
  loadTimingCandidates();
  loadTrust();
  loadPortfolio();
  loadOvernight();
  loadMacroTilt();
});
</script>

<style scoped>
.today-tab { display: flex; flex-direction: column; gap: 14px; }

/* ① 시장 한 줄 */
.today-market {
  display: flex;
  align-items: center;
  gap: 16px;
  flex-wrap: wrap;
  background: rgba(255, 255, 255, 0.04);
  border-radius: 10px;
  padding: 10px 16px;
  font-size: 13px;
}
.tm-item { font-weight: 600; }
.tm-adr { opacity: 0.7; font-weight: 400; }
.tm-status { margin-left: auto; font-size: 12px; opacity: 0.6; }

/* ①-b 간밤 미국장 (보조 tilt · 미검증 참고) */
.today-overnight {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  padding: 6px 16px;
  font-size: 12px;
  opacity: 0.92;
}
/* ①-c 매크로 — 간밤 미국장과 스타일 동일하나 독립 진화 가능하게 분리 */
.today-macro {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  padding: 6px 16px;
  font-size: 12px;
  opacity: 0.92;
}
.ov-label { font-weight: 600; opacity: 0.8; }
.ov-tilt { font-weight: 700; }
.ov-drivers { opacity: 0.7; }
.ov-beta { margin-left: auto; } /* amber 룩은 공용 .badge-unverified */

/* 공통 섹션 */
.today-section {
  background: var(--surface-card, rgba(20, 24, 38, 0.85));
  border-radius: var(--surface-radius, 12px);
  padding: 16px 18px;
}
.ts-title-row { display: flex; align-items: baseline; gap: 10px; flex-wrap: wrap; }
.ts-title-row h2 { margin: 0; font-size: 16px; }
.ts-hint { font-size: 11px; opacity: 0.5; }
.ts-asof { margin-left: auto; font-size: 11px; opacity: 0.55; }
.ts-stale {
  font-size: 11px; font-weight: 600; padding: 2px 8px; border-radius: 4px;
  color: #fbbf24; background: rgba(245, 158, 11, 0.14); border: 1px solid rgba(245, 158, 11, 0.35);
}
.ts-pl { margin-left: auto; font-size: 13px; font-weight: 700; }
.ts-state { padding: 18px 0; text-align: center; font-size: 13px; opacity: 0.6; }
.ts-state.empty { opacity: 0.75; }
.ts-state.failed { opacity: 1; color: #fbbf24; }
.retry-btn {
  display: block; margin: 10px auto 0; padding: 5px 14px; font-size: 12px; cursor: pointer;
  background: transparent; color: #fbbf24;
  border: 1px solid rgba(251,191,36,.4); border-radius: 6px;
}
.retry-btn:hover { background: rgba(251,191,36,.12); }

/* 차트 타이밍(검증 전 베타) — amber 룩은 공용 .badge-unverified, 크기만 로컬 */
.ts-beta { margin-left: auto; font-size: 11px; padding: 2px 8px; }
.beta-banner {
  margin: 10px 0 4px; padding: 8px 12px; border-radius: 8px;
  background: rgba(245, 158, 11, 0.12); border: 1px solid rgba(245, 158, 11, 0.4);
  color: #fbbf24; font-size: 12px; line-height: 1.5;
}

/* 차트 신호 관찰 — 백테스트 부진(승격불가) 톤다운(적색 계열 + 기본 접힘) */
/* 강조를 낮추는 건 위치(맨 아래)·접힘으로만 — 투명도를 겹치면 경고 문구("31%")가 대비 3.01 로 가장 흐렸다(2026-10-01) */
.today-observe { }
.ts-poor { color: #f87171; background: rgba(248, 113, 113, 0.13); }
/* 표면·hover 는 공용 .btn-ghost — 크기만 로컬 */
.ts-toggle { font-size: 11px; font-weight: 600; border-radius: 5px; padding: 2px 9px; min-height: 28px; }
.observe-collapsed { margin-top: 8px; font-size: 12px; line-height: 1.5; color: var(--text-secondary, #aab3bf); }
.observe-collapsed strong { color: #f87171; }
.beta-poor {
  background: rgba(248, 113, 113, 0.10); border-color: rgba(248, 113, 113, 0.38); color: #fca5a5;
}

/* ② 후보 카드 */
.candidate-list { display: flex; flex-direction: column; gap: 8px; margin-top: 12px; }
.candidate-card {
  display: flex;
  align-items: center;
  gap: 12px;
  background: rgba(255, 255, 255, 0.04);
  border-radius: 10px;
  padding: 12px 14px;
  cursor: pointer;
  transition: background 0.15s;
}
.candidate-card:hover { background: rgba(255, 255, 255, 0.08); }
/* 📺 유튜브 참고 — 카드 밖 별도 줄. 추천 근거와 섞여 보이지 않게 들여쓰기·보라 테두리로 구분 */
.candidate-item { display: flex; flex-direction: column; gap: 4px; min-width: 0; }
.cc-yt {
  display: flex; flex-wrap: wrap; align-items: center; gap: 4px 8px;
  margin-left: 38px; padding: 5px 10px; border-radius: 8px; min-width: 0;
  font-size: 12px; color: var(--text-secondary, #aab3bf);
  border-left: 2px solid var(--primary-start, #8b93ff); background: rgba(139, 147, 255, 0.06);
}
.cc-yt-label { font-weight: 700; color: var(--primary-light, #b7bcff); white-space: nowrap; }
.cc-yt-text { min-width: 0; overflow-wrap: anywhere; }
.cc-yt-date { font-size: 11px; color: var(--text-muted, #8a95a3); white-space: nowrap; }
.cc-yt-toggle {
  margin-left: auto; font-size: 11.5px; font-weight: 600; padding: 2px 10px; border-radius: 6px; cursor: pointer;
  color: var(--primary-light, #b7bcff); background: transparent; border: 1px solid rgba(139, 147, 255, 0.4);
}
.cc-yt-toggle:hover { background: rgba(139, 147, 255, 0.12); }
.cc-yt-detail { margin-left: 38px; min-width: 0; }
.cc-yt-state { margin: 4px 0; font-size: 12px; color: var(--text-secondary, #aab3bf); }
.cc-yt-note { margin: 8px 0 0; font-size: 11.5px; line-height: 1.5; color: var(--text-muted, #8a95a3); }
.cc-yt-note-warn { color: var(--warning, #fbbf24); }
@media (max-width: 480px) {
  .cc-yt, .cc-yt-detail { margin-left: 0; }
}
.cc-rank { font-size: 13px; font-weight: 700; opacity: 0.55; min-width: 26px; }
.cc-main { flex: 1; min-width: 0; }
.cc-head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.cc-name { font-size: 14px; font-weight: 700; }
/* 종목명 버튼 — 글자 그대로 보이게 버튼 모양만 지운다(포커스 테두리는 전역 :focus-visible) */
.cc-open {
  background: none; border: 0; padding: 0; margin: 0; color: inherit; cursor: pointer; text-align: left;
  min-height: 24px; display: inline-flex; align-items: center;   /* WCAG 2.5.8 — 글자 높이 22px 였다 */
}
.cc-open:hover { text-decoration: underline; }
.cc-grade {
  font-size: 11px;
  font-weight: 700;
  padding: 2px 8px;
  border-radius: 4px;
}
/* 매수 등급 = 한국 관례 빨강 계열 — 파랑(=매도색) 오독 방지, 결론카드·게이지와 통일 */
.grade-strong { color: var(--signal-strong-buy, #ef4444); background: rgba(239, 68, 68, 0.15); }
.grade-buy { color: var(--signal-buy, #f87171); background: rgba(248, 113, 113, 0.15); }
.cc-score { font-size: 12px; opacity: 0.7; }
.cc-tags { display: flex; gap: 6px; margin-top: 5px; flex-wrap: wrap; }
.cc-tag {
  font-size: 11px;
  padding: 1px 7px;
  border-radius: 4px;
  background: rgba(255, 255, 255, 0.07);
  opacity: 0.8;
}
/* ⚠ 경고 태그(리스크공시·신규급등 등) — 일반 태그와 구분되는 적색 톤 */
.cc-tag-warn {
  color: #fca5a5;
  background: rgba(248, 113, 113, 0.14);
  opacity: 1;
  font-weight: 600;
}
.cc-catalyst {
  font-size: 11px;
  font-weight: 600;
  padding: 1px 8px;
  border-radius: 4px;
}
.cc-cat-link {
  margin-left: 4px;
  color: inherit;
  text-decoration: none;
  opacity: 0.85;
  /* 이모지 링크라 히트박스가 15x15 였다 — WCAG 2.5.8 최소 24px 확보(2026-09-21 점검) */
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 24px;
  min-height: 24px;
}
.cc-cat-link:hover { opacity: 1; text-decoration: underline; }
.cat-positive { color: #fbbf24; background: rgba(251, 191, 36, 0.14); }
.cat-negative { color: #f87171; background: rgba(239, 68, 68, 0.14); }
.cat-neutral  { color: #cbd5e1; background: rgba(203, 213, 225, 0.12); }
.cc-price { text-align: right; }
.cc-price-num { display: block; font-size: 13px; font-weight: 600; }
.cc-change { font-size: 12px; }

/* 신뢰도(실측) — 매수 후보 섹션 상단, 후보를 보기 전에 "얼마나 믿을 수 있나"부터 */
.today-trust {
  margin-top: 10px;
  background: rgba(255, 255, 255, 0.04);
  border-radius: 10px;
  padding: 9px 12px;
  font-size: 12px;
}
.tt-row { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.tt-title { font-weight: 600; opacity: 0.75; }
.tt-chip {
  padding: 1px 8px; border-radius: 4px; font-weight: 600;
  background: rgba(255, 255, 255, 0.07);
}
.tt-chip em { font-style: normal; font-weight: 400; opacity: 0.65; font-size: 11px; }
.tt-weak { opacity: 0.6; }
/* '검증 중' 문구는 아이콘과 같은 줄에서 접힌다 — 휴대폰에서 📊 만 홀로 한 줄을 차지했다(2026-10-01) */
.tt-pending { flex: 1 1 0; min-width: 0; }
.tt-failed { color: var(--warning-color, #eab308); opacity: 1; }
.tt-caution {
  margin-top: 6px; font-size: 11.5px; line-height: 1.45;
  color: #fbbf24;
}

.ts-pos-asof { margin: 4px 0 0; font-size: 12px; opacity: 0.65; }
.ts-pos-failed { margin: 0; font-size: 13px; color: var(--warning-color, #eab308); }
/* ④ 포지션 */
.position-list { margin-top: 10px; display: flex; flex-direction: column; gap: 6px; }
.position-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 8px 10px;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.04);
  font-size: 13px;
  cursor: pointer;
}
.position-row:hover { background: rgba(255, 255, 255, 0.08); }
.pr-name { font-weight: 600; flex: 1; }
.pr-qty { opacity: 0.6; font-size: 12px; }
.pr-rate { font-weight: 700; min-width: 64px; text-align: right; }
.ts-more {
  margin-top: 10px;
  background: none;
  border: none;
  color: #93c5fd;
  font-size: 12px;
  cursor: pointer;
  padding: 0;
}

/* 등락색은 한국 관례(상승=빨강/하락=파랑) — 허브(StockTradingDashboardV2)·디자인 토큰(--stock-up/down)과 통일 */
.positive { color: var(--stock-up, #f87171); }
.negative { color: var(--stock-down, #60a5fa); }

@media (max-width: 600px) {
  .today-market { gap: 10px; font-size: 12px; }
  .tm-status { flex-basis: 100%; margin-left: 0; }
  /* 간밤·매크로: "라벨 · 판정 · 미검증 참고" 한 줄 + 근거 한 줄. 배지가 오른쪽 끝(margin-left:auto)에 있으면
     줄이 넘칠 때 배지만 따로 한 줄을 차지해 두 행이 각 3줄이 됐다(2026-10-01 375px 실측). */
  .today-overnight .ov-beta, .today-macro .ov-beta { order: 2; margin-left: 0; }
  .today-overnight .ov-drivers, .today-macro .ov-drivers { order: 3; flex-basis: 100%; }
}
</style>
