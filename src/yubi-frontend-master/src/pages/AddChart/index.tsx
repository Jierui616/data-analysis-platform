import { genChartByAiUsingPOST } from '@/services/yubi/chartController';
import { UploadOutlined } from '@ant-design/icons';
import { Button, Card, Col, Empty, Form, Input, message, Row, Select, Space, Spin, Steps, Tag, Typography, Upload, Alert } from 'antd';
import { useForm } from 'antd/es/form/Form';
import TextArea from 'antd/es/input/TextArea';
import React, { useMemo, useRef, useState } from 'react';
import ReactECharts from 'echarts-for-react';

/** 解析模型返回的 ECharts option（去掉常见 markdown 代码块） */
function parseEchartsJson(raw: string | undefined): Record<string, unknown> {
  if (!raw?.trim()) {
    throw new Error('图表配置为空');
  }
  let s = raw.trim();
  const wrapped = s.match(/```(?:json)?\s*([\s\S]*?)```/);
  if (wrapped) {
    s = wrapped[1].trim();
  }
  try {
    const option = JSON.parse(s) as Record<string, unknown>;
    const series = (option as any)?.series;
    const hasValidSeries =
      (Array.isArray(series) && series.some((item) => item && typeof item === 'object')) ||
      (!!series && !Array.isArray(series) && typeof series === 'object');
    if (!hasValidSeries) {
      throw new Error('图表配置缺少有效 series（模型返回了 JSON，但不是可渲染的 ECharts option）');
    }
    return option;
  } catch {
    throw new Error('图表不是可渲染的 ECharts option（可能多写说明、结构不完整或缺少 series）');
  }
}

type CompactConclusion = {
  summary: string[];
  findings: string[];
  actions: string[];
};

/** 将长段分析文本提炼为前端简版结论，失败时返回空结构并回退展示原文 */
function buildCompactConclusion(raw?: string): CompactConclusion {
  const empty: CompactConclusion = { summary: [], findings: [], actions: [] };
  if (!raw?.trim()) return empty;

  const cleanSentence = (s: string) =>
    s
      .replace(/\s*[—-]{2,}.*$/g, '')
      .replace(/（[^）]{20,}）/g, '')
      .replace(/\([^)]{20,}\)/g, '')
      .replace(/\s+/g, ' ')
      .trim();

  let body = raw.replace(/\r/g, '\n');
  // 若模型按「要点/发现/建议」分段，优先按段抽取，减少重复
  const sectionPick = (label: RegExp, until: RegExp) => {
    const m = body.match(new RegExp(`${label.source}[：:\\s]*([\\s\\S]*?)(?=${until.source}|$)`, 'i'));
    return m?.[1]?.split(/[。\n；;]+/).map(cleanSentence).filter((s) => s.length >= 6) ?? [];
  };
  let sentences = body.split(/[。\n；;]+/).map(cleanSentence).filter((s) => s.length >= 8);

  const fromLabeled =
    sectionPick(/(?:结论要点|结论总览|总览|概述)/, /(?:关键发现|发现|风险|行动)/).length > 0;
  if (fromLabeled) {
    const sum = sectionPick(/(?:结论要点|结论总览|总览|概述)/, /(?:关键发现|发现)/);
    const fin = sectionPick(/(?:关键发现|发现)/, /(?:行动建议|建议|P\d)/);
    const act = sectionPick(/(?:行动建议|建议)/, /$/);
    if (sum.length || fin.length || act.length) {
      sentences = [...new Set([...sum, ...fin, ...act].filter(Boolean))];
    }
  }

  if (sentences.length === 0) return empty;

  const hasNumber = (s: string) => /(\d+(\.\d+)?)\s*(%|万|元|家|天|单|次|r\s*=)/i.test(s);
  const trendKeywords = /(整体|区间|样本|门店|增长|下降|上升|下滑|波动|环比|同比|趋势|表现)/;
  const riskKeywords = /(风险|异常|告警|流失|偏高|偏低|低效|问题|弱相关|相关)/;
  const actionKeywords = /(建议|优先|应当|可|宜|立即|优化|调整|改进|提升|收缩|聚焦|减少|加强)/;

  const toShort = (s: string, max = 48) => (s.length > max ? `${s.slice(0, max - 1)}…` : s);

  const normKey = (s: string) => s.replace(/\s+/g, '').slice(0, 36);
  const isDupOfAny = (s: string, pool: string[]) => {
    const k = normKey(s);
    return pool.some((p) => {
      if (!p) return false;
      const pk = normKey(p);
      if (k === pk) return true;
      if (k.length <= 10 || pk.length <= 10) return false;
      // 长短句包含或前缀高度重合（减轻「总览 / 发现」同义复述）
      if (p.includes(s) || s.includes(p)) return true;
      const minLen = Math.min(k.length, pk.length);
      if (minLen >= 16) {
        const prefix = 18;
        return k.slice(0, prefix) === pk.slice(0, prefix);
      }
      return false;
    });
  };

  const pickSection = (candidates: string[], limit: number, avoid: string[]) => {
    const seen = new Set<string>();
    const out: string[] = [];
    for (const s of candidates) {
      if (isDupOfAny(s, avoid) || isDupOfAny(s, out)) continue;
      const k = normKey(s);
      if (seen.has(k)) continue;
      seen.add(k);
      out.push(toShort(s));
      if (out.length >= limit) break;
    }
    return out;
  };

  // 先总览、再发现、再建议，且每一层都排除已选，减少三列同义复述
  const summaryPool = sentences.filter(
    (s) => trendKeywords.test(s) || /概况|样本|数据/.test(s),
  );
  let summary = pickSection(summaryPool, 2, []);
  if (summary.length === 0) {
    const neutral = sentences.filter((s) => !actionKeywords.test(s));
    summary = pickSection(neutral.length ? neutral : sentences, 2, []);
  }

  const actionPool = sentences.filter((s) => actionKeywords.test(s));
  const actions = pickSection(actionPool, 3, summary);

  const findingPool = sentences.filter(
    (s) =>
      (riskKeywords.test(s) || hasNumber(s)) &&
      !actionKeywords.test(s) &&
      !actionPool.includes(s),
  );
  const findings = pickSection(findingPool, 3, [...summary, ...actions]);

  return { summary, findings, actions };
}

/** 将文本中的门店编号样式化为标签，便于扫读 */
function renderWithStoreTags(text: string) {
  const parts = text.split(/(PZH-[A-Z0-9]+)/);
  return parts.map((part, i) =>
    /^PZH-[A-Z0-9]+$/.test(part) ? (
      <Tag key={i} style={{ marginInlineEnd: 4, verticalAlign: 'middle' }}>
        {part}
      </Tag>
    ) : (
      <span key={i}>{part}</span>
    ),
  );
}

/**
 * 添加图表页面
 * @constructor
 */
const AddChart: React.FC = () => {
  const [form] = useForm();
  const watchedFile = Form.useWatch('file', form);
  const [chart, setChart] = useState<API.BiResponse>();
  const [option, setOption] = useState<any>();
  const [submitting, setSubmitting] = useState<boolean>(false);
  const [showFullConclusion, setShowFullConclusion] = useState<boolean>(false);
  const randomCaseIndexRef = useRef<number>(-1);

  const randomCases = [
    {
      goal: '基于门店经营测试数据，分析各门店营收、毛利率、客单价、翻台率与取消率表现，定位低效门店与高潜力门店，并给出经营优化建议',
      name: '门店经营总览分析（测试数据）',
    },
    {
      goal: '基于门店成本测试数据，分析食材成本、人工成本、能耗、平台佣金、损耗率与库存损失率，识别成本异常门店并提出降本方案',
      name: '门店成本结构分析（测试数据）',
    },
    {
      goal: '基于门店人效测试数据，分析午晚班排班与实际到岗差异、加班时长、缺勤、备餐时长与收入关系，给出排班优化建议',
      name: '门店人效排班分析（测试数据）',
    },
  ];

  /**
   * 提交
   * @param values
   */
  const onFinish = async (values: any) => {
    // 避免重复提交
    if (submitting) {
      return;
    }
    setSubmitting(true);
    setChart(undefined);
    setOption(undefined);
    setShowFullConclusion(false);
    // 对接后端，上传数据
    const params = {
      ...values,
      file: undefined,
    };
    try {
      const res = (await genChartByAiUsingPOST(
        params,
        {},
        values.file.file.originFileObj,
      )) as API.BaseResponseBiResponse_;
      if (res.code !== 0 && res.code !== undefined) {
        message.error(res.message || '分析失败');
        return;
      }
      if (!res?.data) {
        message.error('分析失败：无返回数据');
        return;
      }
      setChart(res.data);
      try {
        const chartOption = parseEchartsJson(res.data.genChart);
        setOption(chartOption);
        message.success('分析成功');
      } catch (parseErr: any) {
        message.warning(parseErr.message || '图表 JSON 解析失败，结论已显示在右侧');
        setOption(undefined);
      }
    } catch (e: any) {
      message.error('分析失败：' + (e?.message || String(e)));
    }
    setSubmitting(false);
  };

  const handleRandomCase = () => {
    randomCaseIndexRef.current = (randomCaseIndexRef.current + 1) % randomCases.length;
    const nextCase = randomCases[randomCaseIndexRef.current];
    // 先清空再填充下一个案例，避免残留字段影响
    form.resetFields(['goal', 'name']);
    form.setFieldsValue({
      goal: nextCase.goal,
      name: nextCase.name,
    });
    setChart(undefined);
    setOption(undefined);
    setShowFullConclusion(false);
  };

  const compact = buildCompactConclusion(chart?.genResult);
  const hasGenerated = Boolean(chart?.genResult || option);
  const flowStep = useMemo(() => {
    if (submitting) return 1;
    if (hasGenerated) return 2;
    return 0;
  }, [submitting, hasGenerated]);
  const displayOption = useMemo(() => {
    if (!option) return undefined;
    const next = JSON.parse(JSON.stringify(option || {}));

    const normalizeAxis = (axis: any) => {
      if (!axis) return axis;
      if (Array.isArray(axis)) return axis.map((a) => normalizeAxis(a));
      return {
        ...axis,
        axisLabel: {
          ...(axis.axisLabel || {}),
          hideOverlap: true,
          interval: 'auto',
          overflow: 'truncate',
          width: axis?.type === 'category' ? 88 : undefined,
        },
      };
    };
    next.xAxis = normalizeAxis(next.xAxis);
    next.yAxis = normalizeAxis(next.yAxis);

    const titleBlockTop = 10;
    /** 主标题与 subtext 间距；注意必须把默认值写在 ...base 之后，否则会被模型返回覆盖导致叠字 */
    const normalizeTitleOne = (t: any) => {
      const base = t || {};
      const itemGap = Math.max(typeof base.itemGap === 'number' ? base.itemGap : 0, 18);
      return {
        ...base,
        left: base.left ?? 'center',
        top: typeof base.top === 'number' ? base.top : titleBlockTop,
        width: base.width ?? '78%',
        itemGap,
        textStyle: {
          fontSize: 15,
          lineHeight: 22,
          overflow: 'break',
          ...(base.textStyle || {}),
        },
        subtextStyle: {
          fontSize: 12,
          lineHeight: 20,
          color: '#666',
          overflow: 'break',
          ...(base.subtextStyle || {}),
        },
      };
    };

    /** 多个 title 对象常会共用同一 top 导致完全重叠，合并为一条主标题 + subtext */
    const mergeTitleArray = (arr: any[]) => {
      if (!arr?.length) return undefined;
      const first = arr[0] || {};
      const extraTexts = arr
        .slice(1)
        .map((x: any) => [x?.subtext, x?.text].filter(Boolean).join(' '))
        .filter(Boolean);
      const sub = [first.subtext, ...extraTexts].filter(Boolean).join('\n');
      return normalizeTitleOne({
        ...first,
        subtext: sub || first.subtext,
      });
    };

    if (next.title) {
      if (Array.isArray(next.title)) {
        next.title = next.title.length > 1 ? mergeTitleArray(next.title) : normalizeTitleOne(next.title[0]);
      } else {
        next.title = normalizeTitleOne(next.title);
      }
    }

    const hasTitle = Boolean(next.title);

    /**
     * 图例默认放在图表下方居中，避免与标题区、副标题、图例挤在顶部重叠。
     * 去掉模型返回的 top，否则常与标题同一行冲突。
     */
    const normalizeLegendBlock = (leg: Record<string, unknown>) => {
      const { top: _omitTop, ...restLeg } = leg as Record<string, unknown> & { top?: unknown };
      return {
        type: 'scroll',
        orient: 'horizontal',
        ...restLeg,
        left: (restLeg as { left?: unknown }).left ?? 'center',
        bottom: (restLeg as { bottom?: unknown }).bottom ?? 8,
      };
    };
    if (next.legend) {
      next.legend = Array.isArray(next.legend)
        ? next.legend.map((leg: any, i: number) =>
            normalizeLegendBlock({ ...(leg || {}), bottom: 8 + i * 28 }),
          )
        : normalizeLegendBlock(next.legend || {});
    }

    /** 标题可能多行换行，预留更高 grid.top；底部留给横向滚动图例 */
    const minGridTop = hasTitle ? 108 : 52;
    const minGridBottom = next.legend ? 56 : 40;
    const normalizeGrid = (grid: any) => {
      const g = grid || {};
      let top = g.top;
      if (top === undefined || top === null) {
        top = minGridTop;
      } else if (typeof top === 'number') {
        top = Math.max(minGridTop, top);
      }
      let bottom = g.bottom;
      if (bottom === undefined || bottom === null) {
        bottom = minGridBottom;
      } else if (typeof bottom === 'number') {
        bottom = Math.max(minGridBottom, bottom);
      }
      return {
        left: 48,
        right: 24,
        containLabel: true,
        ...g,
        top,
        bottom,
      };
    };
    next.grid = Array.isArray(next.grid) ? next.grid.map((g: any) => normalizeGrid(g)) : normalizeGrid(next.grid);

    const normalizeSeries = (series: any) => {
      if (!series) return series;
      if (Array.isArray(series)) return series.map((s) => normalizeSeries(s));
      return {
        ...series,
        label: series?.label
          ? {
              ...series.label,
              hideOverlap: true,
              overflow: 'truncate',
            }
          : series?.type === 'pie'
            ? { show: false, hideOverlap: true }
            : series?.label,
      };
    };
    next.series = normalizeSeries(next.series);
    return next;
  }, [option]);

  return (
    <div className="add-chart">
      <Steps
        size="small"
        current={flowStep}
        items={[
          { title: '准备数据与需求', description: '上传 CSV、填写分析目标' },
          { title: '提交生成', description: '模型处理约数十秒' },
          { title: '查看结果', description: '图表与结论' },
        ]}
        style={{ marginBottom: 16 }}
      />
      <Row gutter={16} align="stretch">
        <Col xs={24} xl={9} style={{ display: 'flex' }}>
          <Card title="智能分析" bodyStyle={{ padding: 18 }} style={{ width: '100%' }}>
            <div style={{ maxWidth: 640 }}>
              <Form name="addChart" layout="vertical" onFinish={onFinish} initialValues={{}} form={form}>
              <Form.Item
                name="goal"
                label="分析目标"
                rules={[{ required: true, message: '请输入分析目标' }]}
              >
                <TextArea placeholder="请输入你的分析需求，比如：分析网站用户的增长情况" />
              </Form.Item>
              <Form.Item label="数据文件" required style={{ marginBottom: 12 }}>
                <Card
                  size="small"
                  bodyStyle={{
                    padding: watchedFile?.file?.name ? 12 : '10px 12px',
                  }}
                >
                  {watchedFile?.file?.name ? (
                    <Space direction="vertical" size={8} style={{ width: '100%' }}>
                      <Form.Item name="file" noStyle>
                        <Upload name="file" maxCount={1}>
                          <Button icon={<UploadOutlined />}>重新上传文件</Button>
                        </Upload>
                      </Form.Item>
                      <Typography.Text type="secondary">
                        已上传：{watchedFile.file.name}
                        {typeof watchedFile?.file?.size === 'number'
                          ? `（${(watchedFile.file.size / 1024).toFixed(1)} KB）`
                          : ''}
                      </Typography.Text>
                    </Space>
                  ) : (
                    <Space size={10} align="center">
                      <Form.Item name="file" noStyle>
                        <Upload name="file" maxCount={1}>
                          <Button icon={<UploadOutlined />}>上传文件</Button>
                        </Upload>
                      </Form.Item>
                    </Space>
                  )}
                </Card>
              </Form.Item>

              <Row gutter={12}>
                <Col span={12}>
                  <Form.Item name="name" label="图表名称">
                    <Input placeholder="请输入图表名称" />
                  </Form.Item>
                </Col>
                <Col span={12}>
                  <Form.Item name="chartType" label="图表类型">
                    <Select
                      options={[
                        { value: '折线图', label: '折线图' },
                        { value: '柱状图', label: '柱状图' },
                        { value: '堆叠图', label: '堆叠图' },
                        { value: '饼图', label: '饼图' },
                        { value: '雷达图', label: '雷达图' },
                      ]}
                    />
                  </Form.Item>
                </Col>
              </Row>

              <Form.Item>
                <Space>
                  <Button type="primary" htmlType="submit" loading={submitting} disabled={submitting}>
                    提交
                  </Button>
                  <Button htmlType="reset">重置</Button>
                  <Button onClick={handleRandomCase} disabled={submitting}>
                    随机案例
                  </Button>
                </Space>
              </Form.Item>
              </Form>
            </div>
          </Card>
        </Col>
        <Col xs={24} xl={15} style={{ display: 'flex' }}>
          {!hasGenerated ? (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 12, width: '100%', flex: 1 }}>
              <Card
                title="可视化图表"
                style={{ flex: 1, minHeight: 200 }}
                bodyStyle={{ display: 'flex', alignItems: 'center', justifyContent: 'center', minHeight: 160 }}
              >
                <Empty
                  image={Empty.PRESENTED_IMAGE_SIMPLE}
                  description={
                    <Space direction="vertical" size={4}>
                      <Typography.Text>填写左侧表单并提交后展示图表</Typography.Text>
                      {!submitting ? (
                        <Typography.Text type="secondary">首次生成通常需 30～90 秒</Typography.Text>
                      ) : null}
                    </Space>
                  }
                />
              </Card>
              <Card
                title="分析结论"
                style={{ flex: 1, minHeight: 200 }}
                bodyStyle={{ display: 'flex', alignItems: 'center', justifyContent: 'center', minHeight: 160 }}
              >
                <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="结论将在生成完成后展示" />
              </Card>
              {submitting ? <Alert type="info" showIcon message="正在调用模型分析，请勿关闭页面" /> : null}
            </div>
          ) : (
            <Card title="可视化图表" style={{ width: '100%' }} bodyStyle={{ minHeight: 400 }}>
              {displayOption ? (
                <ReactECharts option={displayOption} style={{ height: 400 }} opts={{ renderer: 'canvas' }} />
              ) : (
                <div style={{ minHeight: 400, display: 'flex', alignItems: 'center' }}>当前暂无可渲染图表</div>
              )}
              <Spin spinning={submitting} />
            </Card>
          )}
        </Col>
      </Row>

      {hasGenerated ? (
        <Card title="分析结论" style={{ marginTop: 16 }}>
          {!chart?.genResult ? (
            <div style={{ minHeight: 160, display: 'flex', alignItems: 'center' }}>当前暂无分析结论</div>
          ) : (
            <Space direction="vertical" size={12} style={{ width: '100%' }}>
              <Row gutter={12}>
                <Col xs={24} md={8}>
                  <div style={{ height: '100%', padding: 10, border: '1px solid #f0f0f0', borderRadius: 8 }}>
                    <Typography.Title level={5} style={{ margin: 0, marginBottom: 8 }}>
                      结论总览
                    </Typography.Title>
                    {compact.summary.length > 0 ? (
                      compact.summary.map((s, i) => (
                        <Typography.Paragraph key={`summary-${i}`} style={{ marginBottom: 4 }}>
                          {i + 1}. {s}
                        </Typography.Paragraph>
                      ))
                    ) : (
                      <Typography.Text type="secondary">暂无可提炼摘要，已保留完整结论。</Typography.Text>
                    )}
                  </div>
                </Col>
                <Col xs={24} md={8}>
                  <div style={{ height: '100%', padding: 10, border: '1px solid #f0f0f0', borderRadius: 8 }}>
                    <Typography.Title level={5} style={{ margin: 0, marginBottom: 8 }}>
                      关键发现
                    </Typography.Title>
                    {compact.findings.length > 0 ? (
                      compact.findings.map((s, i) => (
                        <Typography.Paragraph key={`finding-${i}`} style={{ marginBottom: 4 }}>
                          - {s}
                        </Typography.Paragraph>
                      ))
                    ) : (
                      <Typography.Text type="secondary">暂无可提炼发现。</Typography.Text>
                    )}
                  </div>
                </Col>
                <Col xs={24} md={8}>
                  <div style={{ height: '100%', padding: 10, border: '1px solid #f0f0f0', borderRadius: 8 }}>
                    <Typography.Title level={5} style={{ margin: 0, marginBottom: 8 }}>
                      行动建议
                    </Typography.Title>
                    {compact.actions.length > 0 ? (
                      compact.actions.map((s, i) => (
                        <Typography.Paragraph key={`action-${i}`} style={{ marginBottom: 4 }}>
                          P{i + 1}: {s}
                        </Typography.Paragraph>
                      ))
                    ) : (
                      <Typography.Text type="secondary">暂无可提炼建议。</Typography.Text>
                    )}
                  </div>
                </Col>
              </Row>

              <Button type="link" size="small" style={{ paddingLeft: 0, height: 24 }} onClick={() => setShowFullConclusion((v) => !v)}>
                {showFullConclusion ? '收起完整分析' : '展开完整分析'}
              </Button>
              {showFullConclusion ? (
                <Card size="small" title="完整分析原文" bodyStyle={{ maxHeight: 280, overflowY: 'auto' }}>
                  <Typography.Paragraph style={{ whiteSpace: 'pre-wrap', marginBottom: 0 }}>
                    {chart.genResult}
                  </Typography.Paragraph>
                </Card>
              ) : null}
            </Space>
          )}
          <Spin spinning={submitting} />
        </Card>
      ) : null}
    </div>
  );
};
export default AddChart;
