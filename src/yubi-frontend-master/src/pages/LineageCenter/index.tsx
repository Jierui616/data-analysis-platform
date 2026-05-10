import { request, useSearchParams } from '@umijs/max';
import { listMyChartByPageUsingPOST } from '@/services/yubi/chartController';
import {
  Alert,
  Button,
  Card,
  Col,
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
      return `处理步骤异常：${failedEvents[0]?.eventType || '未知步骤'}`;
    }
    if (lowConfidenceEdges.length > 0) {
      return '存在低可信数据关系';
    }
    if (pendingFixCount > 0) {
      return '存在待处理问题';
    }
    return '当前未发现关键风险';
  }, [failedEvents, lowConfidenceEdges, pendingFixCount]);

  const suggestedAction = useMemo(() => {
    if (failedEvents.length > 0) return '建议先重新生成分析，再检查失败步骤输入数据。';
    if (lowConfidenceEdges.length > 0) return '建议优先处理低可信关系，补齐结构化证据。';
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
        title: `存在失败步骤：${failedEvents[0]?.eventType || '未知步骤'}`,
        owner: '数据运营',
        priority: '高',
        impact: '当前分析结论可能失真，不建议直接用于经营决策',
        action: '优先检查失败步骤输入数据并重新生成分析',
        sla: '24小时内',
      });
    }
    if (lowConfidenceEdges.length > 0) {
      items.push({
        title: `存在 ${lowConfidenceEdges.length} 条低可信数据关系`,
        owner: '数据治理负责人',
        priority: failedEvents.length > 0 ? '中' : '高',
        impact: '部分指标关系解释不充分，结论可信度下降',
        action: '补齐结构化证据并完成低可信关系修复',
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

  /** 资产血缘力导向图（与物化/推导接口字段一致） */
  const lineageGraphChartOption = useMemo(() => {
    const nodes = graph?.nodes || [];
    const edges = graph?.edges || [];
    if (!nodes.length && !edges.length) {
      return null;
    }
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
      const label = n?.name || key;
      const short = label.length > 18 ? `${label.slice(0, 16)}…` : label;
      return {
        id: key,
        name: short,
        category: catIndex(n?.nodeType),
        symbolSize: n?.nodeType === 'chart' ? 40 : n?.nodeType === 'dataset' ? 32 : 26,
        tooltip: {
          formatter: `${n?.nodeType || 'unknown'}<br/>${key}`,
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

  return (
    <div>
      <Typography.Title level={4}>分析可信度与问题追踪中心</Typography.Title>
      <Typography.Paragraph type="secondary" style={{ marginTop: -8 }}>
        帮助你判断本次分析是否可信、问题在哪、该由谁处理。业务同学先看“问题与建议”，排查时再看技术详情。
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
                    <Descriptions.Item label="chartId">{trace.chartId}</Descriptions.Item>
                    <Descriptions.Item label="traceId">{trace.traceId}</Descriptions.Item>
                    <Descriptions.Item label="处理步骤">{trace.events?.length || 0}</Descriptions.Item>
                    <Descriptions.Item label="待处理问题">{pendingFixCount}</Descriptions.Item>
                  </Descriptions>
                  {!!failedEvents.length && (
                    <Alert
                      style={{ marginTop: 8 }}
                      type="error"
                      showIcon
                      message={`当前存在失败步骤：${failedEvents[0]?.eventType || '未知步骤'}`}
                    />
                  )}
                </Card>
              </Col>
            </Row>
            <Tabs
              items={[
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
                  label: '数据关系（高级）',
                  children: (
                    <Card>
                      <Descriptions size="small" column={2} style={{ marginBottom: 8 }}>
                        <Descriptions.Item label="节点数">{graph?.nodes?.length || 0}</Descriptions.Item>
                        <Descriptions.Item label="关系边数">{graph?.edges?.length || 0}</Descriptions.Item>
                      </Descriptions>
                      {lineageGraphChartOption ? (
                        <>
                          <Typography.Paragraph type="secondary" style={{ marginBottom: 8 }}>
                            力导向图：可缩放、拖拽节点；边颜色表示可信度（绿高 / 橙中 / 红低）。
                          </Typography.Paragraph>
                          <ReactECharts option={lineageGraphChartOption} style={{ height: 440, width: '100%' }} />
                          <Divider plain>关系明细</Divider>
                        </>
                      ) : (
                        <Typography.Paragraph type="secondary">暂无图谱数据，请先完成分析并加载血缘。</Typography.Paragraph>
                      )}
                      <List
                        size="small"
                        dataSource={graph?.edges || []}
                        locale={{ emptyText: '暂无关系边' }}
                        renderItem={(edge) => (
                          <List.Item>
                            <Space direction="vertical" size={2}>
                              <Typography.Text>{`${edge.fromNodeKey} -> ${edge.toNodeKey}`}</Typography.Text>
                              <Space>
                                <Tag>{`关系类型：${edge.edgeType}`}</Tag>
                                <Tag color="blue">{`证据来源：${edge.evidenceSource || 'unknown'}`}</Tag>
                                <Tag color={edge.confidence === 'high' ? 'success' : edge.confidence === 'low' ? 'error' : 'warning'}>
                                  {`可信度：${edge.confidence || 'medium'}`}
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
                              <Typography.Text>{`${task.fromNodeKey} -> ${task.toNodeKey}`}</Typography.Text>
                              <Space>
                                <Tag>{task.edgeType}</Tag>
                                <Tag color={task.status === 'resolved' ? 'success' : 'warning'}>
                                  {task.status === 'resolved' ? '已修复' : '待处理'}
                                </Tag>
                                <Tag>{task.evidenceSource || 'unknown'}</Tag>
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

