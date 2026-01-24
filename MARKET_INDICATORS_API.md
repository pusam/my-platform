# 📊 주식 시장 지표 API 가이드

## 🎯 개요

한국투자증권 API를 통해 다양한 주식 시장 지표와 순위 정보를 제공합니다.

## 📈 제공 지표 (총 6가지)

### 1. 52주 신고가 종목 🔥
- **의미**: 1년(52주) 중 최고가를 경신한 종목
- **활용**: 강세장 진입 시그널, 모멘텀 투자
- **엔드포인트**: `GET /api/market/52week-high`

### 2. 52주 신저가 종목 ❄️
- **의미**: 1년(52주) 중 최저가를 경신한 종목
- **활용**: 저점 매수 기회, 가치 투자
- **엔드포인트**: `GET /api/market/52week-low`

### 3. 시가총액 상위 💰
- **의미**: 시가총액 기준 대형주 TOP 50
- **활용**: 블루칩 투자, 포트폴리오 안정성
- **엔드포인트**: `GET /api/market/market-cap`

### 4. 거래대금 상위 💹
- **의미**: 거래량 × 가격으로 계산한 활발한 거래 종목
- **활용**: 유동성 높은 종목, 단기 트레이딩
- **엔드포인트**: `GET /api/market/trading-value`

### 5. 급등주 (등락률 상위) 🚀
- **의미**: 당일 가장 많이 오른 종목 TOP 50
- **활용**: 모멘텀 투자, 상승 종목 파악
- **엔드포인트**: `GET /api/market/price-rise`

### 6. 급락주 (등락률 하위) 📉
- **의미**: 당일 가장 많이 떨어진 종목 TOP 50
- **활용**: 반등 기회, 손절 타이밍
- **엔드포인트**: `GET /api/market/price-fall`

---

## 🔌 API 응답 예시

### 공통 응답 형식
```json
{
  "success": true,
  "message": "52주 신고가 종목 조회 성공",
  "data": [
    {
      "stockCode": "005930",
      "stockName": "삼성전자",
      "currentPrice": 75000,
      "changeAmount": 2000,
      "changeRate": 2.74,
      "openPrice": 73500,
      "highPrice": 75500,
      "lowPrice": 73200,
      "volume": 15420000,
      "tradingValue": 1156500,
      "marketCap": 4487500,
      "week52High": 75500,
      "week52Low": 62000,
      "week52HighRate": -0.66,
      "week52LowRate": 20.97,
      "per": 12.5,
      "pbr": 1.8,
      "rank": 1,
      "indicatorType": "52W_HIGH"
    }
  ]
}
```

### 필드 설명
| 필드 | 타입 | 설명 | 단위 |
|------|------|------|------|
| `stockCode` | String | 종목코드 | - |
| `stockName` | String | 종목명 | - |
| `currentPrice` | BigDecimal | 현재가 | 원 |
| `changeAmount` | BigDecimal | 전일대비 | 원 |
| `changeRate` | BigDecimal | 등락률 | % |
| `volume` | Long | 거래량 | 주 |
| `tradingValue` | BigDecimal | 거래대금 | 백만원 |
| `marketCap` | BigDecimal | 시가총액 | 억원 |
| `week52High` | BigDecimal | 52주 최고가 | 원 |
| `week52Low` | BigDecimal | 52주 최저가 | 원 |
| `week52HighRate` | BigDecimal | 52주 최고가 대비율 | % |
| `week52LowRate` | BigDecimal | 52주 최저가 대비율 | % |
| `per` | BigDecimal | 주가수익비율 | 배 |
| `pbr` | BigDecimal | 주가순자산비율 | 배 |
| `rank` | Integer | 순위 | - |
| `indicatorType` | String | 지표 타입 | - |

---

## 💻 프론트엔드 사용 예시

### Vue.js 컴포넌트

```vue
<template>
  <div class="market-indicators">
    <h2>시장 지표</h2>
    
    <!-- 지표 선택 탭 -->
    <div class="indicator-tabs">
      <button @click="loadIndicator('52week-high')" :class="{ active: activeIndicator === '52week-high' }">
        🔥 52주 신고가
      </button>
      <button @click="loadIndicator('52week-low')" :class="{ active: activeIndicator === '52week-low' }">
        ❄️ 52주 신저가
      </button>
      <button @click="loadIndicator('market-cap')" :class="{ active: activeIndicator === 'market-cap' }">
        💰 시가총액 상위
      </button>
      <button @click="loadIndicator('trading-value')" :class="{ active: activeIndicator === 'trading-value' }">
        💹 거래대금 상위
      </button>
      <button @click="loadIndicator('price-rise')" :class="{ active: activeIndicator === 'price-rise' }">
        🚀 급등주
      </button>
      <button @click="loadIndicator('price-fall')" :class="{ active: activeIndicator === 'price-fall' }">
        📉 급락주
      </button>
    </div>

    <!-- 로딩 -->
    <div v-if="loading" class="loading">데이터 로딩중...</div>

    <!-- 종목 리스트 -->
    <div v-else class="stock-list">
      <div v-for="stock in stocks" :key="stock.stockCode" class="stock-item">
        <div class="rank">{{ stock.rank }}</div>
        <div class="info">
          <h3>{{ stock.stockName }} <span class="code">{{ stock.stockCode }}</span></h3>
          <div class="details">
            <span class="price">{{ formatPrice(stock.currentPrice) }}원</span>
            <span class="change" :class="changeClass(stock.changeRate)">
              {{ stock.changeRate > 0 ? '▲' : stock.changeRate < 0 ? '▼' : '-' }}
              {{ Math.abs(stock.changeRate) }}%
            </span>
          </div>
        </div>
        <div class="metrics">
          <div v-if="stock.week52HighRate !== null" class="metric">
            <span class="label">52주고 대비</span>
            <span class="value">{{ stock.week52HighRate }}%</span>
          </div>
          <div v-if="stock.week52LowRate !== null" class="metric">
            <span class="label">52주저 대비</span>
            <span class="value">{{ stock.week52LowRate }}%</span>
          </div>
          <div v-if="stock.marketCap" class="metric">
            <span class="label">시총</span>
            <span class="value">{{ formatMarketCap(stock.marketCap) }}</span>
          </div>
          <div v-if="stock.tradingValue" class="metric">
            <span class="label">거래대금</span>
            <span class="value">{{ formatTradingValue(stock.tradingValue) }}</span>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue';
import { marketAPI } from '@/utils/api';

const activeIndicator = ref('52week-high');
const stocks = ref([]);
const loading = ref(false);

const loadIndicator = async (indicator) => {
  activeIndicator.value = indicator;
  loading.value = true;
  
  try {
    let response;
    switch (indicator) {
      case '52week-high':
        response = await marketAPI.get52WeekHigh();
        break;
      case '52week-low':
        response = await marketAPI.get52WeekLow();
        break;
      case 'market-cap':
        response = await marketAPI.getMarketCap();
        break;
      case 'trading-value':
        response = await marketAPI.getTradingValue();
        break;
      case 'price-rise':
        response = await marketAPI.getPriceRise();
        break;
      case 'price-fall':
        response = await marketAPI.getPriceFall();
        break;
    }
    
    if (response.data.success) {
      stocks.value = response.data.data;
    }
  } catch (error) {
    console.error('지표 로딩 실패:', error);
  } finally {
    loading.value = false;
  }
};

const formatPrice = (price) => {
  return new Intl.NumberFormat('ko-KR').format(price);
};

const formatMarketCap = (marketCap) => {
  if (marketCap >= 10000) {
    return `${(marketCap / 10000).toFixed(1)}조원`;
  }
  return `${marketCap.toFixed(0)}억원`;
};

const formatTradingValue = (value) => {
  if (value >= 1000) {
    return `${(value / 1000).toFixed(1)}십억원`;
  }
  return `${value.toFixed(0)}백만원`;
};

const changeClass = (changeRate) => {
  if (changeRate > 0) return 'positive';
  if (changeRate < 0) return 'negative';
  return 'neutral';
};

// 초기 로드
loadIndicator('52week-high');
</script>

<style scoped>
.indicator-tabs {
  display: flex;
  gap: 10px;
  margin-bottom: 20px;
  flex-wrap: wrap;
}

.indicator-tabs button {
  padding: 10px 20px;
  border: 2px solid #ddd;
  background: white;
  border-radius: 8px;
  cursor: pointer;
  font-size: 14px;
}

.indicator-tabs button.active {
  background: #4CAF50;
  color: white;
  border-color: #4CAF50;
}

.stock-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.stock-item {
  display: flex;
  align-items: center;
  padding: 15px;
  background: white;
  border: 1px solid #ddd;
  border-radius: 8px;
  gap: 15px;
}

.rank {
  font-size: 24px;
  font-weight: bold;
  width: 40px;
  text-align: center;
  color: #666;
}

.info {
  flex: 1;
}

.info h3 {
  margin: 0 0 5px 0;
  font-size: 16px;
}

.code {
  font-size: 12px;
  color: #999;
  font-weight: normal;
}

.details {
  display: flex;
  gap: 10px;
  align-items: center;
}

.price {
  font-size: 18px;
  font-weight: bold;
}

.change {
  font-size: 14px;
  padding: 2px 8px;
  border-radius: 4px;
}

.change.positive {
  color: #F44336;
  background: #FFEBEE;
}

.change.negative {
  color: #2196F3;
  background: #E3F2FD;
}

.metrics {
  display: flex;
  gap: 15px;
}

.metric {
  display: flex;
  flex-direction: column;
  text-align: right;
}

.metric .label {
  font-size: 11px;
  color: #999;
}

.metric .value {
  font-size: 13px;
  font-weight: bold;
  color: #333;
}
</style>
```

---

## 📊 투자 활용 전략

### 1. 52주 신고가 종목 활용
- ✅ **모멘텀 투자**: 상승 추세 지속 가능성
- ✅ **추가 상승 여력**: 신고가 돌파 후 추가 상승
- ⚠️ **과열 주의**: 단기 조정 가능성

### 2. 52주 신저가 종목 활용
- ✅ **가치 투자**: 저평가 매수 기회
- ✅ **반등 노리기**: 추세 반전 시그널 포착
- ⚠️ **하락 이유 분석**: 실적 악화인지 일시적인지 확인

### 3. 시가총액 상위 활용
- ✅ **안정적 투자**: 대형주 포트폴리오
- ✅ **장기 투자**: 블루칩 위주
- ✅ **배당 투자**: 대형주는 배당 안정적

### 4. 거래대금 상위 활용
- ✅ **단기 트레이딩**: 유동성 풍부
- ✅ **이슈주 파악**: 시장 관심 종목
- ⚠️ **변동성 주의**: 급등락 가능성

### 5. 급등/급락주 활용
- ✅ **단기 기회**: 모멘텀 포착
- ✅ **반대 매매**: 과매도/과매수 구간
- ⚠️ **리스크 관리**: 손절 라인 설정 필수

---

## 🔄 캐시 및 업데이트

- **캐시 시간**: 5분
- **자동 갱신**: 캐시 만료 후 첫 요청 시
- **데이터 소스**: 한국투자증권 API (실시간)
- **업데이트 주기**: 장 중 실시간, 장 마감 후 확정

---

## ⚠️ 주의사항

1. **투자 조언 아님**: 이 데이터는 투자 조언이 아닙니다
2. **실시간 데이터**: KIS API 상태에 따라 지연될 수 있음
3. **API 한도**: 일일 호출 한도 있음 (캐시로 관리)
4. **손실 책임**: 투자 손실은 투자자 본인 책임
5. **추가 분석 필요**: 재무제표, 뉴스 등 종합 검토 필수

---

## 🚀 확장 가능한 기능

### 향후 추가 예정
1. **배당수익률 상위**: 고배당주 순위
2. **PER/PBR 저평가**: 가치주 발굴
3. **ROE 상위**: 자기자본이익률 높은 종목
4. **외국인/기관 매수 상위**: 세력 자금 흐름
5. **신규 상장 종목**: IPO 정보
6. **테마별 순위**: 업종별, 테마별 분류

---

## 📚 참고 자료

- [한국투자증권 Open API](https://apiportal.koreainvestment.com/)
- [KIS Developers](https://securities.koreainvestment.com/)
- [주식 용어 사전](https://finance.naver.com/sise/help_invest.naver)

