import React, { useState, useRef, useEffect } from 'react';
import { PageContainer, ProCard } from '@ant-design/pro-components';
import {
  Button, Select, InputNumber, Form, Space, Steps,
  Tag, List, Typography, Spin, Progress, message, Alert, Row, Col, Tooltip, Empty,
  Descriptions, Slider, Divider,
} from 'antd';
import {
  PlayCircleOutlined, PauseCircleOutlined, BulbOutlined,
  LoadingOutlined, CompassOutlined, LineChartOutlined, ExperimentOutlined,
  CopyOutlined, CheckCircleOutlined, RocketOutlined, DatabaseOutlined,
  NodeIndexOutlined, FileSearchOutlined, HistoryOutlined,
} from '@ant-design/icons';
import { history, useSearchParams, Link } from '@umijs/max';
import {
  startExplorationUsingPOST, getStateUsingGET, stopExplorationUsingPOST,
  listExplorableChartsUsingGET,
} from '@/services/yubi/autoEdaController';
import type { ExplorationState, HypothesisItem, AutoEdaChartOption } from '@/services/yubi/autoEdaController';
import { getChartByIdUsingGET } from '@/services/yubi/chartController';
import type { API } from '@/services/yubi/typings';
import { translateHypothesisType, explorationStatusLabel } from '@/utils/explorationLabels';
import './index.less';

const { Text, Paragraph, Title } = Typography;

const confidenceColor = (posterior: number | undefined) => {
  const pct = posterior != null ? posterior * 100 : 0;
  if (pct > 75) return 'success';
  if (pct > 40) return 'warning';
  return 'error';
};

const confidenceLabel = (posterior: number | undefined) => {
  const pct = posterior != null ? Math.round(posterior * 100) : 0;
  return `${pct}%`;
};

const formatChartId = (id?: number) => {
  if (id == null) return '-';
  const s = String(id);
  if (s.length <= 14) return s;
  return `${s.slice(0, 6)}…${s.slice(-4)}`;
};

const statusMeta = (status?: string) => {
  switch (status) {
    case 'succeed':
      return { label: '就绪', color: 'success' as const };
    case 'running':
      return { label: '生成中', color: 'processing' as const };
    case 'failed':
      return { label: '失败', color: 'error' as const };
    default:
      return { label: status || '未知', color: 'default' as const };
  }
};

const AutoEDA: React.FC = () => {
  const [searchParams] = useSearchParams();
  const urlChartId = searchParams.get('chartId');

  const [sessionId, setSessionId] = useState<string | null>(null);
  const [state, setState] = useState<ExplorationState | null>(null);
  const [loading, setLoading] = useState(false);
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const [chartId, setChartId] = useState<number | undefined>(
    urlChartId ? Number(urlChartId) : undefined,
  );
  const [maxBudget, setMaxBudget] = useState(30);
  const [chartInfo, setChartInfo] = useState<API.Chart | null>(null);
  const [chartOptions, setChartOptions] = useState<{ label: string; value: number; chart?: AutoEdaChartOption }[]>([]);
  const [chartsLoading, setChartsLoading] = useState(false);

  const loadChartInfo = async (id: number) => {
    try {
      const res = await getChartByIdUsingGET({ id });
      if (res?.data) setChartInfo(res.data);
    } catch {
      message.warning('无法加载图表详情');
    }
  };

  const formatChartLabel = (c: AutoEdaChartOption) => {
    const name = c.name || `图表 #${c.id}`;
    const statusTag = c.status === 'succeed' ? '' : ` · ${c.status || '未知'}`;
    return `${name}${statusTag}`;
  };

  const loadChartList = async () => {
    setChartsLoading(true);
    try {
      const res = await listExplorableChartsUsingGET();
      const records = res?.data ?? [];
      const options = records
        .filter((c) => c.id != null)
        .map((c) => ({
          label: formatChartLabel(c),
          value: c.id as number,
          chart: c,
        }));
      setChartOptions(options);
      if (options.length === 0) {
        message.info('暂无图表，请先在「实时生成」或「异步生成」中创建分析图表');
      }
    } catch (e: unknown) {
      const errMsg = e instanceof Error ? e.message : '未知错误';
      message.error(`加载图表列表失败：${errMsg}`);
    } finally {
      setChartsLoading(false);
    }
  };

  const applyChartSelection = (id: number) => {
    setChartId(id);
    const matched = chartOptions.find((o) => o.value === id);
    if (matched?.chart) {
      setChartInfo({
        id: matched.chart.id,
        name: matched.chart.name,
        goal: matched.chart.goal,
        chartType: matched.chart.chartType,
        status: matched.chart.status,
      } as API.Chart);
    } else {
      loadChartInfo(id);
    }
  };

  const copyChartId = async () => {
    if (chartInfo?.id == null) return;
    try {
      await navigator.clipboard.writeText(String(chartInfo.id));
      message.success('图表 ID 已复制');
    } catch {
      message.error('复制失败');
    }
  };

  useEffect(() => {
    loadChartList();
  }, []);

  useEffect(() => {
    if (chartId) applyChartSelection(chartId);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [chartId, chartOptions]);

  useEffect(() => {
    if (urlChartId && !Number.isNaN(Number(urlChartId))) {
      setChartId(Number(urlChartId));
    }
  }, [urlChartId]);

  const startPolling = (sid: string) => {
    if (pollRef.current) clearInterval(pollRef.current);
    pollRef.current = setInterval(async () => {
      try {
        const res = await getStateUsingGET(sid);
        if (res?.data) {
          setState(res.data);
          if (['COMPLETED', 'STOPPED', 'FAILED'].includes(res.data.status || '')) {
            if (pollRef.current) clearInterval(pollRef.current);
          }
        }
      } catch { /* ignore */ }
    }, 2000);
  };

  const handleStart = async () => {
    if (!chartId) { message.warning('请选择要分析的数据图表'); return; }
    setLoading(true);
    try {
      const res = await startExplorationUsingPOST({ chartId, maxQueryBudget: maxBudget });
      if (res?.data?.sessionId) {
        setSessionId(res.data.sessionId);
        startPolling(res.data.sessionId);
        message.success('自主探索已启动');
      }
    } catch {
      message.error('启动探索失败，请确认后端与 ClickHouse 已启动');
    } finally {
      setLoading(false);
    }
  };

  const handleStop = async () => {
    if (!sessionId) return;
    await stopExplorationUsingPOST(sessionId);
    if (pollRef.current) clearInterval(pollRef.current);
    message.info('探索已停止');
  };

  const handleViewReport = () => {
    if (sessionId) history.push(`/lineage/report/${sessionId}`);
  };

  useEffect(() => {
    return () => { if (pollRef.current) clearInterval(pollRef.current); };
  }, []);

  const isRunning = state?.status === 'RUNNING';
  const isDone = state?.status === 'COMPLETED';
  const budget = state?.queryBudget || maxBudget;
  const progress = state?.queriesExecuted != null
    ? Math.min(100, Math.round((state.queriesExecuted / budget) * 100))
    : 0;
  const currentStep = isDone ? 2 : isRunning ? 1 : 0;
  const st = statusMeta(chartInfo?.status);

  return (
    <PageContainer
      className="auto-eda-page-container"
      header={{
        title: '智能探索',
        subTitle: '结合数据血缘，自动查数、归纳结论并生成报告',
      }}
      extra={[
        <Link key="history" to="/lineage/history">
          <Button icon={<HistoryOutlined />}>探索记录</Button>
        </Link>,
      ]}
    >
      <div className="auto-eda-page">
        {/* 启动前：双栏工作台 */}
        {!sessionId && (
          <Row gutter={[20, 20]} className="auto-eda-launch-grid">
            <Col xs={24} lg={14}>
              <ProCard className="auto-eda-panel config-panel" bordered>
                <div className="panel-head">
                  <RocketOutlined className="panel-head-icon" />
                  <div>
                    <div className="panel-title">启动探索</div>
                    <div className="panel-desc">选择一张分析图表，系统会自动查数据并整理结论</div>
                  </div>
                </div>
                <Divider className="panel-divider" />
                <Form layout="vertical" className="config-form">
                  <Form.Item label="数据图表" required>
                    <Select
                      size="large"
                      showSearch
                      loading={chartsLoading}
                      placeholder="选择已生成的分析图表"
                      value={chartId}
                      onChange={applyChartSelection}
                      options={chartOptions}
                      optionFilterProp="label"
                      notFoundContent={
                        chartsLoading
                          ? <Spin size="small" />
                          : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无图表" />
                      }
                    />
                  </Form.Item>
                  <Form.Item
                    label={`最多查 ${maxBudget} 次数据`}
                    extra="次数越多结论越细，但耗时更长"
                  >
                    <div className="budget-control">
                      <Slider
                        min={5}
                        max={50}
                        value={maxBudget}
                        onChange={setMaxBudget}
                        marks={{ 5: '5', 20: '20', 30: '30', 50: '50' }}
                      />
                      <InputNumber
                        min={5}
                        max={50}
                        value={maxBudget}
                        onChange={(v) => setMaxBudget(v || 30)}
                        className="budget-input"
                      />
                    </div>
                  </Form.Item>
                  <Button
                    type="primary"
                    size="large"
                    block
                    icon={<PlayCircleOutlined />}
                    onClick={handleStart}
                    loading={loading}
                    disabled={!chartId}
                    className="start-btn"
                  >
                    开始智能探索
                  </Button>
                </Form>
              </ProCard>
            </Col>

            <Col xs={24} lg={10}>
              <ProCard className="auto-eda-panel preview-panel" bordered>
                {chartInfo ? (
                  <>
                    <div className="preview-hero">
                      <div className="preview-icon-wrap">
                        <CompassOutlined />
                      </div>
                      <div className="preview-title-block">
                        <Title level={4} className="preview-title">
                          {chartInfo.name || '未命名图表'}
                        </Title>
                        <Space size={6} wrap>
                          {chartInfo.chartType && (
                            <Tag color="blue" bordered={false}>{chartInfo.chartType}</Tag>
                          )}
                          <Tag color={st.color} bordered={false}>{st.label}</Tag>
                        </Space>
                      </div>
                    </div>
                    {chartInfo.goal && (
                      <div className="preview-goal">
                        <Text type="secondary" className="preview-goal-label">分析目标</Text>
                        <Paragraph className="preview-goal-text" ellipsis={{ rows: 4, expandable: true, symbol: '展开' }}>
                          {chartInfo.goal}
                        </Paragraph>
                      </div>
                    )}
                    <Descriptions
                      column={1}
                      size="small"
                      className="preview-meta"
                      items={[
                        {
                          key: 'id',
                          label: '图表 ID',
                          children: (
                            <Space size={4} className="chart-id-row">
                              <Tooltip title={String(chartInfo.id)}>
                                <Text code className="chart-id-text">{formatChartId(chartInfo.id)}</Text>
                              </Tooltip>
                              <Tooltip title="复制完整 ID">
                                <Button type="text" size="small" icon={<CopyOutlined />} onClick={copyChartId} />
                              </Tooltip>
                            </Space>
                          ),
                        },
                        {
                          key: 'budget',
                          label: '本次预算',
                          children: <Text strong>{maxBudget} 次查询</Text>,
                        },
                      ]}
                    />
                  </>
                ) : (
                  <div className="preview-empty">
                    <FileSearchOutlined />
                    <Text type="secondary">选择左侧图表后，此处显示分析目标与元信息</Text>
                  </div>
                )}
                <Divider className="panel-divider" />
                <div className="how-it-works">
                  <Text type="secondary" className="how-label">探索流程</Text>
                  <div className="how-steps">
                    <div className="how-step"><DatabaseOutlined /><span>读取经营数据</span></div>
                    <div className="how-step"><NodeIndexOutlined /><span>按数据关系逐层下钻</span></div>
                    <div className="how-step"><BulbOutlined /><span>整理结论并生成报告</span></div>
                  </div>
                </div>
              </ProCard>
            </Col>
          </Row>
        )}

        {/* 探索进行中 */}
        {sessionId && state && (
          <>
            <ProCard className="auto-eda-panel progress-panel" bordered>
              <div className="progress-header">
                <Space>
                  {isRunning && <LoadingOutlined spin style={{ color: '#1677ff', fontSize: 18 }} />}
                  {isDone && <CheckCircleOutlined style={{ color: '#52c41a', fontSize: 18 }} />}
                  <Title level={5} style={{ margin: 0 }}>
                    {isDone ? '分析完成' : isRunning ? '正在分析' : (explorationStatusLabel[state.status || ''] || state.status)}
                  </Title>
                  <Tag color={isRunning ? 'processing' : isDone ? 'success' : 'default'}>
                    {state.queriesExecuted || 0} / {budget} 查询
                  </Tag>
                </Space>
                <Space>
                  {isRunning && (
                    <Button icon={<PauseCircleOutlined />} onClick={handleStop} danger size="small">
                      停止
                    </Button>
                  )}
                  {isDone && (
                <Space>
                  <Button type="primary" icon={<BulbOutlined />} onClick={handleViewReport}>
                    查看报告
                  </Button>
                  <Button icon={<HistoryOutlined />} onClick={() => history.push('/lineage/history')}>
                    探索记录
                  </Button>
                </Space>
              )}
                </Space>
              </div>
              <Steps
                current={currentStep}
                size="small"
                className="progress-steps"
                items={[
                  { title: '准备', description: '加载图表与数据' },
                  { title: '自动查数', description: '逐条核对指标' },
                  { title: '出报告', description: '整理可读结论' },
                ]}
              />
              <Progress
                percent={progress}
                status={isRunning ? 'active' : isDone ? 'success' : 'normal'}
                strokeColor={isDone ? '#52c41a' : { from: '#1677ff', to: '#722ed1' }}
                className="progress-bar"
              />
              <Row gutter={[12, 12]} className="progress-stats">
                <Col xs={12} sm={6}>
                  <div className="stat-chip">
                    <LineChartOutlined />
                    <div>
                      <div className="stat-value">{state.queriesExecuted || 0}</div>
                      <div className="stat-label">已查数据次数</div>
                    </div>
                  </div>
                </Col>
                <Col xs={12} sm={6}>
                  <div className="stat-chip stat-chip--success">
                    <ExperimentOutlined />
                    <div>
                      <div className="stat-value">{state.verifiedHypothesisCount || 0}</div>
                      <div className="stat-label">已确认结论</div>
                    </div>
                  </div>
                </Col>
                <Col xs={12} sm={6}>
                  <div className="stat-chip">
                    <BulbOutlined />
                    <div>
                      <div className="stat-value">{state.pendingHypothesisCount || 0}</div>
                      <div className="stat-label">待核实</div>
                    </div>
                  </div>
                </Col>
                <Col xs={12} sm={6}>
                  <div className="stat-chip">
                    <CompassOutlined />
                    <div>
                      <div className="stat-value">
                        {state.lastMaxIg != null ? state.lastMaxIg.toFixed(3) : '-'}
                      </div>
                      <div className="stat-label">
                        关注程度{state.converged ? ' · 已足够' : ''}
                      </div>
                    </div>
                  </div>
                </Col>
              </Row>
              {state.status === 'FAILED' && (
                <Alert
                  type="error"
                  showIcon
                  className="progress-alert"
                  message="探索失败"
                  description={
                    state.errorMessage
                      ? `${state.errorMessage}。请确认：① 已执行 auto_eda_schema.sql；② ClickHouse 已启动；③ 已调用 POST /api/bigdata/sync/metrics 同步数据。`
                      : '请确认 ClickHouse 已启动且 store_daily_metric_ch 表有数据，并已执行 auto_eda_schema.sql'
                  }
                />
              )}
            </ProCard>

            {state.activeHypotheses && state.activeHypotheses.length > 0 && (
              <ProCard title="正在跟进的分析点" className="auto-eda-panel" bordered style={{ marginTop: 16 }}>
                <List
                  className="hypothesis-list"
                  dataSource={state.activeHypotheses}
                  renderItem={(h: HypothesisItem) => (
                    <List.Item className="hypothesis-item">
                      <div className="hypothesis-card">
                        <div className="hypothesis-card-head">
                          <Tag color="geekblue">{translateHypothesisType(h.type)}</Tag>
                          <Tag color={confidenceColor(h.confidencePosterior)}>
                            可信度 {confidenceLabel(h.confidencePosterior)}
                          </Tag>
                        </div>
                        <Text strong className="hypothesis-statement">{h.statement}</Text>
                        {h.lineagePath && (
                          <Text type="secondary" className="hypothesis-lineage" ellipsis>
                            数据路径：{h.lineagePath}
                          </Text>
                        )}
                      </div>
                    </List.Item>
                  )}
                />
              </ProCard>
            )}

            {state.suggestedNextDirections && state.suggestedNextDirections.length > 0 && (
              <div className="suggestions-wrap">
                {state.suggestedNextDirections.map((s, i) => (
                  <Alert key={i} type="info" showIcon icon={<BulbOutlined />} message={s} />
                ))}
              </div>
            )}
          </>
        )}
      </div>
    </PageContainer>
  );
};

export default AutoEDA;
