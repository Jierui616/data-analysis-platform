import { request } from '@umijs/max';

export async function getRestaurantHqOverview(data: Record<string, any>) {
  return request<any>('/api/restaurant/overview/hq', {
    method: 'POST',
    data,
  });
}

export async function getRestaurantStoreOverview(data: Record<string, any>) {
  return request<any>('/api/restaurant/overview/store', {
    method: 'POST',
    data,
  });
}

export async function getRestaurantDishAnalysis(data: Record<string, any>) {
  return request<any>('/api/restaurant/dish/analysis', {
    method: 'POST',
    data,
  });
}

export async function getRestaurantCampaignReview(data: Record<string, any>) {
  return request<any>('/api/restaurant/campaign/review', {
    method: 'POST',
    data,
  });
}

export async function getRestaurantAlertRules() {
  return request<any>('/api/restaurant/alert/rules', {
    method: 'POST',
  });
}

export async function getRestaurantAlertEvents(data: Record<string, any>) {
  return request<any>('/api/restaurant/alert/events', {
    method: 'POST',
    data,
  });
}

export async function getRestaurantCostLossMock(data: Record<string, any>) {
  return request<any>('/api/restaurant/mock/cost-loss', {
    method: 'POST',
    data,
  });
}

export async function getRestaurantStaffEfficiencyMock(data: Record<string, any>) {
  return request<any>('/api/restaurant/mock/staff-efficiency', {
    method: 'POST',
    data,
  });
}

export async function getRestaurantForecastMock(data: Record<string, any>) {
  return request<any>('/api/restaurant/mock/forecast', {
    method: 'POST',
    data,
  });
}

export async function getRestaurantDateRange(data: Record<string, any>) {
  return request<any>('/api/restaurant/date-range', {
    method: 'POST',
    data,
  });
}

export async function getRestaurantGeoScope(data: Record<string, any>) {
  return request<any>('/api/restaurant/geo-scope', {
    method: 'POST',
    data,
  });
}

export async function importRestaurantFromChart(chartId: string | number) {
  return request<any>('/api/restaurant/import/from-chart', {
    method: 'POST',
    data: { chartId },
  });
}

export async function importRestaurantCostData(csvData: string, importMode: string) {
  return request<any>('/api/restaurant/import/cost', {
    method: 'POST',
    data: { csvData, importMode },
  });
}

export async function importRestaurantStaffData(csvData: string, importMode: string) {
  return request<any>('/api/restaurant/import/staff', {
    method: 'POST',
    data: { csvData, importMode },
  });
}

export async function listRestaurantImportLogs() {
  return request<any>('/api/restaurant/import/logs', {
    method: 'POST',
  });
}

export async function rollbackRestaurantLatestImport(importType: 'cost' | 'staff', logId?: number) {
  return request<any>('/api/restaurant/import/rollback-latest', {
    method: 'POST',
    data: { importType, logId },
  });
}
