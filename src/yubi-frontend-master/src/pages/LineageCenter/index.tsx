import { request, useSearchParams } from '@umijs/max';
import { listMyChartByPageUsingPOST } from '@/services/yubi/chartController';
import {
  Alert,
  Button,
  Card,
  Col,
  Collapse,
  Descriptions,
  Divider,
  Input,
  List,
  Result,
  Row,
  Select,
  Space,
  Spin,
  Tabs,
  Tag,
  Timeline,
  Typography,
  message,
} from 'antd';
import ReactECharts from 'echarts-for-react';
import React, { useEffect, useMemo, useState } from 'react';

type LineageEvent = {
  id: number;
  eventType: string;
  status: string;
  logicRef?: string;
  createTime?: string | Date;
  eventMeta?: string;
};

type LineageTraceData = {
  traceId: string;
  chartId: number;
  events: LineageEvent[];
  explanations: Array<{ modelName?: string; promptVersion?: string }>;
};

type LineageGraphEdge = {
  fromNodeKey: string;
  toNodeKey: string;
  edgeType: string;
  evidenceSource?: string;
  confidence?: string;
  evidenceDetail?: string;
  fixSuggestion?: string;
};

type LineageGraphData = {
  traceId: string;
  chartId: number;
  nodes: Array<{ nodeKey: string; nodeType: string; name: string; status?: string }>;
  edges: LineageGraphEdge[];
};

type LineageImpactData = {
  sourceChartId: number;
  impactedCount: number;
  impactedCharts: Array<{ chartId: number; chartName: string; reason: string }>;
};

type LineageFixTask = {
  id: number;
  chartId: number;
  fromNodeKey: string;
  toNodeKey: string;
  edgeType: string;
  evidenceSource?: string;
  confidence?: string;
  fixSuggestion?: string;
  status: string;
};

const EVENT_STEP_LABELS: Record<string, string> = {
  SOURCE_INGESTED: '数据接入',
  DATA_CLEANED: '数据清洗',
  METRIC_COMPUTED: '指标计算',
  CHART_RENDERED: '图表生成',
  AI_INTERPRETED: '智能解读',
};

function businessEventLabel(eventType?: string) {
  if (!eventType) return '处理步骤';
  return EVENT_STEP_LABELS[eventType] || eventType;
}

type NodeLookup = Map<string, { name: string; nodeType?: string }>;

function buildNodeLookup(nodes: LineageGraphData['nodes']): NodeLookup {
  const m: NodeLookup = new Map();
  (nodes || []).forEach((n) => {
    if (n?.nodeKey) m.set(n.nodeKey, { name: n.name, nodeType: n.nodeType });
  });
  return m;
}

function businessNodeLine(nodeKey: string, lookup: NodeLookup): string {
  const hit = lookup.get(nodeKey);
  if (hit?.name) return hit.name;
  if (nodeKey.startsWith('dataset:')) {
    const id = nodeKey.slice('dataset:'.length);
    if (id.startsWith('chart_input_')) return '上传的经营数据';
    if (id.startsWith('chart_clean_')) return '清洗后的数据';
    if (id.startsWith('chart_metric_')) return '指标计算结果';
    if (id.startsWith('chart_option_')) return '图表展示配置';
    if (id.startsWith('chart_ai_result_')) return '智能解读结论';
    return `数据（${id}）`;
  }
  if (nodeKey.startsWith('chart:')) return `图表（编号 ${nodeKey.replace('chart:', '')}）`;
  if (nodeKey.startsWith('metric:')) return '指标口径（分析目标）';
  if (nodeKey.startsWith('task:')) return '分析处理步骤';
  return nodeKey;
}

function edgeTypePlain(edgeType?: string): string {
  const t = String(edgeType || '').toLowerCase();
  if (t === 'read') return '读取';
  if (t === 'write') return '产出';
  if (t === 'derive') return '推导';
  if (t === 'use') return '使用';
  return edgeType || '-';
}

function evidenceSourcePlain(s?: string): string {
  if (s === 'event_meta') return '有明确记录';
  if (s === 'text_parse') return '系统推测';
  if (s === 'system') return '系统内置';
  return s ? s : '未知';
}

function confidencePlain(c?: string): string {
  const x = String(c || '').toLowerCase();
  if (x === 'high') return '高可信';
  if (x === 'medium') return '一般';
  if (x === 'low') return '待核实';
  return '一般';
}

const LineageCenter: React.FC = () => {
  const [searchParams] = useSearchParams();
  const [chartIdInput, setChartIdInput] = useState<string>('');
  const [chartOptions, setChartOptions] = useState<Array<{ label: string; value: string }>>([]);
  const [loading, setLoading] = useState(false);
  const [rerunLoading, setRerunLoading] = useState(false);
  const [trace, setTrace] = useState<LineageTraceData>();
  const [graph, setGraph] = useState<LineageGraphData>();
  const [impact, setImpact] = useState<LineageImpactData>();
  const [fixTasks, setFixTasks] = useState<LineageFixTask[]>([]);

  const loadLineage = async (id: string) => {
    if (!id.trim()) {
      message.warning('请输入 chartId');
      return;
    }
    setLoading(true);
    try {
      const [traceRes, graphRes, impactRes, fixTaskRes] = await Promise.all([
        request<any>(`/api/lineage/chart/${id}/latest`, { method: 'GET', timeout: 15000 }),
        request<any>(`/api/lineage/graph/chart/${id}`, { method: 'GET', params: { depth: 2 }, timeout: 15000 }).catch(
          () => undefined,
        ),
        request<any>(`/api/lineage/impact/chart/${id}`, { method: 'GET', timeout: 15000 }).catch(() => undefined),
        request<any>(`/api/lineage/fix-tasks/chart/${id}`, { method: 'GET', timeout: 15000 }).catch(() => undefined),
      ]);
      const traceData = (traceRes && 'data' in traceRes ? traceRes.data : traceRes) as LineageTraceData | undefined;
      if (!traceData?.traceId) {
        throw new Error('暂无可用血缘链路');
      }
      setTrace(traceData);
      setGraph(graphRes && 'data' in graphRes ? (graphRes.data as LineageGraphData) : (graphRes as LineageGraphData));
      setImpact(
        impactRes && 'data' in impactRes ? (impactRes.data as LineageImpactData) : (impactRes as LineageImpactData),
      );
      const tasks = fixTaskRes && 'data' in fixTaskRes ? (fixTaskRes.data as LineageFixTask[]) : (fixTaskRes as any[]);
      setFixTasks(Array.isArray(tasks) ? tasks : []);
    } catch (e: any) {
      message.error(`加载血缘数据失败：${e?.message || String(e)}`);
      setTrace(undefined);
      setGraph(undefined);
      setImpact(undefined);
      setFixTasks([]);
    } finally {
      setLoading(false);
    }
  };

  const loadMyChartOptions = async () => {
    try {
      const res = await listMyChartByPageUsingPOST({
        current: 1,
        pageSize: 20,
        sortField: 'createTime',
        sortOrder: 'desc',
      } as API.ChartQueryRequest);
      const records = res?.data?.records ?? [];
      const options = records
        .filter((item: any) => item?.id)
        .map((item: any) => ({
          value: String(item.id),
          label: `${item.name || `图表${item.id}`}（${item.chartType || '未分类'}）`,
        }));
      setChartOptions(options);
      return options;
    } catch {
      // ignore options loading errors
      return [];
    }
  };

  useEffect(() => {
    const init = async () => {
      const options = await loadMyChartOptions();
      const chartIdFromQuery = (searchParams.get('chartId') || '').trim();
      const defaultChartId = chartIdFromQuery || options?.[0]?.value || '';
      if (!defaultChartId) {
        return;
      }
      setChartIdInput(defaultChartId);
      loadLineage(defaultChartId);
    };
    init();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const handleFixTaskStatus = async (taskId: number, status: 'pending' | 'resolved') => {
    try {
      const res = await request<any>(`/api/lineage/fix-tasks/${taskId}/status`, {
        method: 'GET',
        params: { status },
      });
      if (res?.code && res.code !== 0) throw new Error(res.message || '状态更新失败');
      setFixTasks((prev) => prev.map((task) => (task.id === taskId ? { ...task, status } : task)));
      message.success(status === 'resolved' ? '已标记为已修复' : '已恢复为待处理');
    } catch (e: any) {
      message.error(`更新失败：${e?.message || String(e)}`);
    }
  };

  const handleRerun = async () => {
    if (!chartIdInput.trim()) return;
    setRerunLoading(true);
    try {
      const res = await request<any>('/api/chart/rerun/async', {
        method: 'POST',
        data: { chartId: chartIdInput },
      });
      if (res?.code && res.code !== 0) throw new Error(res.message || '复跑提交失败');
      message.success('复跑任务已提交');
    } catch (e: any) {
      message.error(`复跑失败：${e?.message || String(e)}`);
    } finally {
      setRerunLoading(false);
    }
  };

  const failedEvents = useMemo(
    () => (trace?.events || []).filter((event) => event.status && event.status !== 'succeed'),
    [trace],
  );

  const lowConfidenceEdges = useMemo(
    () => (graph?.edges || []).filter((edge) => String(edge?.confidence || '').toLowerCase() === 'low'),
    [graph],
  );

  const pendingFixCount = useMemo(
    () => (fixTasks || []).filter((task) => task.status !== 'resolved').length,
    [fixTasks],
  );

  const confidenceScore = useMemo(() => {
    let score = 100;
    score -= failedEvents.length * 20;
    score -= lowConfidenceEdges.length * 8;
    score -= pendingFixCount * 5;
    return Math.max(0, Math.min(100, score));
  }, [failedEvents, lowConfidenceEdges, pendingFixCount]);

  const riskLevel = useMemo(() => {
    if (confidenceScore >= 85) return '低风险';
    if (confidenceScore >= 60) return '中风险';
    return '高风险';
  }, [confidenceScore]);

  const majorIssue = useMemo(() => {
    if (failedEvents.length > 0) {
      return `处理步骤异常：${businessEventLabel(failedEvents[0]?.eventType)}`;
    }
    if (lowConfidenceEdges.length > 0) {
      return '存在基于「系统推测」的数据关系，重要决策前建议核对';
    }
    if (pendingFixCount > 0) {
      return '存在待处理问题';
    }
    return '当前未发现关键风险';
  }, [failedEvents, lowConfidenceEdges, pendingFixCount]);

  const suggestedAction = useMemo(() => {
    if (failedEvents.length > 0) return '建议先重新生成分析，再检查失败步骤输入数据。';
    if (lowConfidenceEdges.length > 0)
      return '建议请技术侧补充「数据来源」说明，或确认当前结果可作为探索/演示结论。';
    if (pendingFixCount > 0) return '建议按优先级完成待处理问题并复核结果。';
    return '当前结果可信，可直接用于经营分析决策。';
  }, [failedEvents, lowConfidenceEdges, pendingFixCount]);

  const issueItems = useMemo(() => {
    const items: Array<{
      title: string;
      owner: string;
      priority: '高' | '中' | '低';
      impact: string;
      action: string;
      sla: string;
    }> = [];
    if (failedEvents.length > 0) {
      items.push({
        title: `存在失败步骤：${businessEventLabel(failedEvents[0]?.eventType)}`,
        owner: '数据运营',
        priority: '高',
        impact: '当前分析结论可能失真，不建议直接用于经营决策',
        action: '优先检查失败步骤输入数据并重新生成分析',
        sla: '24小时内',
      });
    }
    if (lowConfidenceEdges.length > 0) {
      items.push({
        title: `有 ${lowConfidenceEdges.length} 处关系来自「系统推测」，非明确记录`,
        owner: '数据治理负责人',
        priority: failedEvents.length > 0 ? '中' : '高',
        impact: '业务上可能难以完全还原「数从哪条线过来」，重要结论建议复核',
        action: '补充数据来源说明，或在修复任务中标记「已确认可接受」',
        sla: '48小时内',
      });
    }
    if (pendingFixCount > 0) {
      items.push({
        title: `存在 ${pendingFixCount} 条待处理修复任务`,
        owner: '分析负责人',
        priority: '中',
        impact: '问题闭环进度偏慢，影响后续复盘时效',
        action: '按优先级推进修复任务并标记完成',
        sla: '72小时内',
      });
    }
    if (items.length === 0) {
      items.push({
        title: '当前未发现关键风险',
        owner: '分析负责人',
        priority: '低',
        impact: '当前结果可用于日常经营分析与决策',
        action: '保持现有数据更新频率，按周复核一次可信度',
        sla: '持续',
      });
    }
    return items;
  }, [failedEvents, lowConfidenceEdges, pendingFixCount]);

  const impactItemsWithPriority = useMemo(() => {
    const rows = impact?.impactedCharts || [];
    return rows.map((item) => {
      const reason = String(item?.reason || '');
      let level: '高' | '中' | '低' = '低';
      if (reason.includes('关键') || reason.includes('核心') || reason.includes('缺失') || reason.includes('失败')) {
        level = '高';
      } else if (reason.includes('波动') || reason.includes('偏差') || reason.includes('建议')) {
        level = '中';
      }
      return {
        ...item,
        level,
      };
    });
  }, [impact]);

  const impactGrouped = useMemo(() => {
    const grouped: Record<'高' | '中' | '低', Array<any>> = { 高: [], 中: [], 低: [] };
    impactItemsWithPriority.forEach((item) => {
      grouped[item.level].push(item);
    });
    return grouped;
  }, [impactItemsWithPriority]);

  const chartDisplayName = useMemo(() => {
    const opt = chartOptions.find((o) => o.value === chartIdInput);
    if (opt?.label) return opt.label;
    if (trace?.chartId != null) return `图表 ${trace.chartId}`;
    return chartIdInput ? `图表 ${chartIdInput}` : '未选择';
  }, [chartOptions, chartIdInput, trace?.chartId]);

  /** 资产血缘力导向图（与物化/推导接口字段一致） */
  const lineageGraphChartOption = useMemo(() => {
    const nodes = graph?.nodes || [];
    const edges = graph?.edges || [];
    if (!nodes.length && !edges.length) {
      return null;
    }
    const lookup = buildNodeLookup(nodes);
    const categoryOrder = ['dataset', 'task', 'metric', 'chart', 'unknown'];
    const categories = categoryOrder.map((name) => ({ name }));
    const catIndex = (t?: string) => {
      const i = categoryOrder.indexOf(t || 'unknown');
      return i >= 0 ? i : categoryOrder.length - 1;
    };
    const nodeKeySet = new Set<string>();
    nodes.forEach((n) => {
      if (n?.nodeKey) nodeKeySet.add(n.nodeKey);
    });
    edges.forEach((e) => {
      if (e?.fromNodeKey) nodeKeySet.add(e.fromNodeKey);
      if (e?.toNodeKey) nodeKeySet.add(e.toNodeKey);
    });
    const data = Array.from(nodeKeySet).map((key) => {
      const n = nodes.find((x) => x.nodeKey === key);
      const fullLabel = businessNodeLine(key, lookup);
      const short = fullLabel.length > 18 ? `${fullLabel.slice(0, 16)}…` : fullLabel;
      return {
        id: key,
        name: short,
        category: catIndex(n?.nodeType),
        symbolSize: n?.nodeType === 'chart' ? 40 : n?.nodeType === 'dataset' ? 32 : 26,
        tooltip: {
          formatter: `${n?.nodeType || '节点'}<br/>${fullLabel}<br/><span style="color:#999">${key}</span>`,
        },
      };
    });
    const links = edges.map((e) => ({
      source: e.fromNodeKey,
      target: e.toNodeKey,
      value: e.edgeType,
      lineStyle: {
        color: e.confidence === 'high' ? '#52c41a' : e.confidence === 'low' ? '#ff4d4f' : '#faad14',
        width: 1.2,
      },
    }));
    return {
      tooltip: {},
      legend: { data: categories.map((c) => c.name), bottom: 0 },
      series: [
        {
          type: 'graph',
          layout: 'force',
          roam: true,
          draggable: true,
          categories,
          data,
          links,
          edgeSymbol: ['none', 'arrow'],
          edgeSymbolSize: [0, 10],
          label: { show: true, position: 'right', fontSize: 10 },
          force: { repulsion: 320, edgeLength: [90, 160], gravity: 0.08 },
          emphasis: { focus: 'adjacency', lineStyle: { width: 3 } },
        },
      ],
    };
  }, [graph]);

  const graphNodeLookup = useMemo(() => buildNodeLookup(graph?.nodes || []), [graph?.nodes]);

  return (
    <div>
      <Typography.Title level={4}>分析可信度与问题追踪中心</Typography.Title>
      <Typography.Paragraph type="secondary" style={{ marginTop: -8 }}>
        帮助你判断本次分析是否可信、问题在哪、该由谁处理。建议先看「数据链路」理解过程，再看「问题与建议」；技术字段集中在高级页签。
      </Typography.Paragraph>
      <Card style={{ marginBottom: 12 }}>
        <Space wrap>
          <Select
            style={{ width: 360 }}
            placeholder="从图表管理与结果回看中选择"
            value={chartIdInput || undefined}
            options={chartOptions}
            onChange={(value) => setChartIdInput(String(value))}
            showSearch
            optionFilterProp="label"
            allowClear
          />
          <Input
            style={{ width: 220 }}
            placeholder="或输入 chartId"
            value={chartIdInput}
            onChange={(e) => setChartIdInput(e.target.value)}
          />
          <Button type="primary" onClick={() => loadLineage(chartIdInput)} loading={loading}>
            查看可信度
          </Button>
          <Button onClick={handleRerun} loading={rerunLoading} disabled={!chartIdInput.trim()}>
            重新生成分析
          </Button>
        </Space>
      </Card>

      <Spin spinning={loading}>
        {!trace ? (
          <Result status="info" title="暂未生成本图表的追踪信息" subTitle="请先选择图表并点击“查看可信度”。" />
        ) : (
          <>
            <Row gutter={12} style={{ marginBottom: 12 }}>
              <Col xs={24} lg={6}>
                <Card>
                  <Descriptions size="small" column={1}>
                    <Descriptions.Item label="可信度评分">{confidenceScore} / 100</Descriptions.Item>
                    <Descriptions.Item label="风险等级">
                      <Tag color={riskLevel === '低风险' ? 'success' : riskLevel === '中风险' ? 'warning' : 'error'}>{riskLevel}</Tag>
                    </Descriptions.Item>
                  </Descriptions>
                </Card>
              </Col>
              <Col xs={24} lg={6}>
                <Card>
                  <Descriptions size="small" column={1}>
                    <Descriptions.Item label="主要问题">{majorIssue}</Descriptions.Item>
                    <Descriptions.Item label="建议动作">{suggestedAction}</Descriptions.Item>
                  </Descriptions>
                </Card>
              </Col>
              <Col xs={24} lg={12}>
                <Card>
                  <Descriptions size="small" column={4}>
                    <Descriptions.Item label="分析对象">{chartDisplayName}</Descriptions.Item>
                    <Descriptions.Item label="chartId">{trace.chartId}</Descriptions.Item>
                    <Descriptions.Item label="处理环节数">{trace.events?.length || 0}</Descriptions.Item>
                    <Descriptions.Item label="待处理问题">{pendingFixCount}</Descriptions.Item>
                  </Descriptions>
                  <Typography.Text type="secondary" style={{ display: 'block', marginTop: 4 }}>
                    链路编号 traceId（技术用）：{trace.traceId}
                  </Typography.Text>
                  {!!failedEvents.length && (
                    <Alert
                      style={{ marginTop: 8 }}
                      type="error"
                      showIcon
                      message={`当前存在失败步骤：${businessEventLabel(failedEvents[0]?.eventType)}`}
                    />
                  )}
                </Card>
              </Col>
            </Row>
            <Tabs
              items={[
                {
                  key: 'data-lineage',
                  label: '数据链路',
                  children: (
                    <Card>
                      <Typography.Paragraph>
                        <Typography.Text strong>当前分析：</Typography.Text> {chartDisplayName}
                      </Typography.Paragraph>
                      <Typography.Paragraph type="secondary" style={{ marginBottom: 16 }}>
                        按时间顺序展示数据经历的环节，便于理解结论来源。需要看节点关系时，可在下方展开关系图。
                      </Typography.Paragraph>
                      <Timeline
                        items={(trace.events || []).map((event) => ({
                          color: event.status === 'succeed' ? 'green' : event.status === 'failed' ? 'red' : 'blue',
                          children: (
                            <Space direction="vertical" size={2}>
                              <Space wrap>
                                <Typography.Text strong>{businessEventLabel(event.eventType)}</Typography.Text>
                                <Tag
                                  color={
                                    event.status === 'succeed' ? 'success' : event.status === 'failed' ? 'error' : 'processing'
                                  }
                                >
                                  {event.status === 'succeed'
                                    ? '已完成'
                                    : event.status === 'failed'
                                      ? '异常'
                                      : event.status || '进行中'}
                                </Tag>
                              </Space>
                              <Typography.Text type="secondary">时间：{String(event.createTime || '-')}</Typography.Text>
                              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                                技术步骤码：{event.eventType || '-'}
                              </Typography.Text>
                            </Space>
                          ),
                        }))}
                      />
                      <Divider />
                      <Collapse
                        ghost
                        items={[
                          {
                            key: 'graph',
                            label: '展开数据关系图（高级，可拖拽、缩放）',
                            children: lineageGraphChartOption ? (
                              <>
                                <Typography.Paragraph type="secondary">
                                  连线表示数据或结果流向：绿色表示关系有据可查，橙色为一般，红色建议核对。
                                </Typography.Paragraph>
                                <ReactECharts option={lineageGraphChartOption} style={{ height: 440, width: '100%' }} />
                              </>
                            ) : (
                              <Typography.Text type="secondary">暂无关系图数据。</Typography.Text>
                            ),
                          },
                        ]}
                      />
                    </Card>
                  ),
                },
                {
                  key: 'problem',
                  label: '问题与建议',
                  children: (
                    <Card>
                      <List
                        size="small"
                        dataSource={issueItems}
                        renderItem={(item) => (
                          <List.Item>
                            <Space direction="vertical" size={4}>
                              <Typography.Text strong>{item.title}</Typography.Text>
                              <Space wrap>
                                <Tag color="blue">{`负责人：${item.owner}`}</Tag>
                                <Tag color={item.priority === '高' ? 'red' : item.priority === '中' ? 'warning' : 'success'}>
                                  {`优先级：${item.priority}`}
                                </Tag>
                                <Tag color="processing">{`影响：${item.impact}`}</Tag>
                                <Tag color="purple">{`截止时间：${item.sla}`}</Tag>
                              </Space>
                              <Typography.Text>{`建议动作：${item.action}`}</Typography.Text>
                            </Space>
                          </List.Item>
                        )}
                      />
                    </Card>
                  ),
                },
                {
                  key: 'run-trace',
                  label: '技术详情（高级）',
                  children: (
                    <Card>
                      <Timeline
                        items={(trace.events || []).map((event) => ({
                          color: event.status === 'succeed' ? 'green' : event.status === 'failed' ? 'red' : 'blue',
                          children: (
                            <Space direction="vertical" size={2}>
                              <Space>
                                <Tag>{`处理步骤：${event.eventType}`}</Tag>
                                <Tag color={event.status === 'succeed' ? 'success' : event.status === 'failed' ? 'error' : 'processing'}>
                                  {event.status || 'unknown'}
                                </Tag>
                              </Space>
                              <Typography.Text type="secondary">
                                逻辑：{event.logicRef || '-'} | 时间：{String(event.createTime || '-')}
                              </Typography.Text>
                            </Space>
                          ),
                        }))}
                      />
                    </Card>
                  ),
                },
                {
                  key: 'asset-graph',
                  label: '数据关系明细（高级）',
                  children: (
                    <Card>
                      <Typography.Paragraph type="secondary" style={{ marginBottom: 8 }}>
                        关系图已移至「数据链路」页折叠面板；此处保留文字明细，便于对照。
                      </Typography.Paragraph>
                      <Descriptions size="small" column={2} style={{ marginBottom: 8 }}>
                        <Descriptions.Item label="节点数">{graph?.nodes?.length || 0}</Descriptions.Item>
                        <Descriptions.Item label="关系边数">{graph?.edges?.length || 0}</Descriptions.Item>
                      </Descriptions>
                      <List
                        size="small"
                        dataSource={graph?.edges || []}
                        locale={{ emptyText: '暂无关系边' }}
                        renderItem={(edge) => (
                          <List.Item>
                            <Space direction="vertical" size={2}>
                              <Typography.Text strong>
                                {businessNodeLine(edge.fromNodeKey, graphNodeLookup)} →{' '}
                                {businessNodeLine(edge.toNodeKey, graphNodeLookup)}
                              </Typography.Text>
                              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                                技术标识：{edge.fromNodeKey} → {edge.toNodeKey}
                              </Typography.Text>
                              <Space wrap>
                                <Tag>{`关系：${edgeTypePlain(edge.edgeType)}`}</Tag>
                                <Tag color="blue">{`依据：${evidenceSourcePlain(edge.evidenceSource)}`}</Tag>
                                <Tag color={edge.confidence === 'high' ? 'success' : edge.confidence === 'low' ? 'error' : 'warning'}>
                                  {`可信程度：${confidencePlain(edge.confidence)}`}
                                </Tag>
                              </Space>
                              {edge.fixSuggestion ? <Typography.Text type="secondary">{edge.fixSuggestion}</Typography.Text> : null}
                            </Space>
                          </List.Item>
                        )}
                      />
                    </Card>
                  ),
                },
                {
                  key: 'fix-task',
                  label: '修复任务',
                  children: (
                    <Card>
                      <List
                        size="small"
                        dataSource={fixTasks}
                        locale={{ emptyText: '暂无治理任务' }}
                        renderItem={(task) => (
                          <List.Item
                            actions={[
                              task.status === 'resolved' ? (
                                <Button size="small" onClick={() => handleFixTaskStatus(task.id, 'pending')}>
                                  设为处理中
                                </Button>
                              ) : (
                                <Button size="small" type="primary" onClick={() => handleFixTaskStatus(task.id, 'resolved')}>
                                  标记已完成
                                </Button>
                              ),
                            ]}
                          >
                            <Space direction="vertical" size={2}>
                              <Typography.Text strong>
                                {businessNodeLine(task.fromNodeKey, graphNodeLookup)} →{' '}
                                {businessNodeLine(task.toNodeKey, graphNodeLookup)}
                              </Typography.Text>
                              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                                技术标识：{task.fromNodeKey} → {task.toNodeKey}
                              </Typography.Text>
                              <Space wrap>
                                <Tag>{edgeTypePlain(task.edgeType)}</Tag>
                                <Tag color={task.status === 'resolved' ? 'success' : 'warning'}>
                                  {task.status === 'resolved' ? '已修复' : '待处理'}
                                </Tag>
                                <Tag>{evidenceSourcePlain(task.evidenceSource)}</Tag>
                                <Tag>{confidencePlain(task.confidence)}</Tag>
                              </Space>
                            </Space>
                          </List.Item>
                        )}
                      />
                    </Card>
                  ),
                },
                {
                  key: 'impact',
                  label: '影响分析',
                  children: (
                    <Card>
                      <Descriptions size="small" column={4} style={{ marginBottom: 8 }}>
                        <Descriptions.Item label="影响图表总数">{impact?.impactedCount || 0}</Descriptions.Item>
                        <Descriptions.Item label="高影响">{impactGrouped.高.length}</Descriptions.Item>
                        <Descriptions.Item label="中影响">{impactGrouped.中.length}</Descriptions.Item>
                        <Descriptions.Item label="低影响">{impactGrouped.低.length}</Descriptions.Item>
                      </Descriptions>

                      <Typography.Text strong>高影响（优先处理）</Typography.Text>
                      <List
                        size="small"
                        style={{ marginBottom: 8 }}
                        dataSource={impactGrouped.高}
                        locale={{ emptyText: '暂无高影响项' }}
                        renderItem={(item, idx) => (
                          <List.Item>
                            <Space direction="vertical" size={2}>
                              <Space>
                                <Tag color="error">{`处理顺序 #${idx + 1}`}</Tag>
                                <Typography.Text strong>{`${item.chartName} (#${item.chartId})`}</Typography.Text>
                              </Space>
                              <Typography.Text type="secondary">{item.reason}</Typography.Text>
                            </Space>
                          </List.Item>
                        )}
                      />

                      <Typography.Text strong>中影响（次优先）</Typography.Text>
                      <List
                        size="small"
                        style={{ marginBottom: 8 }}
                        dataSource={impactGrouped.中}
                        locale={{ emptyText: '暂无中影响项' }}
                        renderItem={(item, idx) => (
                          <List.Item>
                            <Space direction="vertical" size={2}>
                              <Space>
                                <Tag color="warning">{`处理顺序 #${idx + 1}`}</Tag>
                                <Typography.Text strong>{`${item.chartName} (#${item.chartId})`}</Typography.Text>
                              </Space>
                              <Typography.Text type="secondary">{item.reason}</Typography.Text>
                            </Space>
                          </List.Item>
                        )}
                      />

                      <Typography.Text strong>低影响（可排期处理）</Typography.Text>
                      <List
                        size="small"
                        dataSource={impactGrouped.低}
                        locale={{ emptyText: '暂无低影响项' }}
                        renderItem={(item, idx) => (
                          <List.Item>
                            <Space direction="vertical" size={2}>
                              <Space>
                                <Tag color="default">{`处理顺序 #${idx + 1}`}</Tag>
                                <Typography.Text strong>{`${item.chartName} (#${item.chartId})`}</Typography.Text>
                              </Space>
                              <Typography.Text type="secondary">{item.reason}</Typography.Text>
                            </Space>
                          </List.Item>
                        )}
                      />
                    </Card>
                  ),
                },
              ]}
            />
          </>
        )}
      </Spin>
    </div>
  );
};

export default LineageCenter;

