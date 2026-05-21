import { request } from '@umijs/max';

/** 大数据栈状态（ClickHouse / Kafka / MinIO） */
export async function getBigDataStatus() {
  return request<any>('/api/bigdata/status', { method: 'GET' });
}

/** 将 MySQL 门店日指标同步到 ClickHouse */
export async function syncBigDataMetrics() {
  return request<any>('/api/bigdata/sync/metrics', { method: 'POST' });
}
