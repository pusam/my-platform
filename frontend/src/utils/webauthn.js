// WebAuthn (지문/Face ID/패스키) 클라이언트 래퍼
// 백엔드 options 응답을 @simplewebauthn/browser 에 맞게 호출.

import { startRegistration, startAuthentication, browserSupportsWebAuthn } from '@simplewebauthn/browser';
import apiClient from './api';

export const isWebauthnSupported = () => browserSupportsWebAuthn();

/**
 * 현재 로그인한 사용자의 기기를 지문/Face ID 자격증명으로 등록.
 * @param {string} [deviceName] 사용자가 지정한 기기 이름 (옵션)
 */
export async function registerWebauthn(deviceName) {
  const optRes = await apiClient.post('/webauthn/register/options');
  if (!optRes.data?.success) {
    throw new Error(optRes.data?.message || '등록 옵션 발급 실패');
  }
  const options = optRes.data.data;

  // 브라우저에게 ceremony 요청 — 사용자는 지문/FaceID 프롬프트를 봄
  const attResp = await startRegistration({ optionsJSON: options });

  // 사용자가 이름을 붙이면 같이 전송
  if (deviceName && deviceName.trim()) {
    attResp.deviceName = deviceName.trim();
  }

  const verifyRes = await apiClient.post('/webauthn/register/verify', attResp);
  if (!verifyRes.data?.success) {
    throw new Error(verifyRes.data?.message || '등록 검증 실패');
  }
  return verifyRes.data.data;
}

/**
 * 지문/Face ID 로 로그인.
 * @param {string} username 로그인하려는 아이디
 * @returns {Promise<{token:string, username:string, name:string, role:string}>}
 */
export async function loginWebauthn(username) {
  const optRes = await apiClient.post('/webauthn/login/options', { username });
  if (!optRes.data?.success) {
    throw new Error(optRes.data?.message || '로그인 옵션 발급 실패');
  }
  const options = optRes.data.data;

  if (!options.allowCredentials || options.allowCredentials.length === 0) {
    throw new Error('이 계정에 등록된 생체인증 기기가 없습니다. 먼저 비밀번호로 로그인 후 등록해주세요.');
  }

  const assertResp = await startAuthentication({ optionsJSON: options });

  const verifyRes = await apiClient.post('/webauthn/login/verify', {
    username,
    ...assertResp,
  });

  // /login/verify 는 LoginResponse (success:boolean, token,...) 를 직접 반환
  const body = verifyRes.data;
  if (!body?.success) {
    throw new Error(body?.message || '로그인 실패');
  }
  return {
    token: body.token,
    username: body.username,
    name: body.name,
    role: body.role,
  };
}

/** 등록된 자격증명 목록 */
export async function listCredentials() {
  const res = await apiClient.get('/webauthn/credentials');
  if (!res.data?.success) throw new Error(res.data?.message || '조회 실패');
  return res.data.data;
}

/** 자격증명 삭제 */
export async function deleteCredential(id) {
  const res = await apiClient.delete(`/webauthn/credentials/${id}`);
  if (!res.data?.success) throw new Error(res.data?.message || '삭제 실패');
}
