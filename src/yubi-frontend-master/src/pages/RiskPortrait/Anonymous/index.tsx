import {
  fetchAnonymousPortraitSummary,
  type AnonymousPortraitData,
  type AnonymousCluster,
} from '@/services/yubi/riskPortraitController';
import RiskPortraitNavBar from '@/components/RiskPortraitNavBar';
import { ReloadOutlined } from '@ant-design/icons';
import { Alert, Button, Card, Col, Descriptions, Form, Input, Row, Space, Table, Tag, Tooltip, Typography, message } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import React, { useEffect, useMemo, useState } from 'react';
import '../Member/index.less';

const { Paragraph, Title, Text } = Typography;

const SAMPLE_MASK_LABEL: Record<string, string> = {
  device: '设备侧',
  payToken: '支付侧',
};

/** 将 dev_/ch_wx_/sess_ 等技术前缀转为业务可读；悬停可查看原始片段 */
function formatWeakSignalForDisplay(raw: string): string {
  const s = (raw || '').trim();
  if (s.startsWith('dev_')) return `设备侧 · ${s.slice(4)}`;
  if (s.startsWith('ch_wx_')) return `微信渠道 · ${s.slice(6)}`;
  if (s.startsWith('sess_')) return `会话 · ${s.slice(5)}`;
  if (s.startsWith('ch_')) return `渠道 · ${s.slice(3)}`;
  return s;
}

function anonymousAlertType(data: AnonymousPortraitData | null): 'info' | 'success' | 'warning' | undefined {
  if (!data?.aggregationNote) return undefined;
  if (data.dataMode === 'import') {
    return data.total === 0 ? 'warning' : 'success';
  }
  return 'info';
}

const RiskPortraitAnonymous: React.FC = () => {
  const [form] = Form.useForm<{ weakSignal?: string }>();
  const [loading, setLoading] = useState(false);
  const [data, setData] = useState<AnonymousPortraitData | null>(null);
  const [page, setPage] = useState({ current: 1, pageSize: 10 });

  const load = async (weak?: string, pageArg?: { current: number; pageSize: number }) => {
    const p = pageArg ?? page;
    setLoading(true);
    try {
      const body: Parameters<typeof fetchAnonymousPortraitSummary>[0] = {
        current: p.current,
        pageSize: p.pageSize,
      };
      const w = weak?.trim();
      if (w) body.weakSignal = w;
      const res = await fetchAnonymousPortraitSummary(body);
      setData(res);
      setPage({ current: Number(res.current) || p.current, pageSize: Number(res.pageSize) || p.pageSize });
    } catch (e: any) {
      message.error(e?.message || '加载失败');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    load(undefined, { current: 1, pageSize: 10 });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const onFilter = async () => {
    const v = await form.validateFields().catch(() => null);
    if (!v) return;
    const next = { current: 1, pageSize: page.pageSize };
    setPage(next);
    await load(v.weakSignal, next);
  };

  const clusterColumns: ColumnsType<AnonymousCluster> = useMemo(
    () => [
      { title: '匿名分组编号', dataIndex: 'anonymousBucketId', width: 140, align: 'left' },
      {
        title: '关联来源（已匿名）',
        dataIndex: 'linkedWeakSignals',
        align: 'left',
        render: (signals: string[]) => (
          <Space size={[4, 4]} wrap>
            {(signals || []).map((s) => (
              <Tooltip key={s} title={`内部编号：${s}`}>
                <Tag>{formatWeakSignalForDisplay(s)}</Tag>
              </Tooltip>
            ))}
          </Space>
        ),
      },
      { title: '近七日行为次数', dataIndex: 'eventCount7d', width: 120, align: 'left' },
      {
        title: data?.dataMode === 'import' ? '风险评级' : '风险评级（样例）',
        dataIndex: 'riskTag',
        width: 120,
        align: 'left',
      },
      { title: '最近活动时间', dataIndex: 'lastSeen', width: 170, align: 'left' },
      { title: '主要关联门店', dataIndex: 'topStoreHint', width: 120, align: 'left' },
    ],
    [data?.dataMode],
  );

  const ruleColumns = [
    { title: '信息类型', dataIndex: 'field', width: 120, align: 'left' as const },
    { title: '说明', dataIndex: 'policy', align: 'left' as const },
  ];

  return (
    <div className="rp-member" style={{ padding: 24 }}>
      <RiskPortraitNavBar />
      <Card bordered={false} style={{ boxShadow: '0 1px 2px rgba(0,0,0,0.03)' }}>
        <div className="rp-member-hero">
          <Title level={4} style={{ marginBottom: 8 }}>
            匿名用户画像
          </Title>
          <Paragraph type="secondary" style={{ marginBottom: 0 }}>
            在未登录场景下，结合设备与渠道等行为判断异常领券、跨店核销与套利风险；可用于阶梯发券与风控策略。下方为匿名客群列表及合规留存说明。
          </Paragraph>
        </div>

        <h3 className="rp-member-section-title" style={{ marginTop: 20 }}>
          查询条件
        </h3>
        <div className="rp-member-toolbar">
          <Form form={form} layout="vertical" onFinish={onFilter}>
            <Row gutter={[16, 0]}>
              <Col xs={24} md={14} lg={12}>
                <Form.Item name="weakSignal" label="关键词（可选）">
                  <Input placeholder="可输入分组编号、标识片段或门店名称进行筛选" allowClear />
                </Form.Item>
              </Col>
              <Col xs={24} md={10} lg={12}>
                <Form.Item label=" " colon={false} style={{ marginBottom: 0 }}>
                  <Space wrap>
                    <Button type="primary" htmlType="submit" loading={loading}>
                      应用筛选
                    </Button>
                    <Button
                      icon={<ReloadOutlined />}
                      onClick={() => {
                        form.resetFields();
                        const next = { current: 1, pageSize: 10 };
                        setPage(next);
                        load(undefined, next);
                      }}
                      loading={loading}
                    >
                      重置并刷新
                    </Button>
                  </Space>
                </Form.Item>
              </Col>
            </Row>
          </Form>
        </div>

        {data?.aggregationNote ? (
          <Alert
            type={anonymousAlertType(data) ?? 'info'}
            showIcon
            message={data.aggregationNote}
            style={{ marginBottom: 12 }}
          />
        ) : null}

        {data?.weakSignalFilter ? (
          <Text type="secondary" style={{ display: 'block', marginBottom: 12 }}>
            当前关键词：{data.weakSignalFilter}
          </Text>
        ) : null}

        {data ? (
          <>
            <h3 className="rp-member-section-title" style={{ marginTop: 8 }}>
              匿名客群列表
            </h3>
            <Paragraph type="secondary" style={{ marginBottom: 10, fontSize: 13 }}>
              下表中的编号均为匿名处理后的片段，用于区分来源，不是完整的设备号或账号。
            </Paragraph>
            <div className="rp-member-table-wrap">
              <Table<AnonymousCluster>
                size="small"
                loading={loading}
                rowKey="anonymousBucketId"
                dataSource={data.clusters}
                columns={clusterColumns}
                pagination={{
                  align: 'start',
                  current: page.current,
                  pageSize: page.pageSize,
                  total: data.total,
                  showSizeChanger: true,
                  pageSizeOptions: ['10', '20', '50'],
                  onChange: (c, ps) => {
                    const next = { current: c, pageSize: ps || 10 };
                    setPage(next);
                    const wf = form.getFieldValue('weakSignal') as string | undefined;
                    load(wf, next);
                  },
                }}
              />
            </div>

            <h3 className="rp-member-section-title" style={{ marginTop: 24 }}>
              合规与数据留存
            </h3>
            <div className="rp-member-detail">
              <Descriptions
                column={1}
                size="small"
                bordered
                labelStyle={{ width: 160, textAlign: 'left' }}
                contentStyle={{ textAlign: 'left' }}
              >
                <Descriptions.Item label="建议留存天数">{data.desensitization.retentionSuggestionDays}</Descriptions.Item>
                <Descriptions.Item label="用途与边界">{data.desensitization.purposeStatement}</Descriptions.Item>
                <Descriptions.Item label="脱敏展示样式">
                  <Space direction="vertical">
                    {Object.entries(data.desensitization.sampleMasked || {}).map(([k, v]) => (
                      <Text key={k} code>
                        {SAMPLE_MASK_LABEL[k] ?? k}: {v}
                      </Text>
                    ))}
                  </Space>
                </Descriptions.Item>
              </Descriptions>
              <Table
                style={{ marginTop: 16 }}
                size="small"
                pagination={false}
                rowKey={(r) => r.field}
                dataSource={data.desensitization.rules}
                columns={ruleColumns}
              />
            </div>
          </>
        ) : null}
      </Card>
    </div>
  );
};

export default RiskPortraitAnonymous;
