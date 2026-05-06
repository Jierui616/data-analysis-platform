import { getChartByIdUsingGET } from '@/services/yubi/chartController';
import { useNavigate, useParams } from '@umijs/max';
import {
  Button,
  Card,
  Col,
  Result,
  Row,
  Space,
  Spin,
  Tag,
  Typography,
} from 'antd';
import React, { useEffect, useMemo, useState } from 'react';

type ResultMeta = {
  summary?: {
    headline?: string;
    riskLevel?: 'low' | 'medium' | 'high' | string;
    keyActions?: string[];
  };
  kpis?: Array<{
    name?: string;
    value?: string | number;
    unit?: string;
    trend?: 'up' | 'down' | 'flat' | string;
    delta?: string | number;
  }>;
  issues?: Array<{
    title?: string;
    priority?: 'high' | 'medium' | 'low' | string;
    impact?: string;
    ownerSuggestion?: string;
    action?: string;
  }>;
  insights?: Array<{
    chartTitle?: string;
    points?: string[];
  }>;
};

type KpiTrend = 'up' | 'down' | 'flat';

const extractNumberAfterKeyword = (text: string, keywords: string[]): number | undefined => {
  if (!text.trim()) return undefined;
  const escaped = keywords.map((k) => k.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'));
  const reg = new RegExp(`(?:${escaped.join('|')})[^\\d-]{0,20}(-?\\d{1,3}(?:,\\d{3})*(?:\\.\\d+)?|-?\\d+(?:\\.\\d+)?)`, 'i');
  const match = text.match(reg);
  if (!match) return undefined;
  const value = Number(match[1].replace(/,/g, ''));
  return Number.isFinite(value) ? value : undefined;
};

const inferTrendFromRaw = (text: string, keywords: string[]): KpiTrend => {
  const escaped = keywords.map((k) => k.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'));
  const reg = new RegExp(`(?:${escaped.join('|')}).{0,12}(上升|增长|提升|下降|下滑|减少)`, 'i');
  const match = text.match(reg);
  const token = match?.[1] || '';
  if (/上升|增长|提升/.test(token)) return 'up';
  if (/下降|下滑|减少/.test(token)) return 'down';
  return 'flat';
};

const sanitizeChineseText = (text?: string): string => {
  if (!text) return '-';
  return text
    .replace(/[（(]\s*[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)*\s*[）)]/g, '')
    .replace(/\b(?:avg|min|max|mean|rate|kpi|trend|delta|impact|owner|action)\b/gi, '')
    .replace(/\b[A-Z]{2,}\d+\b/g, '')
    .replace(/\s{2,}/g, ' ')
    .trim();
};

const toProblemSentence = (text?: string): string => {
  const normalized = sanitizeChineseText(text)
    .replace(/[A-Za-z]/g, '')
    .replace(/[0-9０-９]+(?:[.,，]\d+)?/g, '')
    .replace(/[()（）]/g, '')
    .replace(/\s{2,}/g, ' ')
    .trim();
  if (!normalized || normalized === '-') return '门店执行链路存在波动，需优先聚焦关键环节。';
  const shortText = normalized.split(/[。；;]/)[0].trim();
  if (shortText.includes('排队') || shortText.includes('收银') || shortText.includes('候餐')) {
    return '晚高峰收银与排队压力偏高，影响顾客到店体验。';
  }
  if (shortText.includes('断货') || shortText.includes('缺货') || shortText.includes('补货')) {
    return '爆款补货与前场陈列衔接不足，存在有客无货风险。';
  }
  if (shortText.includes('陈列') || shortText.includes('动线') || shortText.includes('连带')) {
    return '门店陈列与动线引导不足，连带购买机会未充分释放。';
  }
  if (shortText.includes('客诉') || shortText.includes('服务') || shortText.includes('找货')) {
    return '高峰服务协同不足，顾客咨询与找货响应不够及时。';
  }
  if (shortText.includes('临期') || shortText.includes('损耗') || shortText.includes('破损') || shortText.includes('交接')) {
    return '店务基础管理存在短板，影响门店稳定执行。';
  }
  return '门店关键经营环节波动，建议先稳定高峰时段执行。';
};

const formatMetric = (value: number | undefined, unit: string): string => {
  if (value === undefined || Number.isNaN(value)) return `--${unit}`;
  return `${value}${unit}`;
};

const buildImpactTemplate = (raw: string): string => {
  const revenue = extractNumberAfterKeyword(raw, ['营收', '销售额', '营业额']);
  const grossMargin = extractNumberAfterKeyword(raw, ['毛利率', '毛利']);
  const turnover = extractNumberAfterKeyword(raw, ['翻台率', '翻台']);
  return `预计影响营收约${formatMetric(revenue, '元/日')}，毛利率约${formatMetric(grossMargin, '%')}，翻台率约${formatMetric(turnover, '次/日')}。`;
};

const pickByText = (text: string, candidates: string[]): string => {
  if (!candidates.length) return '';
  let seed = 0;
  for (let i = 0; i < text.length; i += 1) {
    seed = (seed * 31 + text.charCodeAt(i)) >>> 0;
  }
  return candidates[seed % candidates.length];
};

const detectIssueCategory = (text: string):
  | 'checkout_queue'
  | 'replenishment_stockout'
  | 'display_bundle'
  | 'service_guidance'
  | 'loss_and_ops'
  | 'generic' => {
  if (/排队|收银|结账|候餐|拥堵/.test(text)) return 'checkout_queue';
  if (/断货|缺货|补货|爆款无货|货架空/.test(text)) return 'replenishment_stockout';
  if (/陈列|动线|连带|搭配|堆头|前区/.test(text)) return 'display_bundle';
  if (/服务|导购|客诉|咨询|找货|响应/.test(text)) return 'service_guidance';
  if (/临期|损耗|破损|后仓|交接|盘点/.test(text)) return 'loss_and_ops';
  return 'generic';
};

const normalizeRiskLevel = (value?: string): 'low' | 'medium' | 'high' | undefined => {
  if (value === 'high' || value === 'medium' || value === 'low') return value;
  return undefined;
};

const inferRiskLevelFromContent = (rawText: string, issues?: ResultMeta['issues']): 'low' | 'medium' | 'high' => {
  const issueList = issues || [];
  if (issueList.some((item) => item?.priority === 'high')) return 'high';
  if (issueList.some((item) => item?.priority === 'medium')) return 'medium';
  const text = sanitizeChineseText(rawText);
  const highRiskPatterns = [
    /失败|中断|停摆|严重异常|食品安全|客诉集中|断货|明显流失|大幅下滑/,
    /连续.*异常|持续.*恶化/,
  ];
  if (highRiskPatterns.some((reg) => reg.test(text))) return 'high';
  const mediumRiskPatterns = [/波动|关注|优化|偏低|不足|待改进|下滑|风险/];
  if (mediumRiskPatterns.some((reg) => reg.test(text))) return 'medium';
  return 'low';
};

const buildManagerSummary = (
  rawText: string,
  riskLevel: 'low' | 'medium' | 'high' | string,
): { headline: string; keyActions: string[] } => {
  const cleaned = sanitizeChineseText(rawText)
    .replace(/[A-Za-z]/g, '')
    .replace(/\b[A-Z]{2,}\d+\b/g, '')
    .replace(/\s{2,}/g, ' ')
    .trim();
  const category = detectIssueCategory(cleaned);

  const headlineMap: Record<string, string> = {
    checkout_queue:
      riskLevel === 'high'
        ? '门店高峰收银与排队压力较大，建议立即调整高峰岗位。'
        : '门店高峰收银效率有优化空间，建议先稳住高峰时段秩序。',
    replenishment_stockout:
      riskLevel === 'high'
        ? '门店补货与前场陈列衔接不足，需优先处理断货风险。'
        : '门店补货节奏存在波动，建议强化关键时段补货点检。',
    display_bundle: '门店陈列与动线仍有优化空间，连带购买机会可进一步释放。',
    service_guidance:
      riskLevel === 'high'
        ? '门店服务响应存在短板，建议优先保障高峰顾客体验。'
        : '门店服务协同有提升空间，建议强化导购与补货联动。',
    loss_and_ops: '门店店务基础管理需持续优化，建议加强巡检与交接闭环。',
    generic:
      riskLevel === 'high'
        ? '门店关键经营环节出现明显波动，建议先处理高影响问题。'
        : '门店经营总体可控，建议聚焦重点环节持续优化执行。',
  };

  const keyActionMap: Record<string, string[]> = {
    checkout_queue: ['高峰前完成岗位重排与开台准备', '收银前区设置导流与快结通道', '班后复盘排队异常并次日纠偏'],
    replenishment_stockout: ['执行三次补货点检并留痕', '爆款缺口由当班负责人即时闭环', '交接班重点跟进连续缺货商品'],
    display_bundle: ['主通道统一主推陈列主题', '前区轮换高动销小件强化连带', '按时段调整陈列策略匹配客群'],
    service_guidance: ['高峰执行导购与补货双岗联动', '当班前统一主推与应答话术', '客诉当班闭环并完成回访记录'],
    loss_and_ops: ['闭店前完成临期与破损巡检', '后仓货位标准化并清理补货通道', '交接班逐项确认重点任务'],
    generic: ['先处理高优先级问题再扩展优化', '明确责任人与执行窗口', '班后复盘并跟踪次日改进动作'],
  };

  const fallbackHeadline =
    cleaned && cleaned !== '-' ? toProblemSentence(cleaned) : riskLevel === 'high' ? '当前存在高风险经营问题，建议优先处置。' : '当前经营总体稳定，建议聚焦关键环节优化。';

  return {
    headline: headlineMap[category] || fallbackHeadline,
    keyActions: keyActionMap[category] || keyActionMap.generic,
  };
};

const buildStructuredAction = (issue: ResultMeta['issues'][number], rawText: string) => {
  const titleText = sanitizeChineseText(issue?.title);
  const actionText = sanitizeChineseText(issue?.action);
  const impactText = sanitizeChineseText(issue?.impact);
  const matchText = `${titleText} ${actionText} ${impactText}`.trim();
  const category = detectIssueCategory(matchText);
  const ownerFromIssue = sanitizeChineseText(issue?.ownerSuggestion);
  const isHighPriority = issue?.priority === 'high';
  const executeWindow = isHighPriority ? '今日晚高峰（如已过高峰则明日开店前）' : '本周排班周期内';
  const roleMap: Record<string, { primary: string; support: string }> = {
    checkout_queue: { primary: '收银班长', support: '店长' },
    replenishment_stockout: { primary: '理货补货负责人', support: '店长' },
    display_bundle: { primary: '陈列负责人', support: '导购组长' },
    service_guidance: { primary: '导购组长', support: '店长' },
    loss_and_ops: { primary: '店务负责人', support: '店长' },
    generic: { primary: '店长', support: '当班负责人' },
  };
  const role = roleMap[category] || roleMap.generic;
  const owner = ownerFromIssue !== '-' ? ownerFromIssue : `${role.primary}（协同：${role.support}）`;

  const actionMap: Record<string, string[]> = {
    checkout_queue: [
      '收银班长在高峰前开启临时收银位，并安排专人进行排队导流。',
      '收银班长在前区设置等候引导线，优先处理小篮快结顾客。',
      '收银班长组织高峰班次固定1人收银+1人装袋补位，减少结账堆单。',
      '收银班长在晚高峰前完成设备巡检，避免高峰时段卡顿中断。',
      '收银班长安排前区购物篮归位与导流提醒，降低通道拥堵。',
      '店长协调将高频询价商品前移陈列，减少收银台二次离队查询。',
      '店长在高峰时段前场值守，现场处理排队异常情况。',
      '收银班长执行分段开台策略，按客流动态调整收银台开放数量。',
    ],
    replenishment_stockout: [
      '理货补货负责人执行三次补货点检：开店后、午后、晚高峰前。',
      '理货补货负责人设立爆款补货责任位，前场缺口当班闭环。',
      '理货补货负责人建立高频询问商品清单，优先补齐前10项。',
      '理货补货负责人将爆款安全库存前移至前场，缺口出现后即补即陈列。',
      '理货补货负责人在高峰前30分钟完成爆款货架复检，确保主销位不断档。',
      '理货补货负责人按“主通道先补、次通道后补”顺序提升响应效率。',
      '店长对连续缺货商品设置补货预警，交接班时重点跟进。',
      '理货补货负责人与前场双人联动补货，缩短找货与上架时间。',
    ],
    display_bundle: [
      '陈列负责人在主通道设置本周主推堆头，并完成邻位搭配陈列。',
      '陈列负责人轮换收银前区高动销小件，强化临门一脚推荐。',
      '陈列负责人按主品+搭配品重排货架，提升一站式选购效率。',
      '陈列负责人统一端架陈列主题，集中展示当周主推组合商品。',
      '导购组长协同将高频连带品放在主推品邻位，缩短顾客决策路径。',
      '陈列负责人在收银前区设置小规格尝鲜组合，提升临门连带机会。',
      '店长协调优化门店动线指引标识，减少顾客来回找货路径。',
      '导购组长按时段切换前区主推商品，匹配不同客群购买场景。',
    ],
    service_guidance: [
      '导购组长在高峰时段执行导购与补货双岗联动，减少顾客等待。',
      '导购组长在当班前开展3分钟主推话术晨会，统一推荐口径。',
      '店长设置客诉第一响应人，确保当班内完成处理与回访。',
      '导购组长安排导购驻守主通道，主动协助找货与推荐。',
      '导购组长建立当班服务清单，按时段巡查咨询响应与补货协同。',
      '导购组长统一高频咨询话术，减少重复解释造成等待。',
      '店长安排新员工跟岗演练，优先提升高峰服务协同能力。',
      '店长对当日客诉执行闭环复盘，次日开班前同步改进要点。',
    ],
    loss_and_ops: [
      '店务负责人在闭店前完成临期与破损巡检，并执行交接清单确认。',
      '店务负责人按品类重排后仓货位并上标识，缩短补货路径。',
      '店长组织统一交接班口头复盘流程，避免执行断层。',
      '店务负责人每日闭店前执行临期分层管理，优先处理临近到期商品。',
      '店务负责人建立破损商品即时下架与登记机制，避免前场重复暴露。',
      '店务负责人每班次完成一次后仓通道清理，保障补货路线顺畅。',
      '店长在交接班前核对重点任务清单，确保当班问题不遗留。',
      '店长每周组织一次店务抽检，提前纠偏陈列与损耗问题。',
    ],
    generic: [
      '店长先处理当班高优先级问题，并在班后完成复盘闭环。',
      '店长围绕顾客等待、补货、陈列三项先做现场排查并立即纠偏。',
      '店长组织当班15分钟站会，明确责任人与执行顺序。',
      '店长先稳住晚高峰现场秩序，再逐项清理门店执行短板。',
      '店长在当班完成问题清单分派，确保每项问题有人负责。',
      '店长按“先体验后效率”顺序推进整改，避免动作分散。',
      '店长组织班后复盘并形成次日执行重点，持续跟踪改善。',
      '店长聚焦最影响顾客感知的问题，优先完成现场纠偏。',
    ],
  };
  const doneMap: Record<string, string> = {
    checkout_queue: '临时收银位已开启；岗位分工已上墙；高峰执行打卡2次。',
    replenishment_stockout: '三次点检记录完整；缺口商品已补齐；店长已确认闭环。',
    display_bundle: '主推堆头已落地；标准照已上传；督导抽检通过。',
    service_guidance: '双岗在岗记录齐全；话术抽查已完成；客诉处理有回访。',
    loss_and_ops: '巡检打卡完成；异常已登记处理；交接双方已签字。',
    generic: '现场动作照片已上传；交接班记录已签字；督导抽查通过。',
  };

  return {
    problem: toProblemSentence(titleText || actionText || rawText),
    action: pickByText(matchText || rawText, actionMap[category]),
    owner,
    executeWindow,
    done: doneMap[category],
  };
};

const parseResultMeta = (rawMeta?: string, chart?: API.Chart): ResultMeta => {
  const raw = (chart?.genResult || '').trim();
  const fallbackKpis = () => {
    const revenue = extractNumberAfterKeyword(raw, ['营收', '销售额', '营业额']);
    const grossMargin = extractNumberAfterKeyword(raw, ['毛利率', '毛利']);
    const unitPrice = extractNumberAfterKeyword(raw, ['客单价', '客单']);
    const turnover = extractNumberAfterKeyword(raw, ['翻台率', '翻台']);
    return [
      {
        name: '营收',
        value: revenue ?? '--',
        unit: '元',
        trend: inferTrendFromRaw(raw, ['营收', '销售额', '营业额']),
        delta: '--',
      },
      {
        name: '毛利率',
        value: grossMargin ?? '--',
        unit: '%',
        trend: inferTrendFromRaw(raw, ['毛利率', '毛利']),
        delta: '--',
      },
      {
        name: '客单价',
        value: unitPrice ?? '--',
        unit: '元',
        trend: inferTrendFromRaw(raw, ['客单价', '客单']),
        delta: '--',
      },
      {
        name: '翻台率',
        value: turnover ?? '--',
        unit: '次/日',
        trend: inferTrendFromRaw(raw, ['翻台率', '翻台']),
        delta: '--',
      },
    ] as ResultMeta['kpis'];
  };

  if (rawMeta?.trim()) {
    try {
      const parsed = JSON.parse(rawMeta) as ResultMeta;
      const riskLevelFromParsed =
        normalizeRiskLevel(parsed?.summary?.riskLevel) || inferRiskLevelFromContent(raw, parsed?.issues);
      const managerSummary = buildManagerSummary(raw, riskLevelFromParsed);
      parsed.summary = {
        ...(parsed.summary || {}),
        riskLevel: riskLevelFromParsed,
        headline: managerSummary.headline,
        keyActions: managerSummary.keyActions,
      };
      if (!parsed.kpis?.length || parsed.kpis.every((kpi) => kpi?.value === '--' || kpi?.value === undefined || kpi?.value === null)) {
        parsed.kpis = fallbackKpis();
      }
      return parsed;
    } catch {
      // ignore parse errors
    }
  }
  const firstSentence = raw.split(/[。\n]/).map((v) => v.trim()).find(Boolean) || '本次分析已完成，请关注关键经营指标。';
  const actions = raw
    .split(/[\n；;。]/)
    .map((v) => v.replace(/^\d+[\.、]\s*/, '').trim())
    .filter((v) => v.length >= 8 && (v.includes('建议') || v.includes('优化') || v.includes('提升') || v.includes('控制')))
    .slice(0, 3);
  const riskLevel = inferRiskLevelFromContent(raw);
  const managerSummary = buildManagerSummary(raw, riskLevel);
  return {
    summary: {
      headline: managerSummary.headline,
      riskLevel,
      keyActions: managerSummary.keyActions,
    },
    kpis: fallbackKpis(),
    issues: [
      {
        title: firstSentence,
        priority: riskLevel === 'high' ? 'high' : 'medium',
        impact: '建议结合门店运营数据确认影响范围',
        ownerSuggestion: '门店运营负责人',
        action: actions[0] || '先处理高风险指标项',
      },
    ],
    insights: [
      {
        chartTitle: chart?.name || '当前图表',
        points: [firstSentence, chart?.goal ? `分析目标：${chart.goal}` : '建议结合业务场景执行动作'],
      },
    ],
  };
};

const ChartResultPage: React.FC = () => {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const [loading, setLoading] = useState(true);
  const [chart, setChart] = useState<API.Chart>();

  const loadDetail = async () => {
    if (!id) return;
    // chartId 可能是雪花 long，不能转 Number，否则会丢精度导致查错记录
    const res = await getChartByIdUsingGET({ id: id as any });
    setChart(res?.data);
  };

  useEffect(() => {
    let timer: number | undefined;
    const run = async () => {
      setLoading(true);
      try {
        await loadDetail();
      } finally {
        setLoading(false);
      }
    };
    run();
    timer = window.setInterval(async () => {
      if (chart?.status === 'wait' || chart?.status === 'running') {
        await loadDetail();
      }
    }, 2000);
    return () => {
      if (timer) window.clearInterval(timer);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id, chart?.status]);

  const resultMeta = useMemo(() => parseResultMeta((chart as any)?.resultMeta, chart), [chart]);

  const statusNode = useMemo(() => {
    if (!chart?.status || chart.status === 'wait') {
      return <Result status="warning" title="任务排队中" subTitle={chart?.execMessage || '系统正在排队处理'} />;
    }
    if (chart.status === 'running') {
      return <Result status="info" title="正在生成分析结论" subTitle={chart?.execMessage || '请稍候，系统正在计算'} />;
    }
    if (chart.status === 'failed') {
      return <Result status="error" title="生成失败" subTitle={chart?.execMessage || '请重试一次'} />;
    }
    return null;
  }, [chart?.status, chart?.execMessage]);

  const getRiskTag = (risk?: string) => {
    if (risk === 'high') return <Tag color="error">高风险</Tag>;
    if (risk === 'medium') return <Tag color="warning">中风险</Tag>;
    return <Tag color="success">低风险</Tag>;
  };

  const getPriorityTag = (priority?: string) => {
    if (priority === 'high') return <Tag color="error">高优先级</Tag>;
    if (priority === 'low') return <Tag color="default">低优先级</Tag>;
    return <Tag color="warning">中优先级</Tag>;
  };

  const handleBack = () => {
    if (window.history.length > 1) {
      navigate(-1);
      return;
    }
    navigate('/my_chart');
  };

  return (
    <div>
      <Space align="center" style={{ marginBottom: 8 }}>
        <Button onClick={handleBack}>返回</Button>
        <Typography.Title level={4} style={{ margin: 0 }}>
          分析结果详情
        </Typography.Title>
      </Space>
      <Tag color="blue">chartId: {id}</Tag>
      <Spin spinning={loading}>
        {statusNode}
        <Row gutter={16} style={{ marginTop: 12 }}>
          <Col span={24}>
            <Card title="管理层摘要">
              <Space direction="vertical" size={10} style={{ width: '100%' }}>
                <Space>
                  {getRiskTag(resultMeta?.summary?.riskLevel)}
                  <Typography.Text type="secondary">建议先处理高影响动作，再看技术细节</Typography.Text>
                </Space>
                <Typography.Text strong>{resultMeta?.summary?.headline || '暂无摘要'}</Typography.Text>
                <Space direction="vertical" size={2}>
                  {(resultMeta?.summary?.keyActions || []).slice(0, 3).map((item, index) => (
                    <Typography.Text key={`${item}-${index}`} type="secondary">
                      {`- ${item}`}
                    </Typography.Text>
                  ))}
                </Space>
              </Space>
            </Card>
          </Col>
        </Row>
        <Row gutter={16} style={{ marginTop: 12 }}>
          {(resultMeta?.kpis || []).map((kpi, index) => (
            <Col span={24} md={12} xl={6} key={`${kpi?.name}-${index}`}>
              <Card>
                <Typography.Text type="secondary">{kpi?.name || '-'}</Typography.Text>
                <div style={{ marginTop: 6, marginBottom: 6 }}>
                  <Typography.Title level={4} style={{ margin: 0 }}>
                    {`${kpi?.value ?? '--'}${kpi?.unit || ''}`}
                  </Typography.Title>
                </div>
                <Tag color={kpi?.trend === 'up' ? 'success' : kpi?.trend === 'down' ? 'error' : 'default'}>
                  {kpi?.trend === 'up' ? '上升' : kpi?.trend === 'down' ? '下降' : '持平'}
                </Tag>
              </Card>
            </Col>
          ))}
        </Row>
        <Row gutter={16} style={{ marginTop: 12 }}>
          <Col span={24} xl={24}>
            <Card title="问题与机会（按优先级）">
              {(resultMeta?.issues || []).length ? (
                <Row gutter={[12, 12]}>
                  {(resultMeta?.issues || []).map((item, index) => {
                    const actionPlan = buildStructuredAction(item, chart?.genResult || '');
                    return (
                      <Col key={`${item?.title || 'issue'}-${index}`} xs={24} md={12} xl={8}>
                        <Card size="small" bodyStyle={{ padding: 12 }}>
                          <Space direction="vertical" size={6} style={{ width: '100%' }}>
                            <Space>
                              {getPriorityTag(item?.priority)}
                              <Typography.Text strong>{actionPlan.problem}</Typography.Text>
                            </Space>
                            <Typography.Text type="secondary">{`影响：${buildImpactTemplate(chart?.genResult || '')}`}</Typography.Text>
                            <Typography.Text>{`建议动作：${actionPlan.action}`}</Typography.Text>
                            <Typography.Text type="secondary">{`执行角色：${actionPlan.owner}`}</Typography.Text>
                            <Typography.Text type="secondary">{`执行窗口：${actionPlan.executeWindow}`}</Typography.Text>
                            <Typography.Text type="secondary">{`完成定义：${actionPlan.done}`}</Typography.Text>
                          </Space>
                        </Card>
                      </Col>
                    );
                  })}
                </Row>
              ) : (
                <Typography.Text type="secondary">暂无问题与机会项</Typography.Text>
              )}
            </Card>
          </Col>
        </Row>
      </Spin>
    </div>
  );
};

export default ChartResultPage;
