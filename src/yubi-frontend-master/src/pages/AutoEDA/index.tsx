import React, { useState, useRef, useEffect } from 'react';
import { PageContainer } from '@ant-design/pro-components';
import {
  Card, Button, Select, InputNumber, Form, Space, Steps, Statistic,
  Tag, List, Typography, Spin, Progress, message, Alert
} from 'antd';
import {
  PlayCircleOutlined, PauseCircleOutlined, BulbOutlined,
  LineChartOutlined, LoadingOutlined
} from '@ant-design/icons';
import { history } from '@umijs/max';
import {
  startExplorationUsingPOST, getStateUsingGET, stopExplorationUsingPOST,
  getReportUsingGET
} from '@/services/yubi/autoEdaController';
import type { ExplorationState, HypothesisItem } from '@/services/yubi/autoEdaController';
import './index.less';

const { Text, Paragraph } = Typography;

const AutoEDA: React.FC = () => {
  const [sessionId, setSessionId] = useState<string | null>(null);
  const [state, setState] = useState<ExplorationState | null>(null);
  const [loading, setLoading] = useState(false);
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const [chartId, setChartId] = useState<number | undefined>(undefined);
  const [maxBudget, setMaxBudget] = useState(30);

  const startPolling = (sid: string) => {
    pollRef.current = setInterval(async () => {
      try {
        const res = await getStateUsingGET(sid);
        if (res?.data) {
          setState(res.data);
          if (res.data.status === 'COMPLETED' || res.data.status === 'STOPPED' || res.data.status === 'FAILED') {
            if (pollRef.current) clearInterval(pollRef.current);
          }
        }
      } catch { /* ignore polling errors */ }
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
      }
    } catch { message.error('启动探索失败'); }
    finally { setLoading(false); }
  };

  const handleStop = async () => {
    if (!sessionId) return;
    await stopExplorationUsingPOST(sessionId);
    if (pollRef.current) clearInterval(pollRef.current);
  };

  const handleViewReport = () => {
    if (sessionId) history.push(`/insight-report/${sessionId}`);
  };

  useEffect(() => {
    return () => { if (pollRef.current) clearInterval(pollRef.current); };
  }, []);

  const isRunning = state?.status === 'RUNNING';
  const isDone = state?.status === 'COMPLETED';
  const progress = state?.queriesExecuted && state?.queryBudget
    ? Math.round((state.queriesExecuted / state.queryBudget) * 100) : 0;

  return (
    <PageContainer>
      {!sessionId && (
        <Card title="启动自主数据探索" style={{ maxWidth: 600 }}>
          <Form layout="vertical">
            <Form.Item label="选择数据图表" required>
              <Select
                showSearch
                placeholder="选择智能分析生成的图表"
                value={chartId}
                onChange={setChartId}
              />
            </Form.Item>
            <Form.Item label="探索查询预算（次）">
              <InputNumber min={5} max={100} value={maxBudget} onChange={v => setMaxBudget(v || 30)} />
            </Form.Item>
            <Button type="primary" icon={<PlayCircleOutlined />} onClick={handleStart} loading={loading} block>
              开始自主探索
            </Button>
          </Form>
        </Card>
      )}

      {sessionId && state && (
        <>
          <Card title="探索进行中" style={{ marginBottom: 16 }}>
            <Space direction="vertical" style={{ width: '100%' }}>
              <Progress percent={progress} status={isRunning ? 'active' : 'success'} />
              <Space>
                <Tag color={isRunning ? 'processing' : isDone ? 'success' : 'default'}>
                  {state.status === 'RUNNING' ? <><LoadingOutlined /> 探索中</> : state.status}
                </Tag>
                <Text>已执行查询: {state.queriesExecuted || 0} 次</Text>
                <Text>已验证假设: {state.verifiedHypothesisCount || 0} 个</Text>
                <Text>待验证: {state.pendingHypothesisCount || 0} 个</Text>
              </Space>
              {state.lastMaxIg != null && (
                <Text type="secondary">当前信息增益: {state.lastMaxIg.toFixed(4)} {state.converged ? '(已收敛)' : ''}</Text>
              )}
              {isRunning && (
                <Button icon={<PauseCircleOutlined />} onClick={handleStop} danger>
                  停止探索
                </Button>
              )}
              {isDone && (
                <Button type="primary" icon={<BulbOutlined />} onClick={handleViewReport}>
                  查看分析报告
                </Button>
              )}
              {state.status === 'FAILED' && (
                <Alert type="error" message="探索过程中发生错误，请重试" showIcon />
              )}
            </Space>
          </Card>

          {state.activeHypotheses && state.activeHypotheses.length > 0 && (
            <Card title="活跃假设" style={{ marginBottom: 16 }}>
              <List
                dataSource={state.activeHypotheses}
                renderItem={(h: HypothesisItem) => (
                  <List.Item>
                    <List.Item.Meta
                      avatar={<BulbOutlined style={{ color: '#faad14' }} />}
                      title={
                        <Space>
                          <Tag>{h.type}</Tag>
                          <Text strong>{h.statement}</Text>
                          <Tag color="blue">
                            后验 {h.confidencePosterior ? (h.confidencePosterior * 100).toFixed(0) : '?'}%
                          </Tag>
                        </Space>
                      }
                      description={h.lineagePath ? `血缘路径: ${h.lineagePath}` : undefined}
                    />
                  </List.Item>
                )}
              />
            </Card>
          )}

          {state.suggestedNextDirections && state.suggestedNextDirections.length > 0 && (
            <Card title="探索建议" size="small">
              {state.suggestedNextDirections.map((s, i) => (
                <Paragraph key={i} type="secondary">💡 {s}</Paragraph>
              ))}
            </Card>
          )}
        </>
      )}
    </PageContainer>
  );
};

export default AutoEDA;
