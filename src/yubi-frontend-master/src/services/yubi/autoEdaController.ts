// @ts-ignore
/* eslint-disable */
import { request } from '@umijs/max';

// ---------- Types ----------

export type StartExplorationParams = {
  chartId: number;
  maxQueryBudget?: number;
  targetMetrics?: string[];
  convergenceEpsilon?: number;
  convergenceRounds?: number;
};

export type ExplorationState = {
  sessionId?: string;
  status?: string;
  exploredRegions?: Record<string, string[]>;
  pendingHypothesisCount?: number;
  verifiedHypothesisCount?: number;
  activeHypotheses?: HypothesisItem[];
  queriesExecuted?: number;
  queryBudget?: number;
  lastMaxIg?: number;
  converged?: boolean;
  suggestedNextDirections?: string[];
  startTime?: number;
  elapsedSeconds?: number;
};

export type HypothesisItem = {
  hypothesisId?: string;
  type?: string;
  statement?: string;
  confidencePrior?: number;
  confidencePosterior?: number;
  status?: string;
  lineagePath?: string;
  evidenceChain?: EvidenceChainItem[];
  sourceQueryIds?: string[];
};

export type EvidenceChainItem = {
  stepOrder?: number;
  queryId?: string;
  querySql?: string;
  resultSummary?: string;
  resultRowCount?: number;
  lineagePath?: string;
  igScore?: number;
};

export type InsightReport = {
  sessionId?: string;
  chartName?: string;
  chartId?: number;
  executiveSummary?: InsightItem[];
  anomalies?: AnomalyItem[];
  deepInsights?: InsightItem[];
  openLeads?: OpenLeadItem[];
  auditTrail?: AuditStep[];
  totalQueriesExecuted?: number;
  generatedAt?: number;
};

export type InsightItem = {
  title?: string;
  description?: string;
  confidence?: number;
  actionableAdvice?: string;
  evidenceChain?: EvidenceChainItem[];
};

export type AnomalyItem = {
  metricName?: string;
  anomalyDescription?: string;
  deviationSigma?: number;
  rootCause?: string;
  businessImpact?: string;
  evidenceChain?: EvidenceChainItem[];
};

export type OpenLeadItem = {
  hypothesis?: string;
  reasonIncomplete?: string;
  suggestedDataToCollect?: string;
};

export type AuditStep = {
  stepNumber?: number;
  querySql?: string;
  decisionReason?: string;
  igScore?: number;
  resultSummary?: string;
  executionMs?: number;
};

// ---------- API Calls ----------

export async function startExplorationUsingPOST(
  body: StartExplorationParams,
  options?: { [key: string]: any },
) {
  return request<{ code?: number; data?: { sessionId: string; status: string }; message?: string }>(
    '/api/auto-eda/start',
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      data: body,
      ...(options || {}),
    },
  );
}

export async function getStateUsingGET(
  sessionId: string,
  options?: { [key: string]: any },
) {
  return request<{ code?: number; data?: ExplorationState; message?: string }>(
    `/api/auto-eda/state/${sessionId}`,
    {
      method: 'GET',
      ...(options || {}),
    },
  );
}

export async function getReportUsingGET(
  sessionId: string,
  options?: { [key: string]: any },
) {
  return request<{ code?: number; data?: InsightReport; message?: string }>(
    `/api/auto-eda/report/${sessionId}`,
    {
      method: 'GET',
      ...(options || {}),
    },
  );
}

export async function continueExplorationUsingPOST(
  body: { sessionId: string; followUpQuery: string },
  options?: { [key: string]: any },
) {
  return request<{ code?: number; data?: ExplorationState; message?: string }>(
    '/api/auto-eda/continue',
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      data: body,
      ...(options || {}),
    },
  );
}

export async function getHypothesisDetailUsingGET(
  sessionId: string,
  hypothesisId: string,
  options?: { [key: string]: any },
) {
  return request<{ code?: number; data?: HypothesisItem; message?: string }>(
    `/api/auto-eda/hypothesis/${sessionId}/${hypothesisId}`,
    {
      method: 'GET',
      ...(options || {}),
    },
  );
}

export async function stopExplorationUsingPOST(
  sessionId: string,
  options?: { [key: string]: any },
) {
  return request<{ code?: number; data?: boolean; message?: string }>(
    `/api/auto-eda/stop/${sessionId}`,
    {
      method: 'POST',
      ...(options || {}),
    },
  );
}
