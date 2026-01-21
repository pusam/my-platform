<template>
  <div class="chatbot-container">
    <!-- 플로팅 버튼 -->
    <button
      v-if="!isOpen"
      @click="openChat"
      class="chat-toggle-btn"
    >
      <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
        <path d="M21 15a2 2 0 01-2 2H7l-4 4V5a2 2 0 012-2h14a2 2 0 012 2z"/>
      </svg>
    </button>

    <!-- 채팅창 -->
    <transition name="chat-slide">
      <div v-if="isOpen" class="chat-window">
        <!-- 헤더 -->
        <div class="chat-header">
          <div class="header-info">
            <div class="bot-avatar">
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <circle cx="12" cy="12" r="10"/>
                <path d="M8 14s1.5 2 4 2 4-2 4-2"/>
                <line x1="9" y1="9" x2="9.01" y2="9"/>
                <line x1="15" y1="9" x2="15.01" y2="9"/>
              </svg>
            </div>
            <div class="bot-info">
              <span class="bot-name">AI 상담사</span>
              <span class="bot-status" :class="{ offline: !aiAvailable }">
                {{ aiAvailable ? '온라인' : '오프라인' }}
              </span>
            </div>
          </div>
          <button @click="closeChat" class="close-btn">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <line x1="18" y1="6" x2="6" y2="18"/>
              <line x1="6" y1="6" x2="18" y2="18"/>
            </svg>
          </button>
        </div>

        <!-- 맞춤 상담 토글 -->
        <div class="context-toggle">
          <label class="toggle-label">
            <input type="checkbox" v-model="useContext" />
            <span class="toggle-switch"></span>
            <span class="toggle-text">맞춤 상담 (내 자산/가계부 데이터 활용)</span>
          </label>
        </div>

        <!-- 메시지 영역 -->
        <div class="chat-messages" ref="messagesContainer">
          <div
            v-for="(msg, index) in messages"
            :key="index"
            class="message"
            :class="msg.sender"
          >
            <div class="message-bubble">
              <p v-html="formatMessage(msg.text)"></p>
              <span class="message-time">{{ msg.time }}</span>
            </div>
          </div>

          <!-- 타이핑 인디케이터 -->
          <div v-if="isTyping" class="message bot">
            <div class="message-bubble typing">
              <span></span>
              <span></span>
              <span></span>
            </div>
          </div>
        </div>

        <!-- 빠른 응답 버튼 -->
        <div v-if="showQuickReplies && !isTyping" class="quick-replies">
          <button
            v-for="(reply, index) in quickReplies"
            :key="index"
            @click="sendQuickReply(reply)"
            class="quick-reply-btn"
          >
            {{ reply }}
          </button>
        </div>

        <!-- 입력 영역 -->
        <div class="chat-input">
          <input
            v-model="inputMessage"
            @keyup.enter="sendMessage"
            type="text"
            placeholder="메시지를 입력하세요..."
            :disabled="isTyping"
          />
          <button @click="sendMessage" :disabled="!inputMessage.trim() || isTyping">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <line x1="22" y1="2" x2="11" y2="13"/>
              <polygon points="22 2 15 22 11 13 2 9 22 2"/>
            </svg>
          </button>
        </div>
      </div>
    </transition>
  </div>
</template>

<script setup>
import { ref, nextTick, onMounted } from 'vue';
import { aiAPI } from '../utils/api';

const isOpen = ref(false);
const inputMessage = ref('');
const messages = ref([]);
const isTyping = ref(false);
const showQuickReplies = ref(true);
const messagesContainer = ref(null);
const useContext = ref(false);
const aiAvailable = ref(true);

const quickReplies = ref([
  '자산 현황 분석해줘',
  '이번 달 지출 분석',
  '절약 팁 알려줘'
]);

const getCurrentTime = () => {
  const now = new Date();
  return `${now.getHours().toString().padStart(2, '0')}:${now.getMinutes().toString().padStart(2, '0')}`;
};

const formatMessage = (text) => {
  // 줄바꿈을 <br>로 변환
  return text.replace(/\n/g, '<br>');
};

const scrollToBottom = async () => {
  await nextTick();
  if (messagesContainer.value) {
    messagesContainer.value.scrollTop = messagesContainer.value.scrollHeight;
  }
};

const checkAiStatus = async () => {
  try {
    const response = await aiAPI.checkStatus();
    aiAvailable.value = response.data.data === true;
  } catch (e) {
    // 에러 발생 시 (401 포함) AI를 사용할 수 없는 것으로 처리
    aiAvailable.value = false;
    // 콘솔에 에러를 출력하지 않음 (불필요한 로그 방지)
  }
};

const openChat = () => {
  isOpen.value = true;
  checkAiStatus();

  if (messages.value.length === 0) {
    setTimeout(() => {
      messages.value.push({
        sender: 'bot',
        text: '안녕하세요! AI 상담사입니다.\n무엇이든 물어보세요. 자산 관리, 가계부 분석, 재무 조언 등을 도와드릴 수 있습니다.\n\n"맞춤 상담"을 켜면 회원님의 실제 데이터를 기반으로 분석해드립니다.',
        time: getCurrentTime()
      });
      scrollToBottom();
    }, 300);
  }
};

const closeChat = () => {
  isOpen.value = false;
};

const sendMessage = () => {
  if (!inputMessage.value.trim() || isTyping.value) return;

  const userMessage = inputMessage.value.trim();

  messages.value.push({
    sender: 'user',
    text: userMessage,
    time: getCurrentTime()
  });

  inputMessage.value = '';
  showQuickReplies.value = false;
  scrollToBottom();

  getBotResponse(userMessage);
};

const sendQuickReply = (reply) => {
  inputMessage.value = reply;
  sendMessage();
};

const getBotResponse = async (userMessage) => {
  isTyping.value = true;
  scrollToBottom();

  try {
    const response = await aiAPI.chat(userMessage, useContext.value);

    isTyping.value = false;

    if (response.data.data && response.data.data.success) {
      messages.value.push({
        sender: 'bot',
        text: response.data.data.message,
        time: getCurrentTime()
      });
    } else {
      messages.value.push({
        sender: 'bot',
        text: 'AI 응답을 받지 못했습니다. 다시 시도해주세요.',
        time: getCurrentTime()
      });
    }

    // 빠른 응답 버튼 업데이트
    updateQuickReplies(userMessage);
    showQuickReplies.value = true;

  } catch (error) {
    isTyping.value = false;
    console.error('AI 채팅 오류:', error);

    let errorMessage = 'AI 서버에 연결할 수 없습니다.';
    if (error.code === 'ECONNABORTED') {
      errorMessage = '응답 시간이 초과되었습니다. 다시 시도해주세요.';
    }

    messages.value.push({
      sender: 'bot',
      text: errorMessage,
      time: getCurrentTime()
    });

    showQuickReplies.value = true;
  }

  scrollToBottom();
};

const updateQuickReplies = (lastMessage) => {
  // 마지막 질문에 따라 관련 빠른 응답 제안
  const lowerMessage = lastMessage.toLowerCase();

  if (lowerMessage.includes('자산') || lowerMessage.includes('투자')) {
    quickReplies.value = ['포트폴리오 조언', '위험 분산 방법', '다른 질문하기'];
  } else if (lowerMessage.includes('지출') || lowerMessage.includes('가계부')) {
    quickReplies.value = ['절약 방법 추천', '예산 설정 도움', '다른 질문하기'];
  } else if (lowerMessage.includes('저축') || lowerMessage.includes('절약')) {
    quickReplies.value = ['저축 목표 설정', '지출 줄이는 법', '다른 질문하기'];
  } else {
    quickReplies.value = ['자산 현황 분석해줘', '이번 달 지출 분석', '재무 조언 부탁해'];
  }
};

onMounted(() => {
  checkAiStatus();
});
</script>

<style scoped>
.chatbot-container {
  position: fixed;
  bottom: 24px;
  right: 24px;
  z-index: 9999;
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
}

/* 플로팅 버튼 */
.chat-toggle-btn {
  width: 60px;
  height: 60px;
  border-radius: 50%;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  border: none;
  color: white;
  cursor: pointer;
  box-shadow: 0 4px 20px rgba(102, 126, 234, 0.4);
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.3s ease;
}

.chat-toggle-btn:hover {
  transform: scale(1.1);
  box-shadow: 0 6px 30px rgba(102, 126, 234, 0.5);
}

/* 채팅창 */
.chat-window {
  width: 380px;
  height: 600px;
  background: white;
  border-radius: 20px;
  box-shadow: 0 10px 40px rgba(0, 0, 0, 0.15);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

/* 애니메이션 */
.chat-slide-enter-active,
.chat-slide-leave-active {
  transition: all 0.3s ease;
}

.chat-slide-enter-from,
.chat-slide-leave-to {
  opacity: 0;
  transform: translateY(20px) scale(0.95);
}

/* 헤더 */
.chat-header {
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  color: white;
  padding: 16px 20px;
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.header-info {
  display: flex;
  align-items: center;
  gap: 12px;
}

.bot-avatar {
  width: 40px;
  height: 40px;
  background: rgba(255, 255, 255, 0.2);
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
}

.bot-info {
  display: flex;
  flex-direction: column;
}

.bot-name {
  font-weight: 600;
  font-size: 15px;
}

.bot-status {
  font-size: 12px;
  opacity: 0.9;
}

.bot-status.offline {
  color: #ffcc00;
}

.close-btn {
  background: rgba(255, 255, 255, 0.2);
  border: none;
  color: white;
  width: 32px;
  height: 32px;
  border-radius: 8px;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: background 0.2s;
}

.close-btn:hover {
  background: rgba(255, 255, 255, 0.3);
}

/* 맞춤 상담 토글 */
.context-toggle {
  padding: 10px 16px;
  background: #f0f4ff;
  border-bottom: 1px solid #e0e7ff;
}

.toggle-label {
  display: flex;
  align-items: center;
  gap: 10px;
  cursor: pointer;
  font-size: 13px;
  color: #555;
}

.toggle-label input {
  display: none;
}

.toggle-switch {
  width: 36px;
  height: 20px;
  background: #ccc;
  border-radius: 10px;
  position: relative;
  transition: background 0.3s;
}

.toggle-switch::after {
  content: '';
  position: absolute;
  width: 16px;
  height: 16px;
  background: white;
  border-radius: 50%;
  top: 2px;
  left: 2px;
  transition: transform 0.3s;
}

.toggle-label input:checked + .toggle-switch {
  background: #667eea;
}

.toggle-label input:checked + .toggle-switch::after {
  transform: translateX(16px);
}

.toggle-text {
  flex: 1;
}

/* 메시지 영역 */
.chat-messages {
  flex: 1;
  padding: 20px;
  overflow-y: auto;
  background: #f8f9fa;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.message {
  display: flex;
  max-width: 85%;
}

.message.user {
  align-self: flex-end;
}

.message.bot {
  align-self: flex-start;
}

.message-bubble {
  padding: 12px 16px;
  border-radius: 18px;
  position: relative;
}

.message.user .message-bubble {
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  color: white;
  border-bottom-right-radius: 4px;
}

.message.bot .message-bubble {
  background: white;
  color: #333;
  border-bottom-left-radius: 4px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.08);
}

.message-bubble p {
  margin: 0;
  font-size: 14px;
  line-height: 1.6;
  white-space: pre-wrap;
}

.message-time {
  font-size: 10px;
  opacity: 0.7;
  margin-top: 4px;
  display: block;
}

/* 타이핑 인디케이터 */
.typing {
  display: flex;
  gap: 4px;
  padding: 16px 20px;
}

.typing span {
  width: 8px;
  height: 8px;
  background: #999;
  border-radius: 50%;
  animation: typing 1.4s infinite ease-in-out;
}

.typing span:nth-child(1) { animation-delay: 0s; }
.typing span:nth-child(2) { animation-delay: 0.2s; }
.typing span:nth-child(3) { animation-delay: 0.4s; }

@keyframes typing {
  0%, 60%, 100% { transform: translateY(0); }
  30% { transform: translateY(-8px); }
}

/* 빠른 응답 버튼 */
.quick-replies {
  padding: 12px 16px;
  background: #f8f9fa;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  border-top: 1px solid #eee;
}

.quick-reply-btn {
  padding: 8px 14px;
  background: white;
  border: 2px solid #667eea;
  border-radius: 20px;
  color: #667eea;
  font-size: 12px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.2s;
}

.quick-reply-btn:hover {
  background: #667eea;
  color: white;
}

/* 입력 영역 */
.chat-input {
  padding: 16px 20px;
  background: white;
  border-top: 1px solid #eee;
  display: flex;
  gap: 12px;
}

.chat-input input {
  flex: 1;
  padding: 12px 16px;
  border: 2px solid #e9ecef;
  border-radius: 24px;
  font-size: 14px;
  outline: none;
  transition: border-color 0.2s;
}

.chat-input input:focus {
  border-color: #667eea;
}

.chat-input button {
  width: 44px;
  height: 44px;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  border: none;
  border-radius: 50%;
  color: white;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.2s;
}

.chat-input button:hover:not(:disabled) {
  transform: scale(1.05);
}

.chat-input button:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

/* 반응형 */
@media (max-width: 480px) {
  .chatbot-container {
    bottom: 16px;
    right: 16px;
  }

  .chat-window {
    width: calc(100vw - 32px);
    height: calc(100vh - 100px);
    max-height: 650px;
  }
}
</style>
