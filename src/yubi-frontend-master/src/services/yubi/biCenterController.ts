// @ts-ignore
/* eslint-disable */
import { request } from '@umijs/max';

export type DeepChatActionItem = {
  priority?: string;
  title?: string;
  owner?: string;
  expectedBenefit?: string;
  eta?: string;
};

export type DeepChatResponse = {
  answer?: string;
  followUps?: string[];
  assumptions?: string[];
  actionItems?: DeepChatActionItem[];
};

export type ChatSessionMeta = {
  sessionId?: string;
  title?: string;
  updatedAt?: number;
};

export async function deepChatUsingPOST(body: Record<string, any>, options?: { [key: string]: any }) {
  return request<{ code?: number; data?: DeepChatResponse; message?: string }>('/api/bi-center/ai/deep-chat', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  });
}

export async function runAiUsingPOST(body: Record<string, any>, options?: { [key: string]: any }) {
  return request<{ code?: number; data?: any; message?: string }>('/api/bi-center/ai/run', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  });
}

export async function createChatSessionUsingPOST(body?: Record<string, any>, options?: { [key: string]: any }) {
  return request<{ code?: number; data?: ChatSessionMeta; message?: string }>('/api/bi-center/ai/session/create', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body || {},
    ...(options || {}),
  });
}

export async function listChatSessionsUsingGET(options?: { [key: string]: any }) {
  return request<{ code?: number; data?: ChatSessionMeta[]; message?: string }>('/api/bi-center/ai/session/list', {
    method: 'GET',
    ...(options || {}),
  });
}

export async function listChatSessionMessagesUsingGET(sessionId: string, options?: { [key: string]: any }) {
  return request<{ code?: number; data?: Array<Record<string, any>>; message?: string }>(
    `/api/bi-center/ai/session/${sessionId}/messages`,
    {
      method: 'GET',
      ...(options || {}),
    },
  );
}
