import { deleteChartUsingPOST, listMyChartByPageUsingPOST } from '@/services/yubi/chartController';
import { request, useNavigate } from '@umijs/max';

import { useModel } from '@@/exports';
import { MoreOutlined } from '@ant-design/icons';
import { Alert, Avatar, Button, Card, Col, Descriptions, Divider, Dropdown, List, Segmented, Space, Tooltip, message, Modal, Result, Row, Spin, Tag, Tabs, Timeline, Typography } from 'antd';
import type { MenuProps } from 'antd';
import ReactECharts from 'echarts-for-react';
import React, { useEffect, useRef, useState } from 'react';
import Search from "antd/es/input/Search";

/**
 * 图表管理与结果回看页面
 * @constructor
 */
const MyChartPage: React.FC = () => {
  const navigate = useNavigate();
  const parseChartOptionSafe = (raw: string | undefined) => {
    if (!raw?.trim()) return undefined;
    try {
      const option = JSON.parse(raw) as Record<string, unknown>;
      const series = (option as any)?.series;
      const hasValidSeries =
        (Array.isArray(series) && series.some((item) => item && typeof item === 'object')) ||
        (!!series && !Array.isArray(series) && typeof series === 'object');
      if (!hasValidSeries) return undefined;
      return option;
    } catch {
      return undefined;
    }
  };

  type LineageEvent = {
    id: number;
    eventType: string;
    status: string;
    inputDigest?: string;
    outputDigest?: string;
    logicRef?: string;
    ruleVersion?: string;
    createTime?: string | Date;
    eventMeta?: string;
    operatorId?: number;
  };

  type ExplainSnapshot = {
    id: number;
    explainType: string;
    promptVersion?: string;
    modelName?: string;
    explainData?: string;
  };

  type LineageTraceData = {
    traceId: string;
    chartId: number;
    events: LineageEvent[];
    explanations: ExplainSnapshot[];
    dataTimeRange?: string;
    dataFingerprint?: string;
    resultFingerprint?: string;
    dataChanged?: boolean;
    differenceReason?: string;
  };

  type LineageGraphNode = {
    nodeKey: string;
    nodeType: string;
    name: string;
    status?: string;
  };

  type LineageGraphEdge = {
    fromNodeKey: string;
    toNodeKey: string;
    edgeType: string;
    evidenceSource?: string;
    confidence?: string;
    evidenceDetail?: string;
    fixSuggestion?: string;
    eventMetaTemplate?: string;
  };

  type LineageGraphData = {
    traceId: string;
    chartId: number;
    nodes: LineageGraphNode[];
    edges: LineageGraphEdge[];
  };

  type LineageImpactItem = {
    chartId: number;
    chartName: string;
    reason: string;
  };

  type LineageImpactData = {
    sourceChartId: number;
    impactedCount: number;
    impactedCharts: LineageImpactItem[];
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
    eventMetaTemplate?: string;
    status: string;
  };

  const initSearchParams = {
    current: 1,
    pageSize: 4,
    sortField: 'createTime',
    sortOrder: 'desc',
  };

  const [searchParams, setSearchParams] = useState<API.ChartQueryRequest>({ ...initSearchParams });
  const [searchKeyword, setSearchKeyword] = useState<string>('');
  const { initialState } = useModel('@@initialState');
  const { currentUser } = initialState ?? {};
  const [chartList, setChartList] = useState<API.Chart[]>([]);
  const [total, setTotal] = useState<number>(0);
  const [loading, setLoading] = useState<boolean>(true);
  const [deletingIds, setDeletingIds] = useState<Record<string, boolean>>({});
  const [removingIds, setRemovingIds] = useState<string[]>([]);
  const [lineageVisible, setLineageVisible] = useState(false);
  const [lineageLoading, setLineageLoading] = useState(false);
  const [lineageRerunLoading, setLineageRerunLoading] = useState(false);
  const [lineageChartId, setLineageChartId] = useState<string>('');
  const [lineageData, setLineageData] = useState<LineageTraceData | undefined>(undefined);
  const [lineageGraphData, setLineageGraphData] = useState<LineageGraphData | undefined>(undefined);
  const [lineageGraphLoading, setLineageGraphLoading] = useState(false);
  const [lineageImpactData, setLineageImpactData] = useState<LineageImpactData | undefined>(undefined);
  const [lineageFixTasks, setLineageFixTasks] = useState<LineageFixTask[]>([]);
  const [fixTaskUpdatingId, setFixTaskUpdatingId] = useState<number | undefined>(undefined);
  const [fixTaskStatusFilter, setFixTaskStatusFilter] = useState<'all' | 'pending' | 'resolved'>('all');
  const [fixTaskSourceFilter, setFixTaskSourceFilter] = useState<'all' | 'event_meta' | 'text_parse' | 'system'>('all');
  const [graphDirection, setGraphDirection] = useState<'all' | 'upstream' | 'downstream'>('all');
  const [graphConfidenceFilter, setGraphConfidenceFilter] = useState<'all' | 'low'>('all');
  const [selectedEdge, setSelectedEdge] = useState<LineageGraphEdge | undefined>(undefined);
  const chartRefs = useRef<Record<string, ReactECharts | null>>({});
  const listPollingTimerRef = useRef<number | undefined>(undefined);
  const listPollingStartedAtRef = useRef<number>(0);
  const listPollingFailureCountRef = useRef<number>(0);
  const loadDataRef = useRef<() => Promise<void>>(async () => {});

  /**
   * 将图表 id 统一转成字符串，避免 number / string 类型不匹配；
   * 同时减少长整型 id 在前端比较时带来的问题。
   */
  const getChartIdString = (id: unknown) => {
    if (id === undefined || id === null) {
      return '';
    }
    return String(id).trim();
  };

  const loadData = async () => {
    setLoading(true);
    try {
      const res = await listMyChartByPageUsingPOST(searchParams);
      if (res.data) {
        const records = res.data.records ?? [];
        // 隐藏图表的 title
        records.forEach((data) => {
          if (data.status === 'succeed') {
            const chartOption = parseChartOptionSafe(data.genChart);
            if (chartOption) {
              (chartOption as any).title = undefined;
              data.genChart = JSON.stringify(chartOption);
            } else {
              data.genChart = '';
            }
          }
        });
        setChartList(records);
        setTotal(res.data.total ?? 0);
      } else {
        message.error('获取图表管理与结果回看列表失败');
      }
    } catch (e: any) {
      message.error('获取图表管理与结果回看列表失败，' + e.message);
    }
    setLoading(false);
  };

  const applySearch = (rawValue: string) => {
    const keyword = (rawValue || '').trim();
    setSearchKeyword(rawValue);
    if (!keyword) {
      // 关键词为空时恢复总览
      setSearchParams({ ...initSearchParams });
      return;
    }
    setSearchParams({
      ...initSearchParams,
      name: keyword,
    });
  };

  useEffect(() => {
    loadDataRef.current = loadData;
  });

  useEffect(() => {
    loadData();
  }, [searchParams]);

  useEffect(() => {
    const hasPending = (chartList || []).some((item) => item.status === 'wait' || item.status === 'running');
    const stopPolling = () => {
      if (listPollingTimerRef.current !== undefined) {
        window.clearInterval(listPollingTimerRef.current);
        listPollingTimerRef.current = undefined;
      }
      listPollingStartedAtRef.current = 0;
      listPollingFailureCountRef.current = 0;
    };

    // 仅在列表存在 wait / running 时启用轮询刷新，避免无谓请求。
    if (!hasPending) {
      stopPolling();
      return;
    }
    if (listPollingTimerRef.current !== undefined) {
      return;
    }

    listPollingStartedAtRef.current = Date.now();
    listPollingFailureCountRef.current = 0;

    const intervalMs = 1500;
    const maxDurationMs = 2 * 60 * 1000; // 2 min
    const maxFailureCount = 3;

    listPollingTimerRef.current = window.setInterval(async () => {
      // 超时停止，避免后台任务过久导致一直刷接口。
      if (Date.now() - listPollingStartedAtRef.current > maxDurationMs) {
        stopPolling();
        message.info('后台任务仍在执行中，已暂停自动刷新；你可以稍后手动刷新查看最新状态');
        return;
      }
      // 避免与用户翻页 / 搜索等触发的 loadData 并发。
      if (loading) return;
      try {
        await loadDataRef.current();
        listPollingFailureCountRef.current = 0;
      } catch {
        listPollingFailureCountRef.current += 1;
        if (listPollingFailureCountRef.current >= maxFailureCount) {
          stopPolling();
          message.warning('网络波动导致自动刷新暂停，请稍后手动刷新');
        }
      }
    }, intervalMs);

    return () => {
      stopPolling();
    };
  }, [chartList, loading]);

  /**
   * 根据按钮上的 data-chart-id 安全获取图表 id。
   * 这样即使卡片是动态渲染的，后续新增的删除按钮也能正常工作。
   */
  const getChartIdFromEvent = (event: React.MouseEvent<HTMLElement>) => {
    const chartId = event.currentTarget.getAttribute('data-chart-id') ?? '';
    return chartId.trim();
  };

  /**
   * 删除成功后做一个轻量过渡，再从列表中移除卡片。
   */
  const removeChartCard = (chartId: string) => {
    setRemovingIds((prev) => [...prev, chartId]);
    window.setTimeout(() => {
      setChartList((prev) => prev.filter((item) => getChartIdString(item.id) !== chartId));
      setRemovingIds((prev) => prev.filter((id) => id !== chartId));
      delete chartRefs.current[chartId];
      setTotal((prev) => Math.max(0, prev - 1));
    }, 220);
  };

  /**
   * 下载图表图片，优先使用 ECharts 实例导出 PNG。
   */
  const handleDownloadChart = (chartId: string) => {
    if (!chartId) {
      message.error('未获取到图表 id，请刷新页面后重试');
      return;
    }
    const chartRef = chartRefs.current[chartId];
    const chart = chartRef?.getEchartsInstance?.();
    if (!chart) {
      message.warning('当前图表暂不支持下载，请稍后重试');
      return;
    }
    try {
      const item = chartList.find((currentItem) => getChartIdString(currentItem.id) === chartId);
      const dataUrl = chart.getDataURL({
        type: 'png',
        pixelRatio: 2,
        backgroundColor: '#fff',
      });
      const link = document.createElement('a');
      link.href = dataUrl;
      link.download = `${item?.name || 'chart'}-${chartId}.png`;
      link.click();
      message.success('图片下载成功');
    } catch (e: any) {
      message.error('下载失败，' + (e?.message ?? '请稍后重试'));
    }
  };

  /**
   * 删除单个图表。既支持从按钮事件中读取 id，也支持直接传入 id。
   */
  const handleDeleteChart = (input: React.MouseEvent<HTMLElement> | string) => {
    const chartId = typeof input === 'string' ? input : getChartIdFromEvent(input);
    if (!chartId) {
      message.error('未获取到图表 id，请刷新页面后重试');
      return;
    }
    if (deletingIds[chartId]) {
      return;
    }
    Modal.confirm({
      title: '确定要删除该图表吗？',
      content: '删除后无法恢复',
      okText: '确认删除',
      cancelText: '取消',
      okButtonProps: {
        danger: true,
      },
      onOk: async () => {
        setDeletingIds((prev) => ({
          ...prev,
          [chartId]: true,
        }));
        try {
          const res = await deleteChartUsingPOST({
            id: chartId as any,
          } as API.DeleteRequest);
          if (!res.data) {
            message.error(res.message ?? '删除失败');
            return;
          }
          removeChartCard(chartId);
          message.success('删除成功');
        } catch (e: any) {
          message.error('删除失败，' + (e?.message ?? '请稍后重试'));
        } finally {
          setDeletingIds((prev) => ({
            ...prev,
            [chartId]: false,
          }));
        }
      },
    });
  };

  /**
   * 卡片右上角菜单项：统一承载下载和删除功能入口。
   */
  const getCardMenuItems = (chartId: string): MenuProps['items'] => [
    {
      key: 'viewResult',
      label: '查看结果',
      disabled: !chartId || removingIds.includes(chartId),
    },
    {
      key: 'download',
      label: '下载图片',
      disabled: removingIds.includes(chartId),
    },
    {
      key: 'delete',
      label: '删除图表',
      danger: true,
      disabled: !!deletingIds[chartId] || removingIds.includes(chartId),
    },
  ];

  const handleMenuClick = (chartId: string): MenuProps['onClick'] => ({ key, domEvent }) => {
    if (key === 'viewResult') {
      navigate(`/chart_result/${chartId}`);
      return;
    }
    if (key === 'download') {
      handleDownloadChart(chartId);
      return;
    }
    if (key === 'delete') {
      domEvent.preventDefault();
      handleDeleteChart(chartId);
    }
  };

  const getEventLabel = (eventType: string) => {
    const map: Record<string, string> = {
      SOURCE_INGESTED: '来源入库',
      DATA_CLEANED: '数据清洗',
      METRIC_COMPUTED: '指标计算',
      CHART_RENDERED: '图表生成',
      AI_INTERPRETED: 'AI 解释',
    };
    return map[eventType] || eventType;
  };

  const getEventColor = (eventType: string, status: string) => {
    if (status && status !== 'succeed') return 'red';
    const map: Record<string, string> = {
      SOURCE_INGESTED: 'blue',
      DATA_CLEANED: 'cyan',
      METRIC_COMPUTED: 'geekblue',
      CHART_RENDERED: 'purple',
      AI_INTERPRETED: 'green',
    };
    return map[eventType] || 'gray';
  };

  const getStatusTagColor = (status?: string) => {
    if (!status) return 'default';
    if (status === 'succeed') return 'success';
    if (status === 'running') return 'processing';
    if (status === 'wait') return 'warning';
    return 'error';
  };

  const getEvidenceSourceLabel = (source?: string) => {
    const s = (source || '').trim();
    if (!s) return 'unknown';
    const map: Record<string, string> = {
      system: '系统关系',
      text_parse: '文本推断',
      event_meta: '结构化',
    };
    return map[s] || s;
  };

  const getEvidenceSourceTagColor = (source?: string) => {
    const s = (source || '').trim();
    if (s === 'event_meta') return 'success';
    if (s === 'system') return 'blue';
    if (s === 'text_parse') return 'warning';
    return 'default';
  };

  const getConfidenceLabel = (confidence?: string) => {
    const c = (confidence || '').trim();
    if (!c) return 'medium';
    return c;
  };

  const getConfidenceTagColor = (confidence?: string) => {
    const c = getConfidenceLabel(confidence);
    if (c === 'high') return 'success';
    if (c === 'low') return 'error';
    return 'warning';
  };

  const getEdgeTypeLabel = (edgeType?: string) => {
    const t = (edgeType || '').trim();
    if (!t) return 'unknown';
    const map: Record<string, string> = {
      derive: '派生',
      read: '读取',
      write: '写入',
      use: '使用',
    };
    return map[t] ? `${map[t]}（${t}）` : t;
  };

  const handleCopyText = async (text: string, successMsg = '已复制') => {
    const value = (text || '').trim();
    if (!value) {
      message.warning('暂无可复制内容');
      return;
    }
    try {
      await navigator.clipboard.writeText(value);
      message.success(successMsg);
    } catch {
      // fallback
      const ta = document.createElement('textarea');
      ta.value = value;
      ta.style.position = 'fixed';
      ta.style.left = '-9999px';
      document.body.appendChild(ta);
      ta.select();
      document.execCommand('copy');
      document.body.removeChild(ta);
      message.success(successMsg);
    }
  };

  const formatLineageTime = (value?: string | Date) => {
    if (!value) return '-';
    const date = value instanceof Date ? value : new Date(value);
    if (Number.isNaN(date.getTime())) {
      return String(value);
    }
    const pad = (num: number) => String(num).padStart(2, '0');
    return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(
      date.getMinutes(),
    )}:${pad(date.getSeconds())}`;
  };

  const getFailureReason = (event: LineageEvent) => {
    const meta = (event.eventMeta || '').trim();
    if (meta) return meta;
    if (event.logicRef) return `逻辑节点 ${event.logicRef} 执行异常`;
    return '未记录具体错误原因';
  };

  const getLatestEventTime = (events: LineageEvent[]) => {
    const validDates = (events || [])
      .map((event) => (event.createTime ? new Date(event.createTime).getTime() : NaN))
      .filter((time) => !Number.isNaN(time));
    if (!validDates.length) return '-';
    return formatLineageTime(new Date(Math.max(...validDates)));
  };

  const handleOpenLineage = async (chartId: string) => {
    if (!chartId) {
      message.error('未获取到图表 id，请刷新后重试');
      return;
    }
    setLineageVisible(true);
    setLineageLoading(true);
    setLineageGraphLoading(true);
    setLineageChartId(chartId);
    setLineageData(undefined);
    setLineageGraphData(undefined);
    setLineageImpactData(undefined);
    setLineageFixTasks([]);
    setFixTaskStatusFilter('all');
    setFixTaskSourceFilter('all');
    setGraphDirection('all');
    setGraphConfidenceFilter('all');
    setSelectedEdge(undefined);
    try {
      const [traceRes, graphRes, impactRes, fixTaskRes] = await Promise.all([
        request<any>(`/api/lineage/chart/${chartId}/latest`, {
          method: 'GET',
          timeout: 15000,
        }),
        request<any>(`/api/lineage/graph/chart/${chartId}`, {
          method: 'GET',
          params: {
            depth: 2,
          },
          timeout: 15000,
        }).catch(() => undefined),
        request<any>(`/api/lineage/impact/chart/${chartId}`, {
          method: 'GET',
          timeout: 15000,
        }).catch(() => undefined),
        request<any>(`/api/lineage/fix-tasks/chart/${chartId}`, {
          method: 'GET',
          timeout: 15000,
        }).catch(() => undefined),
      ]);
      if (traceRes && typeof traceRes === 'object' && 'code' in traceRes && traceRes.code !== 0) {
        throw new Error(traceRes.message || '查询血缘链路失败');
      }
      const data = (traceRes && typeof traceRes === 'object' && 'data' in traceRes ? traceRes.data : traceRes) as LineageTraceData | undefined;
      if (!data?.traceId) {
        throw new Error('暂无可用血缘链路');
      }
      setLineageData(data);
      if (graphRes && typeof graphRes === 'object' && 'code' in graphRes && graphRes.code === 0) {
        setLineageGraphData(graphRes.data as LineageGraphData);
      } else if (graphRes && (!('code' in graphRes) || (graphRes as any).traceId)) {
        setLineageGraphData(graphRes as LineageGraphData);
      }
      if (impactRes && typeof impactRes === 'object' && 'code' in impactRes && impactRes.code === 0) {
        setLineageImpactData(impactRes.data as LineageImpactData);
      } else if (impactRes && (!('code' in impactRes) || (impactRes as any).sourceChartId)) {
        setLineageImpactData(impactRes as LineageImpactData);
      }
      if (fixTaskRes && typeof fixTaskRes === 'object' && 'code' in fixTaskRes && fixTaskRes.code === 0) {
        setLineageFixTasks((fixTaskRes.data || []) as LineageFixTask[]);
      } else if (Array.isArray(fixTaskRes)) {
        setLineageFixTasks(fixTaskRes as LineageFixTask[]);
      }
    } catch (e: any) {
      message.warning(`读取血缘链路失败：${e?.message || String(e)}`);
    } finally {
      setLineageLoading(false);
      setLineageGraphLoading(false);
    }
  };

  const handleFixTaskStatus = async (taskId: number, status: 'pending' | 'resolved') => {
    if (!taskId) return;
    setFixTaskUpdatingId(taskId);
    try {
      const res = await request<any>(`/api/lineage/fix-tasks/${taskId}/status`, {
        method: 'GET',
        params: { status },
        timeout: 10000,
      });
      if (res && typeof res === 'object' && 'code' in res && res.code !== 0) {
        throw new Error(res.message || '更新任务状态失败');
      }
      setLineageFixTasks((prev) =>
        prev.map((task) => (task.id === taskId ? { ...task, status } : task))
      );
      message.success(status === 'resolved' ? '已标记为已修复' : '已恢复为待处理');
    } catch (e: any) {
      message.error(`更新失败：${e?.message || String(e)}`);
    } finally {
      setFixTaskUpdatingId(undefined);
    }
  };

  const handleRerunLineage = async () => {
    if (!lineageChartId) {
      message.error('未获取到图表 id，暂无法复跑');
      return;
    }
    setLineageRerunLoading(true);
    try {
      const res = await request<any>('/api/chart/rerun/async', {
        method: 'POST',
        data: {
          chartId: lineageChartId,
        },
        timeout: 10000,
      });
      if (res && typeof res === 'object' && 'code' in res && res.code !== 0) {
        throw new Error(res.message || '复跑失败');
      }
      message.success('复跑任务已提交，正在轮询执行进度...');

      const sleep = (ms: number) => new Promise((resolve) => window.setTimeout(resolve, ms));
      const maxAttempts = 20;
      let finalStatus = '';
      let finalMessage = '';
      for (let attempt = 0; attempt < maxAttempts; attempt += 1) {
        await sleep(1500);
        const detailRes = await request<any>('/api/chart/get', {
          method: 'GET',
          params: {
            id: lineageChartId,
          },
          timeout: 8000,
        });
        const chart = (detailRes && typeof detailRes === 'object' && 'data' in detailRes ? detailRes.data : detailRes) as
          | API.Chart
          | undefined;
        const status = chart?.status || '';
        const execMessage = chart?.execMessage || '';
        if (status === 'succeed' || status === 'failed') {
          finalStatus = status;
          finalMessage = execMessage;
          if (status === 'failed') {
            break;
          }
          // 复跑成功时，额外确认链路已生成 AI 节点，避免读到“成功但 explain 尚未落库”的瞬时状态。
          const lineageRes = await request<any>(`/api/lineage/chart/${lineageChartId}/latest`, {
            method: 'GET',
            timeout: 8000,
          });
          const latestLineage = (lineageRes && typeof lineageRes === 'object' && 'data' in lineageRes
            ? lineageRes.data
            : lineageRes) as LineageTraceData | undefined;
          const hasAiEvent = !!(latestLineage?.events || []).find((event) => event.eventType === 'AI_INTERPRETED');
          if (hasAiEvent) {
            break;
          }
        }
      }

      await handleOpenLineage(lineageChartId);
      await loadData();
      if (finalStatus === 'succeed') {
        message.success('复跑完成，结果已更新');
      } else if (finalStatus === 'failed') {
        message.error(`复跑失败：${finalMessage || '请查看失败定位'}`);
      } else {
        message.warning('复跑仍在进行中，请稍后刷新查看最新状态');
      }
    } catch (e: any) {
      message.error(`复跑失败：${e?.message || String(e)}`);
    } finally {
      setLineageRerunLoading(false);
    }
  };

  return (
    <div className="my-chart-page">
      <div>
        <Space.Compact style={{ width: '100%' }}>
          <Search
            placeholder="请输入图表名称"
            enterButton
            allowClear
            value={searchKeyword}
            loading={loading}
            onChange={(e) => {
              const value = e.target.value;
              setSearchKeyword(value);
              if (!value.trim()) {
                setSearchParams({ ...initSearchParams });
              }
            }}
            onSearch={applySearch}
          />
          <Button
            onClick={() => {
              setSearchKeyword('');
              setSearchParams({ ...initSearchParams });
            }}
          >
            重置总览
          </Button>
        </Space.Compact>
      </div>
      <div className="margin-16" />
      <List
        grid={{
          gutter: 16,
          xs: 1,
          sm: 1,
          md: 1,
          lg: 2,
          xl: 2,
          xxl: 2,
        }}
        pagination={{
          onChange: (page, pageSize) => {
            setSearchParams({
              ...searchParams,
              current: page,
              pageSize,
            })
          },
          current: searchParams.current,
          pageSize: searchParams.pageSize,
          total: total,
        }}
        loading={loading}
        dataSource={chartList}
        renderItem={(item) => (
          <List.Item
            key={getChartIdString(item.id) || item.name}
            style={{
              opacity: removingIds.includes(getChartIdString(item.id)) ? 0 : 1,
              transform: removingIds.includes(getChartIdString(item.id)) ? 'scale(0.98)' : 'scale(1)',
              transition: 'all 0.22s ease',
            }}
          >
            <Card
              style={{ width: '100%' }}
              extra={(
                <Dropdown
                  menu={{
                    items: getCardMenuItems(getChartIdString(item.id)),
                    onClick: handleMenuClick(getChartIdString(item.id)),
                  }}
                  trigger={['click']}
                >
                  <Button
                    type="text"
                    data-chart-id={getChartIdString(item.id)}
                    loading={!!deletingIds[getChartIdString(item.id)]}
                    disabled={!!deletingIds[getChartIdString(item.id)]}
                    icon={<MoreOutlined style={{ fontSize: 18, fontWeight: 700 }} />}
                    style={{
                      border: '1px solid rgba(15, 23, 42, 0.12)',
                      boxShadow: '0 1px 2px rgba(15, 23, 42, 0.12)',
                      color: 'rgba(15, 23, 42, 0.82)',
                      background: 'rgba(15, 23, 42, 0.06)',
                      borderRadius: 10,
                      width: 34,
                      height: 30,
                      paddingInline: 0,
                    }}
                  />
                </Dropdown>
              )}
            >
              <List.Item.Meta
                avatar={<Avatar src={currentUser && currentUser.userAvatar} />}
                title={(
                  <Space size={8} wrap>
                    <span>{item.name}</span>
                  </Space>
                )}
                description={item.chartType ? '图表类型：' + item.chartType : undefined}
              />
              <>
                {
                  item.status === 'wait' && <>
                    <Result
                      status="warning"
                      title="待生成"
                      subTitle={item.execMessage ?? '当前图表生成队列繁忙，请耐心等候'}
                    />
                  </>
                }
                {
                  item.status === 'running' && <>
                    <Result
                      status="info"
                      title="图表生成中"
                      subTitle={item.execMessage}
                    />
                  </>
                }
                {
                  item.status === 'succeed' && <>
                    <div style={{ marginBottom: 16 }} />
                    <p>{'分析目标：' + item.goal}</p>
                    <div style={{ marginBottom: 16 }} />
                    {item.genChart ? (
                      <ReactECharts
                        ref={(instance) => {
                          chartRefs.current[getChartIdString(item.id)] = instance;
                        }}
                        option={parseChartOptionSafe(item.genChart)}
                      />
                    ) : (
                      <Result
                        status="warning"
                        title="图表配置不可渲染"
                        subTitle="该次 AI 返回了结论，但图表 option 缺少有效 series。建议重试生成或切换 qwen-plus / qwen-turbo。"
                      />
                    )}
                  </>
                }
                {
                  item.status === 'failed' && <>
                    <Result
                      status="error"
                      title="图表生成失败"
                      subTitle={item.execMessage}
                    />
                  </>
                }
              </>
            </Card>
          </List.Item>
        )}
      />
      <Modal
        open={lineageVisible}
        title={`血缘链路追溯（图表 ${lineageChartId || '-'}）`}
        onCancel={() => setLineageVisible(false)}
        footer={null}
        width={1100}
      >
        <Tabs
          items={[
            {
              key: 'run-trace',
              label: '运行链路',
              children: (
                <Spin spinning={lineageLoading}>
                  {!lineageData ? (
                    <Result status="info" title="暂无链路数据" subTitle="请先执行一次图表生成或 AI 分析后再查看。" />
                  ) : (
                    <>
                      {(() => {
                        const events = lineageData.events || [];
                        const failedEvents = events.filter((event) => event.status && event.status !== 'succeed');
                        const latestExplain = (lineageData.explanations || [])[0];
                        const latestAiEvent = [...events].reverse().find((event) => event.eventType === 'AI_INTERPRETED');
                        const latestPrompt = latestExplain?.promptVersion || latestAiEvent?.logicRef || '未记录';
                        const latestModel = latestExplain?.modelName || '未记录';
                        const dataTimeRange = lineageData.dataTimeRange || '暂未记录（后端接入后展示）';
                        const dataFingerprint =
                          lineageData.dataFingerprint || latestAiEvent?.inputDigest || '暂未记录（用于判断数据是否被替换）';
                        const resultFingerprint =
                          lineageData.resultFingerprint || latestAiEvent?.outputDigest || '暂未记录（用于判断结果是否一致）';
                        const dataChangedText =
                          lineageData.dataChanged === undefined || lineageData.dataChanged === null
                            ? '暂无法判断（需接入上一次快照）'
                            : lineageData.dataChanged
                            ? '已变化'
                            : '未变化';
                        const differenceReason = lineageData.differenceReason || '暂无法判断（需接入上一次快照）';
                        return (
                          <>
                            <Card size="small" style={{ marginBottom: 16 }}>
                              <Row gutter={[12, 8]} align="middle">
                                <Col xs={24} lg={18}>
                                  <Typography.Text strong>图表ID：{lineageData.chartId}</Typography.Text>
                                  <Typography.Text style={{ marginLeft: 16 }}>
                                    运行时间：{getLatestEventTime(events)}
                                  </Typography.Text>
                                  <Typography.Text style={{ marginLeft: 16 }}>
                                    traceId：{lineageData.traceId}
                                  </Typography.Text>
                                </Col>
                                <Col xs={24} lg={6} style={{ textAlign: 'right' }}>
                                  <Tag color={failedEvents.length ? 'error' : 'success'} style={{ marginRight: 8 }}>
                                    {failedEvents.length ? '状态：失败' : '状态：成功'}
                                  </Tag>
                                  <Button
                                    type="primary"
                                    size="small"
                                    loading={lineageRerunLoading}
                                    onClick={handleRerunLineage}
                                  >
                                    一键复跑
                                  </Button>
                                </Col>
                              </Row>
                            </Card>

                            <Row gutter={16}>
                              <Col xs={24} lg={14}>
                                <Card title="失败定位" size="small" style={{ marginBottom: 12 }}>
                                  {!failedEvents.length ? (
                                    <Alert type="success" showIcon message="当前未发现失败步骤" />
                                  ) : (
                                    <Descriptions size="small" column={1} bordered>
                                      <Descriptions.Item label="失败步骤">
                                        {getEventLabel(failedEvents[0].eventType)}
                                      </Descriptions.Item>
                                      <Descriptions.Item label="错误状态">
                                        {failedEvents[0].status || 'unknown'}
                                      </Descriptions.Item>
                                      <Descriptions.Item label="发生时间">
                                        {formatLineageTime(failedEvents[0].createTime)}
                                      </Descriptions.Item>
                                      <Descriptions.Item label="错误原因">
                                        {getFailureReason(failedEvents[0])}
                                      </Descriptions.Item>
                                    </Descriptions>
                                  )}
                                </Card>

                                <Card title="执行链路" size="small">
                                  <Timeline
                                    items={events.map((event) => ({
                                      color: getEventColor(event.eventType, event.status),
                                      children: (
                                        <div>
                                          <div style={{ marginBottom: 4 }}>
                                            <Tag color={getEventColor(event.eventType, event.status)}>{getEventLabel(event.eventType)}</Tag>
                                            <Tag color={getStatusTagColor(event.status)}>{event.status || 'unknown'}</Tag>
                                            <Tag>{event.ruleVersion || 'v1'}</Tag>
                                          </div>
                                          <div style={{ color: 'rgba(0,0,0,0.65)', fontSize: 13 }}>
                                            逻辑：{event.logicRef || '-'} | 时间：{formatLineageTime(event.createTime)}
                                          </div>
                                          {event.status && event.status !== 'succeed' && (
                                            <div style={{ marginTop: 6, color: '#cf1322', fontSize: 13 }}>
                                              错误定位：{getFailureReason(event)}
                                            </div>
                                          )}
                                        </div>
                                      ),
                                    }))}
                                  />
                                </Card>
                              </Col>

                              <Col xs={24} lg={10}>
                                <Card title="结果差异原因（对比上一次）" size="small" style={{ marginBottom: 12 }}>
                                  <Descriptions size="small" column={1} bordered>
                                    <Descriptions.Item label="数据是否变化">
                                      {dataChangedText}
                                    </Descriptions.Item>
                                    <Descriptions.Item label="差异说明">
                                      {differenceReason}
                                    </Descriptions.Item>
                                    <Descriptions.Item label="提示词版本">
                                      本次：{latestPrompt}
                                    </Descriptions.Item>
                                    <Descriptions.Item label="模型版本">
                                      本次：{latestModel}
                                    </Descriptions.Item>
                                  </Descriptions>
                                </Card>

                                <Card title="本次分析依据" size="small">
                                  <Descriptions size="small" column={1} bordered>
                                    <Descriptions.Item label="数据时间范围">
                                      {dataTimeRange}
                                    </Descriptions.Item>
                                    <Descriptions.Item label="数据指纹">
                                      {dataFingerprint}
                                    </Descriptions.Item>
                                    <Descriptions.Item label="提示词版本">
                                      {latestPrompt}
                                    </Descriptions.Item>
                                    <Descriptions.Item label="模型版本">
                                      {latestModel}
                                    </Descriptions.Item>
                                    <Descriptions.Item label="结果指纹">
                                      {resultFingerprint}
                                    </Descriptions.Item>
                                  </Descriptions>
                                </Card>
                              </Col>
                            </Row>
                          </>
                        );
                      })()}
                    </>
                  )}
                </Spin>
              ),
            },
            {
              key: 'asset-graph',
              label: '资产血缘图',
              children: (
                <Spin spinning={lineageGraphLoading}>
                  {!lineageGraphData ? (
                    <Result
                      status="info"
                      title="暂无资产图谱数据"
                      subTitle="当前仅展示基础链路，请先完成一次分析后重试。"
                    />
                  ) : (
                    <Row gutter={16}>
                      {(() => {
                        const allNodes = lineageGraphData.nodes || [];
                        const allEdges = lineageGraphData.edges || [];
                        const directionFilteredEdges = allEdges.filter((edge) => {
                          if (graphDirection === 'all') return true;
                          if (graphDirection === 'upstream') return edge.edgeType === 'read' || edge.edgeType === 'derive';
                          return edge.edgeType === 'write' || edge.edgeType === 'use';
                        });
                        const filteredEdges = directionFilteredEdges.filter((edge) => {
                          if (graphConfidenceFilter === 'all') return true;
                          return edge.confidence !== 'high';
                        });
                        const visibleNodeKeySet = new Set<string>();
                        filteredEdges.forEach((edge) => {
                          visibleNodeKeySet.add(edge.fromNodeKey);
                          visibleNodeKeySet.add(edge.toNodeKey);
                        });
                        const filteredNodes =
                          graphDirection === 'all'
                            ? allNodes
                            : allNodes.filter((node) => visibleNodeKeySet.has(node.nodeKey));
                        const pendingCount = lineageFixTasks.filter((task) => task.status !== 'resolved').length;
                        const resolvedCount = lineageFixTasks.filter((task) => task.status === 'resolved').length;
                        const textParseCount = lineageFixTasks.filter((task) => task.evidenceSource === 'text_parse').length;
                        const eventMetaCount = lineageFixTasks.filter((task) => task.evidenceSource === 'event_meta').length;
                        const filteredFixTasks = lineageFixTasks.filter((task) => {
                          const statusMatch = fixTaskStatusFilter === 'all' || task.status === fixTaskStatusFilter;
                          const sourceMatch = fixTaskSourceFilter === 'all' || task.evidenceSource === fixTaskSourceFilter;
                          return statusMatch && sourceMatch;
                        });
                        return (
                          <>
                            <Col span={24} style={{ marginBottom: 12 }}>
                              <Card size="small">
                                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                                  <Typography.Text strong>图谱筛选</Typography.Text>
                                  <div style={{ display: 'flex', gap: 8 }}>
                                    <Segmented
                                      value={graphDirection}
                                      onChange={(value) => setGraphDirection(value as 'all' | 'upstream' | 'downstream')}
                                      options={[
                                        { label: '全部', value: 'all' },
                                        { label: '仅上游', value: 'upstream' },
                                        { label: '仅下游', value: 'downstream' },
                                      ]}
                                    />
                                    <Segmented
                                      value={graphConfidenceFilter}
                                      onChange={(value) => setGraphConfidenceFilter(value as 'all' | 'low')}
                                      options={[
                                        { label: '全部可信度', value: 'all' },
                                        { label: '仅低可信度', value: 'low' },
                                      ]}
                                    />
                                  </div>
                                </div>
                              </Card>
                            </Col>
                            <Col span={24} style={{ marginBottom: 12 }}>
                              <Card size="small" title="治理统计看板">
                                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', gap: 10 }}>
                                  <Space size={10} wrap>
                                    <Typography.Text type="secondary">总任务</Typography.Text>
                                    <Typography.Text strong>{lineageFixTasks.length}</Typography.Text>
                                    <Divider type="vertical" />
                                    <Typography.Text type="secondary">待处理</Typography.Text>
                                    <Typography.Text strong style={{ color: '#fa8c16' }}>{pendingCount}</Typography.Text>
                                    <Divider type="vertical" />
                                    <Typography.Text type="secondary">已修复</Typography.Text>
                                    <Typography.Text strong style={{ color: '#52c41a' }}>{resolvedCount}</Typography.Text>
                                    <Divider type="vertical" />
                                    <Typography.Text type="secondary">文本推断</Typography.Text>
                                    <Typography.Text strong style={{ color: '#cf1322' }}>{textParseCount}</Typography.Text>
                                    <Divider type="vertical" />
                                    <Typography.Text type="secondary">结构化</Typography.Text>
                                    <Typography.Text strong>{eventMetaCount}</Typography.Text>
                                  </Space>
                                  <Space size={8} wrap>
                                    <Tag color="success">可信度优先</Tag>
                                    <Typography.Text type="secondary">低可信会进入待修复清单</Typography.Text>
                                  </Space>
                                </div>
                                {!lineageFixTasks.length && (
                                  <>
                                    <Divider style={{ margin: '12px 0' }} />
                                    <Alert
                                      type="success"
                                      showIcon
                                      message="当前链路血缘可信度较高"
                                      description="暂无待治理/修复任务；如果后续出现“文本推断边/低可信度边”，这里会生成待处理清单供你跟踪。"
                                    />
                                  </>
                                )}
                              </Card>
                            </Col>
                            <Col xs={24} lg={11}>
                              <Card title="资产节点" size="small" bodyStyle={{ maxHeight: 520, overflow: 'auto' }}>
                                <List
                                  size="small"
                                  dataSource={filteredNodes}
                                  renderItem={(node) => (
                                    <List.Item>
                                      <div style={{ width: '100%' }}>
                                        <Typography.Text strong>{node.name}</Typography.Text>
                                        <div style={{ marginTop: 4 }}>
                                          <Tag>{node.nodeType}</Tag>
                                          <Tag color={node.status === 'succeed' ? 'success' : node.status === 'failed' ? 'error' : 'default'}>
                                            {node.status || 'unknown'}
                                          </Tag>
                                          <Typography.Text type="secondary" style={{ marginLeft: 8 }}>
                                            {node.nodeKey}
                                          </Typography.Text>
                                        </div>
                                      </div>
                                    </List.Item>
                                  )}
                                />
                              </Card>
                            </Col>
                            <Col xs={24} lg={13}>
                              <Card title="关系链路" size="small" style={{ marginBottom: 12 }} bodyStyle={{ maxHeight: 520, overflow: 'auto' }}>
                                <List
                                  size="small"
                                  dataSource={filteredEdges}
                                  renderItem={(edge) => (
                                    <List.Item style={{ paddingBlock: 10 }}>
                                      <div style={{ width: '100%' }}>
                                        <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
                                          <Tooltip title={edge.fromNodeKey}>
                                            <Typography.Text strong ellipsis style={{ maxWidth: 520 }}>
                                              {edge.fromNodeKey}
                                            </Typography.Text>
                                          </Tooltip>
                                          <Tag color="processing">{edge.edgeType}</Tag>
                                          <Tooltip title={edge.toNodeKey}>
                                            <Typography.Text strong ellipsis style={{ maxWidth: 520 }}>
                                              {edge.toNodeKey}
                                            </Typography.Text>
                                          </Tooltip>
                                        </div>
                                        <div style={{ marginTop: 6, display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
                                          <Tag color={getEvidenceSourceTagColor(edge.evidenceSource)}>
                                            来源：{getEvidenceSourceLabel(edge.evidenceSource)}
                                          </Tag>
                                          <Tag color={getConfidenceTagColor(edge.confidence)}>
                                            可信度：{getConfidenceLabel(edge.confidence)}
                                          </Tag>
                                          <Button
                                            type="link"
                                            size="small"
                                            style={{ paddingInline: 0, marginLeft: 2 }}
                                            onClick={() => setSelectedEdge(edge)}
                                          >
                                            证据
                                          </Button>
                                        </div>
                                      </div>
                                    </List.Item>
                                  )}
                                />
                              </Card>
                              <Card size="small" title="信息面板（摘要 / 影响 / 治理）">
                                <Tabs
                                  size="small"
                                  items={[
                                    {
                                      key: 'summary',
                                      label: '图谱摘要',
                                      children: (
                                        <Descriptions size="small" column={1} bordered>
                                          <Descriptions.Item label="traceId">{lineageGraphData.traceId || '-'}</Descriptions.Item>
                                          <Descriptions.Item label="节点数">{filteredNodes.length}</Descriptions.Item>
                                          <Descriptions.Item label="关系数">{filteredEdges.length}</Descriptions.Item>
                                        </Descriptions>
                                      ),
                                    },
                                    {
                                      key: 'impact',
                                      label: '影响分析',
                                      children: !lineageImpactData ? (
                                        <Typography.Text type="secondary">暂无影响分析数据</Typography.Text>
                                      ) : !lineageImpactData.impactedCount ? (
                                        <Alert type="success" showIcon message="当前未发现受影响图表" />
                                      ) : (
                                        <List
                                          size="small"
                                          dataSource={lineageImpactData.impactedCharts || []}
                                          renderItem={(item) => (
                                            <List.Item>
                                              <div>
                                                <Typography.Text strong>{item.chartName}</Typography.Text>
                                                <Typography.Text type="secondary" style={{ marginLeft: 8 }}>
                                                  #{item.chartId}
                                                </Typography.Text>
                                                <div style={{ marginTop: 4 }}>
                                                  <Typography.Text>{item.reason}</Typography.Text>
                                                </div>
                                              </div>
                                            </List.Item>
                                          )}
                                        />
                                      ),
                                    },
                                    {
                                      key: 'fix',
                                      label: '待修复边',
                                      children: (
                                        <>
                                          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12, gap: 8, flexWrap: 'wrap' }}>
                                            <Segmented
                                              value={fixTaskStatusFilter}
                                              onChange={(value) => setFixTaskStatusFilter(value as 'all' | 'pending' | 'resolved')}
                                              options={[
                                                { label: '全部状态', value: 'all' },
                                                { label: '待处理', value: 'pending' },
                                                { label: '已修复', value: 'resolved' },
                                              ]}
                                            />
                                            <Segmented
                                              value={fixTaskSourceFilter}
                                              onChange={(value) => setFixTaskSourceFilter(value as 'all' | 'event_meta' | 'text_parse' | 'system')}
                                              options={[
                                                { label: '全部来源', value: 'all' },
                                                { label: '结构化', value: 'event_meta' },
                                                { label: '文本推断', value: 'text_parse' },
                                                { label: '系统关系', value: 'system' },
                                              ]}
                                            />
                                          </div>
                                          {!lineageFixTasks.length ? (
                                            <Typography.Text type="secondary">暂无待跟踪修复任务</Typography.Text>
                                          ) : !filteredFixTasks.length ? (
                                            <Typography.Text type="secondary">当前筛选条件下暂无任务</Typography.Text>
                                          ) : (
                                            <List
                                              size="small"
                                              dataSource={filteredFixTasks}
                                              renderItem={(task) => (
                                                <List.Item
                                                  actions={[
                                                    task.status === 'resolved' ? (
                                                      <Button
                                                        key="rollback"
                                                        size="small"
                                                        loading={fixTaskUpdatingId === task.id}
                                                        onClick={() => handleFixTaskStatus(task.id, 'pending')}
                                                      >
                                                        设为待处理
                                                      </Button>
                                                    ) : (
                                                      <Button
                                                        key="resolve"
                                                        type="primary"
                                                        size="small"
                                                        loading={fixTaskUpdatingId === task.id}
                                                        onClick={() => handleFixTaskStatus(task.id, 'resolved')}
                                                      >
                                                        标记已修复
                                                      </Button>
                                                    ),
                                                  ]}
                                                >
                                                  <div style={{ width: '100%' }}>
                                                    <Typography.Text strong>
                                                      {task.fromNodeKey}
                                                      {' -> '}
                                                      {task.toNodeKey}
                                                    </Typography.Text>
                                                    <div style={{ marginTop: 4 }}>
                                                      <Tag color={task.status === 'resolved' ? 'success' : 'warning'}>
                                                        {task.status === 'resolved' ? '已修复' : '待处理'}
                                                      </Tag>
                                                      <Tag>{task.edgeType}</Tag>
                                                      <Tag>{task.evidenceSource || 'unknown'}</Tag>
                                                      <Tag>{task.confidence || 'medium'}</Tag>
                                                    </div>
                                                    <Typography.Paragraph style={{ marginTop: 6, marginBottom: 0 }}>
                                                      {task.fixSuggestion || '暂无建议'}
                                                    </Typography.Paragraph>
                                                  </div>
                                                </List.Item>
                                              )}
                                            />
                                          )}
                                        </>
                                      ),
                                    },
                                  ]}
                                />
                              </Card>
                            </Col>
                          </>
                        );
                      })()}
                    </Row>
                  )}
                </Spin>
              ),
            },
          ]}
        />
      </Modal>
      <Modal
        open={!!selectedEdge}
        title="边证据详情"
        onCancel={() => setSelectedEdge(undefined)}
        footer={null}
        width={720}
      >
        {!selectedEdge ? null : (() => {
          const from = selectedEdge.fromNodeKey || '-';
          const to = selectedEdge.toNodeKey || '-';
          const edgeType = selectedEdge.edgeType || 'unknown';
          const evidenceSource = selectedEdge.evidenceSource || 'unknown';
          const confidence = getConfidenceLabel(selectedEdge.confidence);
          const confidenceColor = getConfidenceTagColor(selectedEdge.confidence);
          const sourceColor = getEvidenceSourceTagColor(selectedEdge.evidenceSource);
          const edgeTypeText = getEdgeTypeLabel(edgeType);

          return (
            <Space direction="vertical" size={12} style={{ width: '100%' }}>
              <Card size="small" style={{ background: 'rgba(15, 23, 42, 0.03)' }}>
                <div style={{ display: 'flex', justifyContent: 'space-between', gap: 12, flexWrap: 'wrap' }}>
                  <div style={{ minWidth: 360, flex: 1 }}>
                    <Typography.Text type="secondary">关系摘要</Typography.Text>
                    <div style={{ marginTop: 6, display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
                      <Tooltip title={from}>
                        <Typography.Text strong ellipsis style={{ maxWidth: 280 }}>
                          {from}
                        </Typography.Text>
                      </Tooltip>
                      <Tag color="processing">{edgeTypeText}</Tag>
                      <Tooltip title={to}>
                        <Typography.Text strong ellipsis style={{ maxWidth: 280 }}>
                          {to}
                        </Typography.Text>
                      </Tooltip>
                    </div>
                    <div style={{ marginTop: 8 }}>
                      <Tag color={sourceColor}>证据来源：{getEvidenceSourceLabel(evidenceSource)}</Tag>
                      <Tag color={confidenceColor}>可信度：{confidence}</Tag>
                    </div>
                  </div>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
                    <Button size="small" onClick={() => handleCopyText(from, '已复制起点')}>
                      复制起点
                    </Button>
                    <Button size="small" onClick={() => handleCopyText(to, '已复制终点')}>
                      复制终点
                    </Button>
                    <Button
                      size="small"
                      type="primary"
                      onClick={() =>
                        handleCopyText(
                          JSON.stringify(
                            {
                              fromNodeKey: from,
                              toNodeKey: to,
                              edgeType,
                              evidenceSource,
                              confidence,
                              evidenceDetail: selectedEdge.evidenceDetail || '',
                            },
                            null,
                            2,
                          ),
                          '已复制边信息',
                        )
                      }
                    >
                      复制整条边
                    </Button>
                  </div>
                </div>
              </Card>

              <Card size="small" title="基本信息">
                <Descriptions size="small" column={1} bordered>
                  <Descriptions.Item label="起点">{from}</Descriptions.Item>
                  <Descriptions.Item label="终点">{to}</Descriptions.Item>
                  <Descriptions.Item label="关系类型">{edgeTypeText}</Descriptions.Item>
                  <Descriptions.Item label="证据来源">{getEvidenceSourceLabel(evidenceSource)}</Descriptions.Item>
                  <Descriptions.Item label="可信度">
                    <Tag color={confidenceColor} style={{ marginRight: 0 }}>
                      {confidence}
                    </Tag>
                  </Descriptions.Item>
                </Descriptions>
              </Card>

              <Card size="small" title="解释与治理">
                <Descriptions size="small" column={1} bordered>
                  <Descriptions.Item label="证据说明">
                    {selectedEdge.evidenceDetail || '暂无证据详情'}
                  </Descriptions.Item>
                  <Descriptions.Item label="修复建议">
                    {selectedEdge.fixSuggestion || '当前边可信度较高，无需额外修复。'}
                  </Descriptions.Item>
                  <Descriptions.Item label="推荐 eventMeta 模板">
                    <div style={{ display: 'flex', justifyContent: 'space-between', gap: 8, alignItems: 'flex-start' }}>
                      <pre style={{ margin: 0, whiteSpace: 'pre-wrap', wordBreak: 'break-all', flex: 1 }}>
                        {selectedEdge.eventMetaTemplate || '（当前边可信度较高，无需模板）'}
                      </pre>
                      <Button
                        size="small"
                        onClick={() => handleCopyText(selectedEdge.eventMetaTemplate || '', '已复制模板')}
                        disabled={!selectedEdge.eventMetaTemplate}
                      >
                        复制模板
                      </Button>
                    </div>
                  </Descriptions.Item>
                </Descriptions>
              </Card>
            </Space>
          );
        })()}
      </Modal>
    </div>
  );
};
export default MyChartPage;
