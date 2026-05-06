import { history } from '@umijs/max';
import { pageRiskHitLog, type HitLogRow } from '@/services/yubi/riskPortraitController';
import { ArrowLeftOutlined } from '@ant-design/icons';
import { Button, Card, Table, Typography, message } from 'antd';
import React, { useCallback, useEffect, useState } from 'react';

const { Paragraph, Title } = Typography;

const sceneDisplayName = (code?: string) => {
  const m: Record<string, string> = {
    store_credit: '门店铺货与授信',
    coupon_control: '券与促销风控',
    trade_intercept: '交易异常拦截',
  };
  return (code && m[code]) || code || '—';
};

const actionDisplayName = (code?: string) => {
  const c = String(code || '').toUpperCase();
  const m: Record<string, string> = {
    REVIEW: '待复核',
    PASS: '放行',
    INTERCEPT: '拦截',
    DECISION: '决策记录',
  };
  return m[c] || code || '—';
};

const RiskPortraitAudit: React.FC = () => {
  const [loading, setLoading] = useState(false);
  const [total, setTotal] = useState(0);
  const [current, setCurrent] = useState(1);
  const [pageSize, setPageSize] = useState(10);
  const [dataSource, setDataSource] = useState<HitLogRow[]>([]);

  const load = useCallback(async (page: number, size: number) => {
    setLoading(true);
    try {
      const res = await pageRiskHitLog({ current: page, pageSize: size });
      setDataSource(res.records || []);
      setTotal(Number(res.total) || 0);
      setCurrent(Number(res.current) || page);
      setPageSize(Number(res.pageSize) || size);
    } catch (e: any) {
      message.error(e?.message || '加载失败，请确认留痕功能已初始化后重试');
      setDataSource([]);
      setTotal(0);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load(1, 10);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const columns = [
    { title: '发生时间', dataIndex: 'time', key: 'time', width: 170 },
    {
      title: '决策场景',
      dataIndex: 'scene',
      key: 'scene',
      width: 140,
      render: (v: string) => sceneDisplayName(v),
    },
    { title: '对象', dataIndex: 'subject', key: 'subject', ellipsis: true },
    {
      title: '处置动作',
      dataIndex: 'action',
      key: 'action',
      width: 100,
      render: (v: string) => actionDisplayName(v),
    },
    { title: '规则与摘要', dataIndex: 'rule', key: 'rule', ellipsis: true },
  ];

  return (
    <div style={{ padding: 24, maxWidth: 1100 }}>
      <Button icon={<ArrowLeftOutlined />} onClick={() => history.push('/risk_portrait/overview')} style={{ marginBottom: 16 }}>
        返回总览
      </Button>
      <Card>
        <Title level={5}>命中与留痕</Title>
        <Paragraph type="secondary">
          展示策略命中与决策留痕记录，支持按页浏览。在「策略与规则」中生成决策并勾选保存留痕后，此处可查询与追溯。
        </Paragraph>
        <Table<HitLogRow>
          rowKey={(r) => String(r.id ?? `${r.time}-${r.subject}`)}
          size="small"
          loading={loading}
          columns={columns}
          dataSource={dataSource}
          pagination={{
            current,
            pageSize,
            total,
            showSizeChanger: true,
            showTotal: (t) => `共 ${t} 条`,
            onChange: (p, ps) => {
              const nextSize = ps || pageSize;
              load(p, nextSize);
            },
          }}
        />
      </Card>
    </div>
  );
};

export default RiskPortraitAudit;
