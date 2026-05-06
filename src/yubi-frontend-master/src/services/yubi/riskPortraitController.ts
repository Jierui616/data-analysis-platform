/**
 * Risk portrait API client. Backend context-path is /api.
 * Hit log DDL: src/yubi-backend-master/sql/risk_portrait_schema.sql → table risk_portrait_hit_log (English DB COMMENTs).
 * Endpoints: overview, store/profile, hit-log/page, decision, member/activity, member/watchlist, anonymous/summary.
 */
import { request } from '@umijs/max';

export type PortraitScores = {
  stability: number;
  trust: number;
  value: number;
};

export type StorePortraitData = {
  storeId: string;
  storeName?: string;
  scores: PortraitScores;
  riskLevel: string;
  reasons: string[];
  suggestedActions: string[];
  windowNote?: string;
};

/** 门店画像（无 Mock；失败由全局 request 或调用方处理） */
export async function fetchStorePortrait(body: {
  storeId: string;
  storeName?: string;
  startDate?: string;
  endDate?: string;
}): Promise<StorePortraitData> {
  const res = await request<{ code?: number; data?: StorePortraitData; message?: string }>(
    '/api/risk-portrait/store/profile',
    {
      method: 'POST',
      data: body,
    },
  );
  if (res?.code !== 0 || !res?.data?.storeId) {
    throw new Error(res?.message || '门店画像加载失败');
  }
  return res.data as StorePortraitData;
}

export type OverviewSummary = {
  storeEvaluated: number;
  highRiskStores: number;
  pendingAudits: number;
  lastRefresh?: string;
};

export async function fetchPortraitOverview(body?: Record<string, string | undefined>): Promise<OverviewSummary> {
  const res = await request<{ code?: number; data?: OverviewSummary; message?: string }>('/api/risk-portrait/overview', {
    method: 'POST',
    data: body || {},
  });
  if (res?.code !== 0 || res?.data == null) {
    throw new Error(res?.message || '画像总览加载失败');
  }
  return res.data;
}

export type HitLogRow = {
  id?: number;
  time: string;
  subject: string;
  action: string;
  rule: string;
  scene?: string;
};

export type HitLogPageData = {
  records: HitLogRow[];
  total: number;
  current: number;
  pageSize: number;
};

export async function pageRiskHitLog(body: {
  current?: number;
  pageSize?: number;
  scene?: string;
}): Promise<HitLogPageData> {
  const res = await request<{ code?: number; data?: HitLogPageData; message?: string }>('/api/risk-portrait/hit-log/page', {
    method: 'POST',
    data: body,
  });
  if (res?.code !== 0 || res?.data == null) {
    throw new Error(res?.message || '命中留痕加载失败');
  }
  return res.data;
}

export type RiskDecisionResult = {
  scene: string;
  riskLevel: string;
  hitRules: string[];
  recommendedActions: string[];
  persisted: boolean;
  portrait?: StorePortraitData;
  note?: string;
};

export async function runRiskDecision(body: {
  scene: string;
  subjectType?: string;
  subjectId?: string;
  storeId?: string | number;
  startDate?: string;
  endDate?: string;
  persist?: boolean;
}): Promise<RiskDecisionResult> {
  const res = await request<{ code?: number; data?: RiskDecisionResult; message?: string }>('/api/risk-portrait/decision', {
    method: 'POST',
    data: body,
  });
  if (res?.code !== 0 || res?.data == null) {
    throw new Error(res?.message || '决策执行失败');
  }
  return res.data as RiskDecisionResult;
}

export type MemberActivityRow = {
  time: string;
  storeName: string;
  amount: string;
  channel: string;
  orderNoMasked: string;
};

export type MemberCouponRow = {
  time: string;
  storeName: string;
  couponName: string;
  faceValue: string;
  discountAmount: string;
  status: string;
};

export type PortraitDataMode = 'demo' | 'import';

export type MemberActivityData = {
  dimension: string;
  maskedQuery: string;
  period: { startDate: string; endDate: string };
  summary: {
    orderCount: number;
    totalAmount: string;
    couponRedeemCount: number;
    lastActive: string;
  };
  transactions: MemberActivityRow[];
  couponRedemptions: MemberCouponRow[];
  dataSourceNote?: string;
  dataMode?: PortraitDataMode;
};

export async function fetchMemberActivity(body: {
  dimension: 'memberId' | 'phone' | 'payAccount';
  value: string;
  startDate?: string;
  endDate?: string;
}): Promise<MemberActivityData> {
  const res = await request<{ code?: number; data?: MemberActivityData; message?: string }>('/api/risk-portrait/member/activity', {
    method: 'POST',
    data: body,
  });
  if (res?.code !== 0 || res?.data == null) {
    throw new Error(res?.message || '会员明细加载失败');
  }
  return res.data;
}

export type MemberWatchlistRow = {
  memberId: string;
  displayRef: string;
  riskLevel: string;
  tags: string[];
  orderCount: number;
  couponRedeemCount: number;
  refundHint: string;
  lastActive: string;
  topStore: string;
};

export type MemberWatchlistData = {
  records: MemberWatchlistRow[];
  total: number;
  current: number;
  pageSize: number;
  period: { startDate: string; endDate: string };
  dataSourceNote?: string;
  dataMode?: PortraitDataMode;
};

/** 总部会员关注名单（分页 + 筛选） */
export async function fetchMemberWatchlist(body: {
  startDate?: string;
  endDate?: string;
  riskLevel?: string;
  storeKeyword?: string;
  current?: number;
  pageSize?: number;
}): Promise<MemberWatchlistData> {
  const res = await request<{ code?: number; data?: MemberWatchlistData; message?: string }>('/api/risk-portrait/member/watchlist', {
    method: 'POST',
    data: body,
  });
  if (res?.code !== 0 || res?.data == null) {
    throw new Error(res?.message || '关注名单加载失败');
  }
  return res.data;
}

export type AnonymousCluster = {
  anonymousBucketId: string;
  linkedWeakSignals: string[];
  eventCount7d: number;
  riskTag: string;
  lastSeen: string;
  topStoreHint?: string;
};

export type AnonymousPortraitData = {
  clusters: AnonymousCluster[];
  total: number;
  current: number;
  pageSize: number;
  desensitization: {
    retentionSuggestionDays: number;
    purposeStatement: string;
    rules: { field: string; policy: string }[];
    sampleMasked: Record<string, string>;
  };
  weakSignalFilter?: string;
  aggregationNote?: string;
  dataMode?: PortraitDataMode;
};

/** 匿名 ID 聚合分桶与合规脱敏策略说明（支持分页与 weakSignal） */
export async function fetchAnonymousPortraitSummary(body?: {
  weakSignal?: string;
  current?: number;
  pageSize?: number;
}): Promise<AnonymousPortraitData> {
  const res = await request<{ code?: number; data?: AnonymousPortraitData; message?: string }>(
    '/api/risk-portrait/anonymous/summary',
    {
      method: 'POST',
      data: body || {},
    },
  );
  if (res?.code !== 0 || res?.data == null) {
    throw new Error(res?.message || '匿名画像加载失败');
  }
  return res.data;
}
