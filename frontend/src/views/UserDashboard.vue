<template>
  <div class="page-container">
    <div class="page-content">
      <!-- 헤더 -->
      <header class="common-header">
        <h1>대시보드</h1>
        <div class="header-actions">
          <button @click="showWidgetSettings = true" class="btn-widget-settings" title="위젯 설정">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <circle cx="12" cy="12" r="3"/>
              <path d="M19.4 15a1.65 1.65 0 00.33 1.82l.06.06a2 2 0 010 2.83 2 2 0 01-2.83 0l-.06-.06a1.65 1.65 0 00-1.82-.33 1.65 1.65 0 00-1 1.51V21a2 2 0 01-2 2 2 2 0 01-2-2v-.09A1.65 1.65 0 009 19.4a1.65 1.65 0 00-1.82.33l-.06.06a2 2 0 01-2.83 0 2 2 0 010-2.83l.06-.06a1.65 1.65 0 00.33-1.82 1.65 1.65 0 00-1.51-1H3a2 2 0 01-2-2 2 2 0 012-2h.09A1.65 1.65 0 004.6 9a1.65 1.65 0 00-.33-1.82l-.06-.06a2 2 0 010-2.83 2 2 0 012.83 0l.06.06a1.65 1.65 0 001.82.33H9a1.65 1.65 0 001-1.51V3a2 2 0 012-2 2 2 0 012 2v.09a1.65 1.65 0 001 1.51 1.65 1.65 0 001.82-.33l.06-.06a2 2 0 012.83 0 2 2 0 010 2.83l-.06.06a1.65 1.65 0 00-.33 1.82V9a1.65 1.65 0 001.51 1H21a2 2 0 012 2 2 2 0 01-2 2h-.09a1.65 1.65 0 00-1.51 1z"/>
            </svg>
          </button>
          <div class="header-user">
            <div class="user-avatar">{{ username.charAt(0) }}</div>
            <span>{{ username }}</span>
          </div>
          <button @click="logout" class="btn btn-logout">로그아웃</button>
        </div>
      </header>

      <!-- 환영 메시지 -->
      <section class="welcome-card">
        <div class="welcome-content">
          <h2>환영합니다, <span class="highlight">{{ username }}</span>님!</h2>
          <p>플랫폼에 접속하셨습니다. 아래 메뉴에서 원하는 기능을 선택하세요.</p>
        </div>
        <div class="welcome-decoration">
          <svg viewBox="0 0 200 200" xmlns="http://www.w3.org/2000/svg">
            <path fill="rgba(102, 126, 234, 0.1)" d="M45.7,-51.9C59.1,-41.5,69.7,-26.2,71.8,-9.8C73.9,6.6,67.5,24.2,56.4,37.2C45.3,50.3,29.5,58.8,12.4,62.8C-4.7,66.8,-23.1,66.3,-38.4,58.5C-53.7,50.7,-65.9,35.6,-70.3,18.5C-74.7,1.4,-71.3,-17.7,-61.5,-32.1C-51.7,-46.5,-35.5,-56.2,-19.1,-65.1C-2.7,-74,14,-82.1,28.6,-77.3C43.2,-72.5,55.7,-54.8,45.7,-51.9Z" transform="translate(100 100)" />
          </svg>
        </div>
      </section>

      <!-- 메뉴 그리드 -->
      <section class="menu-grid">
        <article class="menu-card" @click="goToBoard">
          <div class="card-icon board">
            <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
              <path d="M14 2H6a2 2 0 00-2 2v16a2 2 0 002 2h12a2 2 0 002-2V8z"/>
              <polyline points="14,2 14,8 20,8"/>
              <line x1="16" y1="13" x2="8" y2="13"/>
              <line x1="16" y1="17" x2="8" y2="17"/>
              <polyline points="10,9 9,9 8,9"/>
            </svg>
          </div>
          <h3>게시판</h3>
          <p>자유롭게 글을 작성하고 파일을 공유할 수 있습니다.</p>
          <span class="card-arrow">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <polyline points="9,6 15,12 9,18"/>
            </svg>
          </span>
        </article>

        <article class="menu-card" @click="goToMyContent">
          <div class="card-icon content">
            <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
              <path d="M11 4H4a2 2 0 00-2 2v14a2 2 0 002 2h14a2 2 0 002-2v-7"/>
              <path d="M18.5 2.5a2.121 2.121 0 013 3L12 15l-4 1 1-4 9.5-9.5z"/>
            </svg>
          </div>
          <h3>내 콘텐츠</h3>
          <p>작성한 글과 파일을 확인하고 관리합니다.</p>
          <span class="card-arrow">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <polyline points="9,6 15,12 9,18"/>
            </svg>
          </span>
        </article>

        <article class="menu-card" @click="goToAsset">
          <div class="card-icon asset">
            <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
              <line x1="12" y1="1" x2="12" y2="23"/>
              <path d="M17 5H9.5a3.5 3.5 0 000 7h5a3.5 3.5 0 010 7H6"/>
            </svg>
          </div>
          <h3>자산 관리</h3>
          <p>보유한 금/은 자산을 관리하고 손익을 확인합니다.</p>
          <span class="card-arrow">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <polyline points="9,6 15,12 9,18"/>
            </svg>
          </span>
        </article>

        <article class="menu-card" @click="goToSettings">
          <div class="card-icon settings">
            <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
              <circle cx="12" cy="12" r="3"/>
              <path d="M19.4 15a1.65 1.65 0 00.33 1.82l.06.06a2 2 0 010 2.83 2 2 0 01-2.83 0l-.06-.06a1.65 1.65 0 00-1.82-.33 1.65 1.65 0 00-1 1.51V21a2 2 0 01-2 2 2 2 0 01-2-2v-.09A1.65 1.65 0 009 19.4a1.65 1.65 0 00-1.82.33l-.06.06a2 2 0 01-2.83 0 2 2 0 010-2.83l.06-.06a1.65 1.65 0 00.33-1.82 1.65 1.65 0 00-1.51-1H3a2 2 0 01-2-2 2 2 0 012-2h.09A1.65 1.65 0 004.6 9a1.65 1.65 0 00-.33-1.82l-.06-.06a2 2 0 010-2.83 2 2 0 012.83 0l.06.06a1.65 1.65 0 001.82.33H9a1.65 1.65 0 001-1.51V3a2 2 0 012-2 2 2 0 012 2v.09a1.65 1.65 0 001 1.51 1.65 1.65 0 001.82-.33l.06-.06a2 2 0 012.83 0 2 2 0 010 2.83l-.06.06a1.65 1.65 0 00-.33 1.82V9a1.65 1.65 0 001.51 1H21a2 2 0 012 2 2 2 0 01-2 2h-.09a1.65 1.65 0 00-1.51 1z"/>
            </svg>
          </div>
          <h3>내 설정</h3>
          <p>개인 정보 및 비밀번호를 관리합니다.</p>
          <span class="card-arrow">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <polyline points="9,6 15,12 9,18"/>
            </svg>
          </span>
        </article>

        <article class="menu-card gold" @click="goToGold">
          <div class="card-icon gold-icon">
            <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
              <circle cx="12" cy="12" r="10"/>
              <path d="M12 6v12"/>
              <path d="M15 9.5c0-1.5-1.5-2.5-3-2.5s-3 1-3 2.5c0 2 6 1 6 4 0 1.5-1.5 2.5-3 2.5s-3-1-3-2.5"/>
            </svg>
          </div>
          <h3>금 시세</h3>
          <p>실시간 금 시세 정보를 확인합니다.</p>
          <span class="card-arrow">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <polyline points="9,6 15,12 9,18"/>
            </svg>
          </span>
        </article>

        <article class="menu-card silver" @click="goToSilver">
          <div class="card-icon silver-icon">
            <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
              <circle cx="12" cy="12" r="10"/>
              <path d="M12 6v12"/>
              <path d="M15 9.5c0-1.5-1.5-2.5-3-2.5s-3 1-3 2.5c0 2 6 1 6 4 0 1.5-1.5 2.5-3 2.5s-3-1-3-2.5"/>
            </svg>
          </div>
          <h3>은 시세</h3>
          <p>실시간 은 시세 정보를 확인합니다.</p>
          <span class="card-arrow">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <polyline points="9,6 15,12 9,18"/>
            </svg>
          </span>
        </article>

        <article class="menu-card" @click="goToFiles">
          <div class="card-icon files">
            <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
              <path d="M22 19a2 2 0 01-2 2H4a2 2 0 01-2-2V5a2 2 0 012-2h5l2 3h9a2 2 0 012 2z"/>
            </svg>
          </div>
          <h3>내 파일</h3>
          <p>개인 파일과 폴더를 관리합니다.</p>
          <span class="card-arrow">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <polyline points="9,6 15,12 9,18"/>
            </svg>
          </span>
        </article>

        <article class="menu-card finance" @click="goToFinance">
          <div class="card-icon finance-icon">
            <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
              <rect x="3" y="4" width="18" height="18" rx="2" ry="2"/>
              <line x1="16" y1="2" x2="16" y2="6"/>
              <line x1="8" y1="2" x2="8" y2="6"/>
              <line x1="3" y1="10" x2="21" y2="10"/>
            </svg>
          </div>
          <h3>가계부</h3>
          <p>월별 수입과 지출을 기록하고 관리합니다.</p>
          <span class="card-arrow">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <polyline points="9,6 15,12 9,18"/>
            </svg>
          </span>
        </article>

        <article class="menu-card car" @click="goToCar">
          <div class="card-icon car-icon">
            <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
              <path d="M19 17h2c.6 0 1-.4 1-1v-3c0-.9-.7-1.7-1.5-1.9L18 10l-2.7-3.6c-.4-.5-1-.9-1.6-.9H10c-.7 0-1.3.4-1.6.9L5.5 10l-2 1.1C2.7 11.3 2 12.1 2 13v3c0 .6.4 1 1 1h2"/>
              <circle cx="7" cy="17" r="2"/>
              <circle cx="17" cy="17" r="2"/>
            </svg>
          </div>
          <h3>자동차 관리</h3>
          <p>정비 기록과 주행거리를 관리합니다.</p>
          <span class="card-arrow">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <polyline points="9,6 15,12 9,18"/>
            </svg>
          </span>
        </article>

        <article class="menu-card sector" @click="goToSector">
          <div class="card-icon sector-icon">
            <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
              <polyline points="22,7 13.5,15.5 8.5,10.5 2,17"/>
              <polyline points="16,7 22,7 22,13"/>
            </svg>
          </div>
          <h3>섹터별 거래대금</h3>
          <p>반도체, 2차전지, 로봇 등 섹터별 거래대금을 확인합니다.</p>
          <span class="card-arrow">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <polyline points="9,6 15,12 9,18"/>
            </svg>
          </span>
        </article>

        <article class="menu-card investor" @click="goToInvestor">
          <div class="card-icon investor-icon">
            <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
              <path d="M17 21v-2a4 4 0 00-4-4H5a4 4 0 00-4 4v2"/>
              <circle cx="9" cy="7" r="4"/>
              <path d="M23 21v-2a4 4 0 00-3-3.87"/>
              <path d="M16 3.13a4 4 0 010 7.75"/>
            </svg>
          </div>
          <h3>수급 탐지기</h3>
          <p>외국인/기관/프로그램 순매수를 추적하고 매수 신호를 포착합니다.</p>
          <span class="card-arrow">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <polyline points="9,6 15,12 9,18"/>
            </svg>
          </span>
        </article>

        <article class="menu-card supply-chart" @click="goToSupplyChart">
          <div class="card-icon supply-chart-icon">
            <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
              <circle cx="12" cy="12" r="10"/>
              <line x1="12" y1="8" x2="12" y2="12"/>
              <line x1="12" y1="12" x2="16" y2="14"/>
              <path d="M8 14l2-4 2 4 2-6"/>
            </svg>
          </div>
          <h3>수급 차트</h3>
          <p>"얘네 왜 이렇게 사지?" 종목 발견! 외국인/기관 순매수 순위와 이상 패턴을 탐지합니다.</p>
          <span class="card-arrow">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <polyline points="9,6 15,12 9,18"/>
            </svg>
          </span>
        </article>

        <article class="menu-card reddit" @click="goToReddit">
          <div class="card-icon reddit-icon">
            <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
              <circle cx="12" cy="12" r="10"/>
              <path d="M16.5 12a1.5 1.5 0 11-3 0 1.5 1.5 0 013 0z"/>
              <path d="M10.5 12a1.5 1.5 0 11-3 0 1.5 1.5 0 013 0z"/>
              <path d="M15 15.5c-1 1-2 1.5-3 1.5s-2-.5-3-1.5"/>
              <path d="M17 8l2-2M19 6a1 1 0 100-2 1 1 0 000 2z"/>
              <path d="M17.5 9c1.5 0 2.5 1 2.5 2"/>
            </svg>
          </div>
          <h3>Reddit 주식 정보</h3>
          <p>해외 주식 커뮤니티(WSB, stocks 등)의 실시간 트렌드와 인기 게시물을 확인합니다.</p>
          <span class="card-arrow">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <polyline points="9,6 15,12 9,18"/>
            </svg>
          </span>
        </article>
      </section>

      <!-- 시세 위젯 그리드 -->
      <section v-if="widgetSettings.goldPrice || widgetSettings.silverPrice || widgetSettings.assetSummary" class="price-widgets-grid">
        <!-- 금 시세 위젯 -->
        <div v-if="widgetSettings.goldPrice" class="price-widget gold-widget" @click="goToGold">
          <div class="widget-header">
            <div class="widget-icon gold">
              <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
                <circle cx="12" cy="12" r="10"/>
                <path d="M12 6v12"/>
                <path d="M15 9.5c0-1.5-1.5-2.5-3-2.5s-3 1-3 2.5c0 2 6 1 6 4 0 1.5-1.5 2.5-3 2.5s-3-1-3-2.5"/>
              </svg>
            </div>
            <span class="widget-label">금 시세</span>
          </div>
          <div v-if="goldPrice" class="widget-body">
            <div class="price-main">{{ formatCurrency(goldPrice.price) }}<small>/g</small></div>
            <div class="price-change" :class="goldPrice.changeRate >= 0 ? 'positive' : 'negative'">
              {{ goldPrice.changeRate >= 0 ? '+' : '' }}{{ goldPrice.changeRate?.toFixed(2) || 0 }}%
            </div>
          </div>
          <div v-else class="widget-body loading">
            <span v-if="loadingPrices">로딩 중...</span>
            <span v-else>데이터 없음</span>
          </div>
        </div>

        <!-- 은 시세 위젯 -->
        <div v-if="widgetSettings.silverPrice" class="price-widget silver-widget" @click="goToSilver">
          <div class="widget-header">
            <div class="widget-icon silver">
              <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
                <circle cx="12" cy="12" r="10"/>
                <path d="M12 6v12"/>
                <path d="M15 9.5c0-1.5-1.5-2.5-3-2.5s-3 1-3 2.5c0 2 6 1 6 4 0 1.5-1.5 2.5-3 2.5s-3-1-3-2.5"/>
              </svg>
            </div>
            <span class="widget-label">은 시세</span>
          </div>
          <div v-if="silverPrice" class="widget-body">
            <div class="price-main">{{ formatCurrency(silverPrice.price) }}<small>/g</small></div>
            <div class="price-change" :class="silverPrice.changeRate >= 0 ? 'positive' : 'negative'">
              {{ silverPrice.changeRate >= 0 ? '+' : '' }}{{ silverPrice.changeRate?.toFixed(2) || 0 }}%
            </div>
          </div>
          <div v-else class="widget-body loading">
            <span v-if="loadingPrices">로딩 중...</span>
            <span v-else>데이터 없음</span>
          </div>
        </div>

        <!-- 자산 요약 위젯 -->
        <div v-if="widgetSettings.assetSummary" class="price-widget asset-widget" @click="goToAsset">
          <div class="widget-header">
            <div class="widget-icon asset">
              <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
                <line x1="12" y1="1" x2="12" y2="23"/>
                <path d="M17 5H9.5a3.5 3.5 0 000 7h5a3.5 3.5 0 010 7H6"/>
              </svg>
            </div>
            <span class="widget-label">자산 요약</span>
          </div>
          <div class="widget-body">
            <div class="price-main">{{ formatCurrency(assetSummary.totalAssets) }}</div>
            <div class="price-change" :class="assetSummary.totalProfit >= 0 ? 'positive' : 'negative'">
              {{ assetSummary.totalProfit >= 0 ? '+' : '' }}{{ formatCurrency(assetSummary.totalProfit) }}
              ({{ assetSummary.profitRate >= 0 ? '+' : '' }}{{ assetSummary.profitRate?.toFixed(2) || 0 }}%)
            </div>
          </div>
        </div>
      </section>

      <!-- 오늘의 경제 뉴스 -->
      <section v-if="widgetSettings.news" class="news-section">
        <div class="news-header">
          <div class="news-title">
            <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
              <path d="M19 3H5c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2z"/>
              <path d="M7 7h10M7 11h10M7 15h7"/>
            </svg>
            <h3>오늘의 경제 뉴스</h3>
          </div>
          <div class="news-actions">
            <span class="news-badge">AI 요약</span>
            <button v-if="newsList.length === 0" @click="fetchNews" :disabled="fetchingNews" class="btn-fetch-news">
              <svg v-if="!fetchingNews" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <path d="M23 4v6h-6M1 20v-6h6"/>
                <path d="M3.51 9a9 9 0 0114.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0020.49 15"/>
              </svg>
              <span v-if="fetchingNews" class="loading-spinner"></span>
              {{ fetchingNews ? '수집 중...' : '뉴스 가져오기' }}
            </button>
          </div>
        </div>
        <div v-if="newsList.length > 0" class="news-list">
          <article v-for="news in newsList" :key="news.id" class="news-item" @click="openNewsUrl(news.sourceUrl)">
            <div class="news-content">
              <h4>{{ news.title }}</h4>
              <p>{{ news.summary }}</p>
              <div class="news-meta">
                <span class="news-source">{{ news.sourceName }}</span>
                <span class="news-time">{{ formatNewsTime(news.summarizedAt) }}</span>
              </div>
            </div>
            <div class="news-arrow">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <polyline points="9,6 15,12 9,18"/>
              </svg>
            </div>
          </article>
        </div>
        <div v-else class="news-empty">
          <div class="empty-icon">📰</div>
          <p>아직 수집된 뉴스가 없습니다.</p>
          <small>매일 아침 8시에 자동으로 수집되거나, 위 버튼을 눌러 수동으로 가져올 수 있습니다.</small>
        </div>
      </section>

      <!-- 가계부 요약 위젯 -->
      <section v-if="widgetSettings.financeSummary" class="finance-summary-widget">
        <div class="widget-header">
          <div class="widget-title">
            <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
              <rect x="3" y="4" width="18" height="18" rx="2" ry="2"/>
              <line x1="16" y1="2" x2="16" y2="6"/>
              <line x1="8" y1="2" x2="8" y2="6"/>
              <line x1="3" y1="10" x2="21" y2="10"/>
            </svg>
            <h3>이번 달 가계부</h3>
          </div>
          <button @click="goToFinance" class="btn-view-all">자세히 보기</button>
        </div>
        <div class="finance-summary-content">
          <div class="finance-stat income">
            <span class="stat-label">수입</span>
            <span class="stat-value">{{ formatCurrency(financeSummary.totalIncome) }}</span>
          </div>
          <div class="finance-stat expense">
            <span class="stat-label">지출</span>
            <span class="stat-value">{{ formatCurrency(financeSummary.totalExpense) }}</span>
          </div>
          <div class="finance-stat balance" :class="financeSummary.balance >= 0 ? 'positive' : 'negative'">
            <span class="stat-label">잔액</span>
            <span class="stat-value">{{ formatCurrency(financeSummary.balance) }}</span>
          </div>
        </div>
      </section>

      <!-- AI 상담 배너 -->
      <section class="ai-banner" @click="openAiChat">
        <div class="ai-banner-content">
          <div class="ai-icon">
            <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
              <path d="M12 2a2 2 0 012 2c0 .74-.4 1.39-1 1.73V7h1a7 7 0 017 7h1a1 1 0 011 1v3a1 1 0 01-1 1h-1v1a2 2 0 01-2 2H5a2 2 0 01-2-2v-1H2a1 1 0 01-1-1v-3a1 1 0 011-1h1a7 7 0 017-7h1V5.73c-.6-.34-1-.99-1-1.73a2 2 0 012-2z"/>
              <circle cx="8.5" cy="14.5" r="1.5"/>
              <circle cx="15.5" cy="14.5" r="1.5"/>
              <path d="M9 18h6"/>
            </svg>
          </div>
          <div class="ai-text">
            <h3>AI 재무 상담사</h3>
            <p>자산 관리, 가계부, 투자에 대한 맞춤형 상담을 받아보세요</p>
          </div>
          <div class="ai-arrow">
            <span>상담 시작</span>
            <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <polyline points="9,6 15,12 9,18"/>
            </svg>
          </div>
        </div>
        <div class="ai-decoration"></div>
      </section>

      <!-- 위젯 설정 모달 -->
      <WidgetSettingsModal
        :visible="showWidgetSettings"
        :settings="widgetSettings"
        @close="showWidgetSettings = false"
        @update:settings="updateWidgetSettings"
      />
    </div>
  </div>
</template>

<script>
import { newsAPI, financeAPI, goldAPI, silverAPI, assetAPI } from '../utils/api';
import WidgetSettingsModal from '../components/WidgetSettingsModal.vue';

export default {
  name: 'UserDashboard',
  components: {
    WidgetSettingsModal
  },
  data() {
    return {
      username: '',
      newsList: [],
      fetchingNews: false,
      showWidgetSettings: false,
      widgetSettings: {
        goldPrice: true,
        silverPrice: true,
        assetSummary: true,
        news: true,
        financeSummary: true
      },
      financeSummary: {
        totalIncome: 0,
        totalExpense: 0,
        balance: 0
      },
      goldPrice: null,
      silverPrice: null,
      assetSummary: {
        totalAssets: 0,
        totalProfit: 0,
        profitRate: 0
      },
      loadingPrices: false
    }
  },
  mounted() {
    this.username = localStorage.getItem('username') || 'User'
    this.loadWidgetSettings()
    this.loadNews()
    this.loadFinanceSummary()
    this.loadPrices()
    this.loadAssetSummary()
  },
  methods: {
    loadWidgetSettings() {
      const saved = localStorage.getItem('dashboardWidgets')
      if (saved) {
        this.widgetSettings = { ...this.widgetSettings, ...JSON.parse(saved) }
      }
    },
    updateWidgetSettings(settings) {
      this.widgetSettings = { ...this.widgetSettings, ...settings }
      // 위젯 데이터 다시 로드
      if (settings.goldPrice || settings.silverPrice) {
        this.loadPrices()
      }
      if (settings.assetSummary) {
        this.loadAssetSummary()
      }
      if (settings.financeSummary) {
        this.loadFinanceSummary()
      }
      if (settings.news) {
        this.loadNews()
      }
    },
    async loadFinanceSummary() {
      try {
        const now = new Date()
        const response = await financeAPI.getMonthlyTransactions(now.getFullYear(), now.getMonth() + 1)
        if (response.data.success) {
          const data = response.data.data
          this.financeSummary = {
            totalIncome: data.totalIncome || 0,
            totalExpense: data.totalExpense || 0,
            balance: data.balance || 0
          }
        }
      } catch (error) {
        console.error('가계부 요약 로드 실패:', error)
      }
    },
    async loadPrices() {
      this.loadingPrices = true
      try {
        const [goldRes, silverRes] = await Promise.all([
          goldAPI.getPrice(),
          silverAPI.getPrice()
        ])
        if (goldRes.data.success) {
          this.goldPrice = goldRes.data.data
        }
        if (silverRes.data.success) {
          this.silverPrice = silverRes.data.data
        }
      } catch (error) {
        console.error('시세 로드 실패:', error)
      } finally {
        this.loadingPrices = false
      }
    },
    async loadAssetSummary() {
      try {
        const response = await assetAPI.getAssetSummary()
        if (response.data.success) {
          const data = response.data.data
          this.assetSummary = {
            totalAssets: data.totalCurrentValue || 0,
            totalProfit: data.totalProfit || 0,
            profitRate: data.profitRate || 0
          }
        }
      } catch (error) {
        console.error('자산 요약 로드 실패:', error)
      }
    },
    formatCurrency(value) {
      if (!value) return '0원'
      return new Intl.NumberFormat('ko-KR').format(value) + '원'
    },
    async loadNews() {
      try {
        // 오늘 뉴스가 없으면 최근 뉴스 조회
        let response = await newsAPI.getTodayNews()
        if (response.data.data && response.data.data.length > 0) {
          this.newsList = response.data.data.slice(0, 5)
        } else {
          response = await newsAPI.getRecentNews()
          this.newsList = response.data.data ? response.data.data.slice(0, 5) : []
        }
      } catch (error) {
        console.error('뉴스 로드 실패:', error)
        this.newsList = []
      }
    },
    openNewsUrl(url) {
      if (url) {
        window.open(url, '_blank')
      }
    },
    formatNewsTime(dateStr) {
      if (!dateStr) return ''
      const date = new Date(dateStr)
      const now = new Date()
      const diffHours = Math.floor((now - date) / (1000 * 60 * 60))
      if (diffHours < 1) return '방금 전'
      if (diffHours < 24) return `${diffHours}시간 전`
      return date.toLocaleDateString('ko-KR')
    },
    async fetchNews() {
      this.fetchingNews = true
      try {
        await newsAPI.fetchNews()
        await this.loadNews()
      } catch (error) {
        console.error('뉴스 수집 실패:', error)
        alert('뉴스 수집에 실패했습니다. AI 서버(Ollama)가 실행 중인지 확인해주세요.')
      } finally {
        this.fetchingNews = false
      }
    },
    goToBoard() {
      this.$router.push('/board')
    },
    goToMyContent() {
      this.$router.push('/my-content')
    },
    goToAsset() {
      this.$router.push('/asset')
    },
    goToSettings() {
      this.$router.push('/settings')
    },
    goToGold() {
      this.$router.push('/gold')
    },
    goToSilver() {
      this.$router.push('/silver')
    },
    goToFiles() {
      this.$router.push('/files')
    },
    goToFinance() {
      this.$router.push('/finance')
    },
    goToCar() {
      this.$router.push('/car')
    },
    goToSector() {
      this.$router.push('/sector')
    },
    goToInvestor() {
      this.$router.push('/investor')
    },
    goToSupplyChart() {
      this.$router.push('/supply-chart')
    },
    goToReddit() {
      this.$router.push('/reddit')
    },
    openAiChat() {
      // 챗봇 열기 이벤트 발생
      window.dispatchEvent(new CustomEvent('open-chatbot'))
    },
    logout() {
      localStorage.removeItem('jwt_token')
      localStorage.removeItem('username')
      localStorage.removeItem('role')
      this.$router.push('/login')
    }
  }
}
</script>

<style scoped>
/* 환영 카드 */
.welcome-card {
  background: rgba(255, 255, 255, 0.95);
  backdrop-filter: blur(20px);
  border-radius: 20px;
  padding: var(--card-padding);
  margin-bottom: var(--section-gap);
  box-shadow: 0 10px 40px rgba(0, 0, 0, 0.1);
  display: flex;
  justify-content: space-between;
  align-items: center;
  overflow: hidden;
  position: relative;
}

.welcome-content {
  position: relative;
  z-index: 1;
}

.welcome-content h2 {
  font-size: 28px;
  color: var(--text-primary);
  margin: 0 0 12px 0;
}

.welcome-content .highlight {
  background: var(--primary-gradient);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  background-clip: text;
}

.welcome-content p {
  font-size: 16px;
  color: var(--text-muted);
  margin: 0;
}

.welcome-decoration {
  position: absolute;
  right: -50px;
  top: -50px;
  width: 300px;
  height: 300px;
  opacity: 0.5;
}

/* 사용자 아바타 */
.user-avatar {
  width: 36px;
  height: 36px;
  background: var(--primary-gradient);
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: white;
  font-weight: 700;
  font-size: 16px;
}

/* 메뉴 그리드 */
.menu-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
  gap: 20px;
  margin-bottom: var(--section-gap);
}

.menu-card {
  background: rgba(255, 255, 255, 0.95);
  backdrop-filter: blur(20px);
  border-radius: 20px;
  padding: var(--card-padding);
  cursor: pointer;
  transition: all 0.3s ease;
  border: 2px solid transparent;
  position: relative;
  overflow: hidden;
}

.menu-card:hover {
  transform: translateY(-6px);
  box-shadow: 0 20px 40px rgba(0, 0, 0, 0.15);
  border-color: var(--primary-start);
}

.menu-card:hover .card-arrow {
  opacity: 1;
  transform: translateX(0);
}

.card-icon {
  width: 60px;
  height: 60px;
  border-radius: 16px;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 20px;
  transition: transform 0.3s ease;
}

.menu-card:hover .card-icon {
  transform: scale(1.1);
}

.card-icon.board {
  background: linear-gradient(135deg, rgba(102, 126, 234, 0.15) 0%, rgba(118, 75, 162, 0.15) 100%);
  color: var(--primary-start);
}

.card-icon.content {
  background: linear-gradient(135deg, rgba(79, 172, 254, 0.15) 0%, rgba(0, 242, 254, 0.15) 100%);
  color: #4facfe;
}

.card-icon.asset {
  background: linear-gradient(135deg, rgba(247, 183, 51, 0.15) 0%, rgba(252, 74, 26, 0.15) 100%);
  color: #f7b733;
}

.card-icon.settings {
  background: linear-gradient(135deg, rgba(108, 117, 125, 0.15) 0%, rgba(73, 80, 87, 0.15) 100%);
  color: #6c757d;
}

.card-icon.gold-icon {
  background: linear-gradient(135deg, rgba(255, 215, 0, 0.2) 0%, rgba(218, 165, 32, 0.2) 100%);
  color: #daa520;
}

.card-icon.silver-icon {
  background: linear-gradient(135deg, rgba(192, 192, 192, 0.2) 0%, rgba(128, 128, 128, 0.2) 100%);
  color: #808080;
}

.card-icon.files {
  background: linear-gradient(135deg, rgba(93, 173, 226, 0.15) 0%, rgba(52, 152, 219, 0.15) 100%);
  color: #3498db;
}

.card-icon.finance-icon {
  background: linear-gradient(135deg, rgba(46, 204, 113, 0.15) 0%, rgba(26, 188, 156, 0.15) 100%);
  color: #2ecc71;
}

.card-icon.car-icon {
  background: linear-gradient(135deg, rgba(52, 73, 94, 0.15) 0%, rgba(44, 62, 80, 0.15) 100%);
  color: #34495e;
}

.card-icon.sector-icon {
  background: linear-gradient(135deg, rgba(79, 70, 229, 0.15) 0%, rgba(139, 92, 246, 0.15) 100%);
  color: #4F46E5;
}

.card-icon.investor-icon {
  background: linear-gradient(135deg, rgba(16, 185, 129, 0.15) 0%, rgba(5, 150, 105, 0.15) 100%);
  color: #10B981;
}

.menu-card.car {
  background: linear-gradient(135deg, rgba(245, 247, 250, 0.95) 0%, rgba(255, 255, 255, 0.95) 100%);
  border: 2px solid rgba(52, 73, 94, 0.2);
}

.menu-card.car:hover {
  border-color: #34495e;
  box-shadow: 0 20px 40px rgba(52, 73, 94, 0.15);
}

.menu-card.car h3 {
  color: #2c3e50;
}

.menu-card.sector {
  background: linear-gradient(135deg, rgba(238, 242, 255, 0.95) 0%, rgba(255, 255, 255, 0.95) 100%);
  border: 2px solid rgba(79, 70, 229, 0.2);
}

.menu-card.sector:hover {
  border-color: #4F46E5;
  box-shadow: 0 20px 40px rgba(79, 70, 229, 0.15);
}

.menu-card.sector h3 {
  color: #4F46E5;
}

.menu-card.investor {
  background: linear-gradient(135deg, rgba(236, 253, 245, 0.95) 0%, rgba(255, 255, 255, 0.95) 100%);
  border: 2px solid rgba(16, 185, 129, 0.2);
}

.menu-card.investor:hover {
  border-color: #10B981;
  box-shadow: 0 20px 40px rgba(16, 185, 129, 0.15);
}

.menu-card.investor h3 {
  color: #059669;
}

.card-icon.supply-chart-icon {
  background: linear-gradient(135deg, rgba(239, 68, 68, 0.15) 0%, rgba(220, 38, 38, 0.15) 100%);
  color: #EF4444;
}

.menu-card.supply-chart {
  background: linear-gradient(135deg, rgba(254, 242, 242, 0.95) 0%, rgba(255, 255, 255, 0.95) 100%);
  border: 2px solid rgba(239, 68, 68, 0.2);
}

.menu-card.supply-chart:hover {
  border-color: #EF4444;
  box-shadow: 0 20px 40px rgba(239, 68, 68, 0.15);
}

.menu-card.supply-chart h3 {
  color: #DC2626;
}

.card-icon.reddit-icon {
  background: linear-gradient(135deg, rgba(255, 69, 0, 0.15) 0%, rgba(255, 87, 34, 0.15) 100%);
  color: #FF4500;
}

.menu-card.reddit {
  background: linear-gradient(135deg, rgba(255, 247, 237, 0.95) 0%, rgba(255, 255, 255, 0.95) 100%);
  border: 2px solid rgba(255, 69, 0, 0.2);
}

.menu-card.reddit:hover {
  border-color: #FF4500;
  box-shadow: 0 20px 40px rgba(255, 69, 0, 0.15);
}

.menu-card.reddit h3 {
  color: #EA580C;
}

.menu-card h3 {
  font-size: 20px;
  color: var(--text-primary);
  margin: 0 0 10px 0;
  font-weight: 600;
}

.menu-card p {
  font-size: 14px;
  color: var(--text-muted);
  margin: 0;
  line-height: 1.5;
}

.card-arrow {
  position: absolute;
  right: 24px;
  top: 50%;
  transform: translateX(10px) translateY(-50%);
  opacity: 0;
  transition: all 0.3s ease;
  color: var(--primary-start);
}

/* 금/은 특별 스타일 */
.menu-card.gold {
  background: linear-gradient(135deg, rgba(255, 249, 230, 0.95) 0%, rgba(255, 255, 255, 0.95) 100%);
  border: 2px solid rgba(255, 215, 0, 0.3);
}

.menu-card.gold:hover {
  border-color: #ffd700;
  box-shadow: 0 20px 40px rgba(255, 215, 0, 0.2);
}

.menu-card.gold h3 {
  color: #b8860b;
}

.menu-card.silver {
  background: linear-gradient(135deg, rgba(248, 249, 250, 0.95) 0%, rgba(255, 255, 255, 0.95) 100%);
  border: 2px solid rgba(192, 192, 192, 0.3);
}

.menu-card.silver:hover {
  border-color: #c0c0c0;
  box-shadow: 0 20px 40px rgba(128, 128, 128, 0.2);
}

.menu-card.silver h3 {
  color: #708090;
}

/* 뉴스 섹션 */
.news-section {
  background: rgba(255, 255, 255, 0.95);
  backdrop-filter: blur(20px);
  border-radius: 20px;
  padding: var(--card-padding);
  margin-bottom: var(--section-gap);
  box-shadow: 0 10px 40px rgba(0, 0, 0, 0.1);
}

.news-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 20px;
}

.news-title {
  display: flex;
  align-items: center;
  gap: 12px;
  color: var(--text-primary);
}

.news-title svg {
  color: var(--primary-start);
}

.news-title h3 {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
}

.news-badge {
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  color: white;
  padding: 6px 14px;
  border-radius: 20px;
  font-size: 12px;
  font-weight: 600;
}

.news-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.news-item {
  display: flex;
  align-items: flex-start;
  gap: 16px;
  padding: 16px 20px;
  background: linear-gradient(135deg, #f8f9fa 0%, #fff 100%);
  border-radius: 14px;
  cursor: pointer;
  transition: all 0.2s ease;
  border: 1px solid var(--border-light);
}

.news-item:hover {
  border-color: var(--primary-start);
  box-shadow: 0 4px 15px rgba(102, 126, 234, 0.1);
  transform: translateX(4px);
}

.news-content {
  flex: 1;
  min-width: 0;
}

.news-content h4 {
  margin: 0 0 8px 0;
  font-size: 15px;
  font-weight: 600;
  color: var(--text-primary);
  line-height: 1.4;
  display: -webkit-box;
  -webkit-line-clamp: 1;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.news-content p {
  margin: 0 0 10px 0;
  font-size: 13px;
  color: var(--text-muted);
  line-height: 1.6;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.news-meta {
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 12px;
}

.news-source {
  color: var(--primary-start);
  font-weight: 500;
}

.news-time {
  color: #adb5bd;
}

.news-arrow {
  color: #ced4da;
  transition: color 0.2s;
  flex-shrink: 0;
  padding-top: 4px;
}

.news-item:hover .news-arrow {
  color: var(--primary-start);
}

.news-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}

.btn-fetch-news {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 16px;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  color: white;
  border: none;
  border-radius: 8px;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.2s;
}

.btn-fetch-news:hover:not(:disabled) {
  transform: translateY(-2px);
  box-shadow: 0 4px 12px rgba(102, 126, 234, 0.3);
}

.btn-fetch-news:disabled {
  opacity: 0.7;
  cursor: not-allowed;
}

.loading-spinner {
  width: 14px;
  height: 14px;
  border: 2px solid rgba(255, 255, 255, 0.3);
  border-top-color: white;
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.news-empty {
  text-align: center;
  padding: 40px 20px;
  color: var(--text-muted);
}

.news-empty .empty-icon {
  font-size: 48px;
  margin-bottom: 12px;
}

.news-empty p {
  margin: 0 0 8px 0;
  font-size: 15px;
  color: var(--text-primary);
}

.news-empty small {
  font-size: 13px;
  color: #adb5bd;
}

/* AI 상담 배너 */
.ai-banner {
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  border-radius: 20px;
  padding: 32px 40px;
  cursor: pointer;
  transition: all 0.3s ease;
  position: relative;
  overflow: hidden;
  box-shadow: 0 10px 40px rgba(102, 126, 234, 0.3);
}

.ai-banner:hover {
  transform: translateY(-4px);
  box-shadow: 0 20px 50px rgba(102, 126, 234, 0.4);
}

.ai-banner:hover .ai-arrow svg {
  transform: translateX(4px);
}

.ai-banner-content {
  display: flex;
  align-items: center;
  gap: 24px;
  position: relative;
  z-index: 1;
}

.ai-icon {
  width: 80px;
  height: 80px;
  background: rgba(255, 255, 255, 0.2);
  border-radius: 20px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: white;
  flex-shrink: 0;
}

.ai-text {
  flex: 1;
}

.ai-text h3 {
  font-size: 24px;
  color: white;
  margin: 0 0 8px 0;
  font-weight: 700;
}

.ai-text p {
  font-size: 15px;
  color: rgba(255, 255, 255, 0.85);
  margin: 0;
}

.ai-arrow {
  display: flex;
  align-items: center;
  gap: 8px;
  background: rgba(255, 255, 255, 0.2);
  padding: 12px 20px;
  border-radius: 12px;
  color: white;
  font-weight: 600;
  font-size: 15px;
  transition: all 0.3s ease;
}

.ai-arrow:hover {
  background: rgba(255, 255, 255, 0.3);
}

.ai-arrow svg {
  transition: transform 0.3s ease;
}

.ai-decoration {
  position: absolute;
  right: -100px;
  top: -100px;
  width: 300px;
  height: 300px;
  background: rgba(255, 255, 255, 0.1);
  border-radius: 50%;
}

.ai-decoration::after {
  content: '';
  position: absolute;
  right: 150px;
  bottom: -50px;
  width: 200px;
  height: 200px;
  background: rgba(255, 255, 255, 0.05);
  border-radius: 50%;
}

/* 반응형 */
@media (max-width: 768px) {
  .welcome-card {
    padding: var(--card-padding);
  }

  .welcome-content h2 {
    font-size: 22px;
  }

  .welcome-decoration {
    display: none;
  }

  .menu-grid {
    grid-template-columns: 1fr;
  }

  .ai-banner {
    padding: 24px;
  }

  .ai-banner-content {
    flex-direction: column;
    text-align: center;
    gap: 16px;
  }

  .ai-icon {
    width: 64px;
    height: 64px;
  }

  .ai-icon svg {
    width: 36px;
    height: 36px;
  }

  .ai-text h3 {
    font-size: 20px;
  }

  .ai-text p {
    font-size: 14px;
  }

  .ai-arrow {
    width: 100%;
    justify-content: center;
  }

  .ai-decoration {
    display: none;
  }

  .news-section {
    padding: 20px;
  }

  .news-header {
    flex-direction: column;
    align-items: flex-start;
    gap: 12px;
  }

  .news-item {
    padding: 14px 16px;
  }

  .news-content h4 {
    font-size: 14px;
  }

  .news-content p {
    font-size: 12px;
    -webkit-line-clamp: 3;
  }
}

/* 위젯 설정 버튼 */
.btn-widget-settings {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  border: none;
  border-radius: 12px;
  background: linear-gradient(135deg, #f8f9fa 0%, #e9ecef 100%);
  color: var(--text-secondary);
  cursor: pointer;
  transition: all 0.3s ease;
}

.btn-widget-settings:hover {
  transform: scale(1.05);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.15);
  color: var(--primary-start);
}

[data-theme="dark"] .btn-widget-settings {
  background: linear-gradient(135deg, #27272a 0%, #1f1f23 100%);
  color: var(--text-secondary);
}

[data-theme="dark"] .btn-widget-settings:hover {
  color: var(--primary-start);
}

/* 가계부 요약 위젯 */
.finance-summary-widget {
  background: rgba(255, 255, 255, 0.95);
  backdrop-filter: blur(20px);
  border-radius: 20px;
  padding: var(--card-padding);
  margin-bottom: var(--section-gap);
  box-shadow: 0 10px 40px rgba(0, 0, 0, 0.1);
}

[data-theme="dark"] .finance-summary-widget {
  background: var(--card-bg);
}

.widget-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 20px;
}

.widget-title {
  display: flex;
  align-items: center;
  gap: 12px;
  color: var(--text-primary);
}

.widget-title svg {
  color: #2ecc71;
}

.widget-title h3 {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
}

.btn-view-all {
  background: linear-gradient(135deg, #f8f9fa 0%, #e9ecef 100%);
  border: none;
  padding: 8px 16px;
  border-radius: 8px;
  color: var(--text-secondary);
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.2s;
}

.btn-view-all:hover {
  background: var(--primary-gradient);
  color: white;
}

[data-theme="dark"] .btn-view-all {
  background: linear-gradient(135deg, #27272a 0%, #1f1f23 100%);
}

.finance-summary-content {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 16px;
}

.finance-stat {
  background: linear-gradient(135deg, #f8f9fa 0%, #fff 100%);
  border-radius: 14px;
  padding: 20px;
  text-align: center;
  border-left: 4px solid transparent;
}

[data-theme="dark"] .finance-stat {
  background: linear-gradient(135deg, #27272a 0%, #1f1f23 100%);
}

.finance-stat.income {
  border-left-color: #4caf50;
}

.finance-stat.expense {
  border-left-color: #f44336;
}

.finance-stat.balance {
  border-left-color: var(--primary-start);
}

.finance-stat.balance.positive {
  border-left-color: #4caf50;
}

.finance-stat.balance.negative {
  border-left-color: #f44336;
}

.finance-stat .stat-label {
  display: block;
  font-size: 13px;
  color: var(--text-muted);
  margin-bottom: 8px;
}

.finance-stat .stat-value {
  font-size: 18px;
  font-weight: 700;
}

.finance-stat.income .stat-value {
  color: #4caf50;
}

.finance-stat.expense .stat-value {
  color: #f44336;
}

.finance-stat.balance .stat-value {
  color: var(--primary-start);
}

.finance-stat.balance.positive .stat-value {
  color: #4caf50;
}

.finance-stat.balance.negative .stat-value {
  color: #f44336;
}

@media (max-width: 768px) {
  .finance-summary-content {
    grid-template-columns: 1fr;
  }

  .finance-stat {
    padding: 16px;
  }
}

/* 시세 위젯 그리드 */
.price-widgets-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
  gap: 20px;
  margin-bottom: var(--section-gap);
}

.price-widget {
  background: rgba(255, 255, 255, 0.95);
  backdrop-filter: blur(20px);
  border-radius: 20px;
  padding: 24px;
  box-shadow: 0 10px 40px rgba(0, 0, 0, 0.1);
  cursor: pointer;
  transition: all 0.3s ease;
  border: 2px solid transparent;
}

.price-widget:hover {
  transform: translateY(-4px);
  box-shadow: 0 20px 40px rgba(0, 0, 0, 0.15);
}

.price-widget .widget-header {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
}

.price-widget .widget-icon {
  width: 48px;
  height: 48px;
  border-radius: 14px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.price-widget .widget-icon.gold {
  background: linear-gradient(135deg, rgba(255, 215, 0, 0.2) 0%, rgba(218, 165, 32, 0.2) 100%);
  color: #daa520;
}

.price-widget .widget-icon.silver {
  background: linear-gradient(135deg, rgba(192, 192, 192, 0.2) 0%, rgba(128, 128, 128, 0.2) 100%);
  color: #708090;
}

.price-widget .widget-icon.asset {
  background: linear-gradient(135deg, rgba(247, 183, 51, 0.15) 0%, rgba(252, 74, 26, 0.15) 100%);
  color: #f7b733;
}

.price-widget .widget-label {
  font-size: 16px;
  font-weight: 600;
  color: #374151;
}

.price-widget .widget-body {
  padding-left: 60px;
}

.price-widget .widget-body.loading {
  color: #9ca3af;
  font-size: 14px;
}

.price-widget .price-main {
  font-size: 28px;
  font-weight: 700;
  color: #1f2937;
  margin-bottom: 4px;
}

.price-widget .price-main small {
  font-size: 14px;
  font-weight: 400;
  color: #6b7280;
}

.price-widget .price-change {
  font-size: 14px;
  font-weight: 600;
}

.price-widget .price-change.positive {
  color: #ef4444;
}

.price-widget .price-change.negative {
  color: #3b82f6;
}

.gold-widget {
  border-color: rgba(255, 215, 0, 0.3);
}

.gold-widget:hover {
  border-color: #ffd700;
}

.silver-widget {
  border-color: rgba(192, 192, 192, 0.3);
}

.silver-widget:hover {
  border-color: #c0c0c0;
}

.asset-widget {
  border-color: rgba(247, 183, 51, 0.3);
}

.asset-widget:hover {
  border-color: #f7b733;
}

[data-theme="dark"] .price-widget {
  background: var(--card-bg);
}

[data-theme="dark"] .price-widget .widget-label {
  color: var(--text-primary);
}

[data-theme="dark"] .price-widget .price-main {
  color: var(--text-primary);
}

@media (max-width: 768px) {
  .price-widgets-grid {
    grid-template-columns: 1fr;
  }

  .price-widget .widget-body {
    padding-left: 0;
    margin-top: 12px;
  }

  .price-widget .price-main {
    font-size: 24px;
  }
}
</style>
