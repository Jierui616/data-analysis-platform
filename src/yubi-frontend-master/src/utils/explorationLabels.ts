/** 探索相关：面向业务用户的通俗文案 */

export const hypothesisTypeLabel: Record<string, string> = {
  anomaly: '异常波动',
  correlation: '指标关联',
  trend: '趋势变化',
  segment_diff: '门店/区域差异',
  counterfactual: '情景推测',
};

export const explorationStatusLabel: Record<string, string> = {
  COMPLETED: '已完成',
  RUNNING: '分析中',
  FAILED: '失败',
  STOPPED: '已停止',
};

export const translateHypothesisType = (type?: string) =>
  (type && hypothesisTypeLabel[type]) || type || '待分类';
