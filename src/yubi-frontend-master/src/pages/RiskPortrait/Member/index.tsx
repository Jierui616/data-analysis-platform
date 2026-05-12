import RiskPortraitNavBar from '@/components/RiskPortraitNavBar';
import {
  fetchMemberActivity,
  fetchMemberWatchlist,
  type MemberActivityData,
  type MemberWatchlistData,
  type MemberWatchlistRow,
} from '@/services/yubi/riskPortraitController';
import { SearchOutlined } from '@ant-design/icons';
import {
  Alert,
  Button,
  Card,
  Col,
  DatePicker,
  Form,
  Input,
  Row,
  Select,
  Space,
  Statistic,
  Table,
  Tag,
  Typography,
  message,
} from 'antd';
import type { Dayjs } from 'dayjs';
import dayjs from 'dayjs';
import React, { useCallback, useEffect, useState } from 'react';
import './index.less';

const { Paragraph, Title, Text } = Typography;

const DIM_OPTIONS = [
  { value: 'memberId' as const, label: '会员 ID' },
  { value: 'phone' as const, label: '手机号' },
  { value: 'payAccount' as const, label: '支付账号' },
];

const RISK_FILTER = [
  { value: '高风险', label: '高风险' },
  { value: '中风险', label: '中风险' },
  { value: '低风险', label: '低风险' },
];

function watchlistAlertType(w: MemberWatchlistData | null): 'info' | 'success' | 'warning' | undefined {
  if (!w?.dataSourceNote) return undefined;
  if (w.dataMode === 'import') {
    return (w.total ?? 0) === 0 ? 'warning' : 'success';
  }
  return 'info';
}

function detailAlertType(d: MemberActivityData): 'info' | 'success' | 'warning' {
  if (d.dataMode === 'import') {
    const empty = (d.transactions?.length ?? 0) === 0 && (d.couponRedemptions?.length ?? 0) === 0;
    return empty ? 'warning' : 'success';
  }
  return 'info';
}

const RiskPortraitMember: React.FC = () => {
  const [listForm] = Form.useForm<{ dateRange?: [Dayjs, Dayjs]; riskLevel?: string; storeKeyword?: string }>();
  const [preciseForm] = Form.useForm<{ dimension: 'memberId' | 'phone' | 'payAccount'; value: string; dateRange?: [Dayjs, Dayjs] }>();

  const [listLoading, setListLoading] = useState(false);
  const [watchlist, setWatchlist] = useState<MemberWatchlistData | null>(null);
  const [page, setPage] = useState({ current: 1, pageSize: 10 });

  const [detailLoading, setDetailLoading] = useState(false);
  const [detail, setDetail] = useState<MemberActivityData | null>(null);
  const [detailFromList, setDetailFromList] = useState(false);

  const [preciseLoading, setPreciseLoading] = useState(false);

  const loadWatchlist = useCallback(async (pageArg: { current: number; pageSize: number }) => {
    setListLoading(true);
    try {
      const v = await listForm.validateFields().catch(() => listForm.getFieldsValue());
      const dr = v.dateRange;
      const body: Parameters<typeof fetchMemberWatchlist>[0] = {
        current: pageArg.current,
        pageSize: pageArg.pageSize,
      };
      if (dr?.[0] && dr?.[1]) {
        body.startDate = dr[0].format('YYYY-MM-DD');
        body.endDate = dr[1].format('YYYY-MM-DD');
      }
      if (v.riskLevel) body.riskLevel = v.riskLevel;
      const kw = (v.storeKeyword ?? '').trim();
      if (kw) body.storeKeyword = kw;
      const res = await fetchMemberWatchlist(body);
      setWatchlist(res);
      setPage({ current: Number(res.current) || pageArg.current, pageSize: Number(res.pageSize) || pageArg.pageSize });
    } catch (e: any) {
      if (e?.errorFields) return;
      message.error(e?.message || '名单加载失败');
    } finally {
      setListLoading(false);
    }
  }, [listForm]);

  useEffect(() => {
    loadWatchlist({ current: 1, pageSize: 10 });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const onListSearch = () => {
    const next = { current: 1, pageSize: page.pageSize };
    setPage(next);
    loadWatchlist(next);
  };

  const openDetailFromRow = async (row: MemberWatchlistRow) => {
    if (!watchlist?.period) return;
    setDetail(null);
    setDetailLoading(true);
    setDetailFromList(true);
    try {
      const res = await fetchMemberActivity({
        dimension: 'memberId',
        value: row.memberId,
        startDate: watchlist.period.startDate,
        endDate: watchlist.period.endDate,
      });
      setDetail(res);
    } catch (e: any) {
      message.error(e?.message || '明细加载失败');
    } finally {
      setDetailLoading(false);
    }
  };

  const onPreciseSearch = async () => {
    try {
      const v = await preciseForm.validateFields();
      setPreciseLoading(true);
      setDetail(null);
      setDetailFromList(false);
      const raw = (v.value ?? '').trim();
      if (!raw) {
        message.warning('请输入查询值');
        return;
      }
      const body: Parameters<typeof fetchMemberActivity>[0] = {
        dimension: v.dimension,
        value: raw,
      };
      if (v.dateRange?.[0] && v.dateRange?.[1]) {
        body.startDate = v.dateRange[0].format('YYYY-MM-DD');
        body.endDate = v.dateRange[1].format('YYYY-MM-DD');
      }
      const res = await fetchMemberActivity(body);
      setDetail(res);
    } catch (e: any) {
      if (e?.errorFields) return;
      message.error(e?.message || '查询失败');
    } finally {
      setPreciseLoading(false);
    }
  };

  return (
    <div className="rp-member" style={{ padding: 24 }}>
      <RiskPortraitNavBar />
      <Card bordered={false} style={{ boxShadow: '0 1px 2px rgba(0,0,0,0.03)' }}>
        <div className="rp-member-hero">
          <Title level={4} style={{ marginBottom: 8 }}>
            会员 / 可识别用户画像
          </Title>
          <Paragraph type="secondary" style={{ marginBottom: 0 }}>
            稳定性（频次、客单、复购周期）、可信度（异常退货、异常领券核销、「羊毛党」模式）、价值（毛利贡献、忠诚度、触达）。默认从「关注名单」浏览；需要稽核或工单时再使用下方精确查询。
          </Paragraph>
        </div>

        <h3 className="rp-member-section-title" style={{ marginTop: 20 }}>
          会员关注名单
        </h3>
        <div className="rp-member-toolbar">
          <Form
            form={listForm}
            layout="vertical"
            initialValues={{
              dateRange: [dayjs().subtract(29, 'day'), dayjs()],
              storeKeyword: '',
            }}
          >
            <Row gutter={[16, 0]}>
              <Col xs={24} sm={10}>
                <Form.Item name="dateRange" label="统计区间">
                  <DatePicker.RangePicker style={{ width: '100%' }} />
                </Form.Item>
              </Col>
              <Col xs={24} sm={6}>
                <Form.Item name="riskLevel" label="风险等级">
                  <Select options={RISK_FILTER} allowClear placeholder="全部" style={{ width: '100%' }} />
                </Form.Item>
              </Col>
              <Col xs={24} sm={8}>
                <Form.Item name="storeKeyword" label="门店关键词">
                  <Input placeholder="匹配主要活跃门店" allowClear />
                </Form.Item>
              </Col>
            </Row>
            <Form.Item style={{ marginBottom: 0 }}>
              <Button type="primary" icon={<SearchOutlined />} loading={listLoading} onClick={onListSearch}>
                查询名单
              </Button>
            </Form.Item>
          </Form>
        </div>

        {watchlist?.dataSourceNote ? (
          <Alert
            type={watchlistAlertType(watchlist) ?? 'info'}
            showIcon
            message={watchlist.dataSourceNote}
            style={{ marginBottom: 12 }}
          />
        ) : null}

        <div className="rp-member-table-wrap">
        <Table<MemberWatchlistRow>
          rowKey="memberId"
          loading={listLoading}
          size="small"
          dataSource={watchlist?.records || []}
          pagination={{
            align: 'start',
            current: page.current,
            pageSize: page.pageSize,
            total: watchlist?.total ?? 0,
            showSizeChanger: true,
            pageSizeOptions: ['10', '20', '50'],
            onChange: (c, ps) => {
              const next = { current: c, pageSize: ps || 10 };
              setPage(next);
              loadWatchlist(next);
            },
          }}
          columns={[
            { title: '脱敏展示', dataIndex: 'displayRef', width: 110, align: 'left' },
            { title: '风险等级', dataIndex: 'riskLevel', width: 90, align: 'left' },
            {
              title: '标签',
              dataIndex: 'tags',
              align: 'left',
              render: (tags: string[]) => (
                <Space size={[4, 4]} wrap>
                  {(tags || []).map((t) => (
                    <Tag key={t}>{t}</Tag>
                  ))}
                </Space>
              ),
            },
            { title: '订单数', dataIndex: 'orderCount', width: 80, align: 'left' },
            { title: '券核销', dataIndex: 'couponRedeemCount', width: 80, align: 'left' },
            { title: '退款倾向', dataIndex: 'refundHint', width: 80, align: 'left' },
            { title: '最近活跃', dataIndex: 'lastActive', width: 170, align: 'left' },
            { title: '主要门店', dataIndex: 'topStore', ellipsis: true, align: 'left' },
            {
              title: '操作',
              key: 'act',
              width: 100,
              align: 'left',
              render: (_, row) => (
                <Button type="link" size="small" onClick={() => openDetailFromRow(row)}>
                  查看明细
                </Button>
              ),
            },
          ]}
        />
        </div>

        {detail || detailLoading ? (
          <Card
            className="rp-member-detail"
            size="small"
            title={detailFromList ? '明细（来自关注名单）' : '明细（精确查询）'}
            loading={detailLoading && !detail}
            style={{ marginBottom: 16 }}
          >
            {detail ? (
              <>
                {detail.dataSourceNote ? (
                  <Alert type={detailAlertType(detail)} showIcon message={detail.dataSourceNote} style={{ marginBottom: 12 }} />
                ) : null}
                <Space direction="vertical" size="middle" style={{ width: '100%' }}>
                  <Text type="secondary">
                    查询维度：{DIM_OPTIONS.find((d) => d.value === detail.dimension)?.label ?? detail.dimension}；展示脱敏：{detail.maskedQuery}；区间：
                    {detail.period.startDate} ~ {detail.period.endDate}
                  </Text>
                  <Row gutter={16} justify="start">
                    <Col span={6}>
                      <Statistic title="订单笔数" value={detail.summary.orderCount} />
                    </Col>
                    <Col span={6}>
                      <Statistic title="交易金额合计（元）" value={detail.summary.totalAmount} />
                    </Col>
                    <Col span={6}>
                      <Statistic title="券核销条数" value={detail.summary.couponRedeemCount} />
                    </Col>
                    <Col span={6}>
                      <Statistic title="最近活跃" value={detail.summary.lastActive || '—'} />
                    </Col>
                  </Row>
                  <Card size="small" title="交易明细">
                    <Table
                      size="small"
                      rowKey={(r, i) => `${r.time}-${i}`}
                      pagination={false}
                      dataSource={detail.transactions}
                      columns={[
                        { title: '时间', dataIndex: 'time', width: 170, align: 'left' },
                        { title: '门店', dataIndex: 'storeName', align: 'left' },
                        { title: '金额（元）', dataIndex: 'amount', width: 110, align: 'left' },
                        { title: '渠道', dataIndex: 'channel', width: 100, align: 'left' },
                        { title: '订单号（脱敏）', dataIndex: 'orderNoMasked', width: 140, align: 'left' },
                      ]}
                    />
                  </Card>
                  <Card size="small" title="券核销明细">
                    <Table
                      size="small"
                      rowKey={(r, i) => `${r.time}-${i}`}
                      pagination={false}
                      dataSource={detail.couponRedemptions}
                      columns={[
                        { title: '时间', dataIndex: 'time', width: 170, align: 'left' },
                        { title: '门店', dataIndex: 'storeName', align: 'left' },
                        { title: '券名称', dataIndex: 'couponName', align: 'left' },
                        { title: '面额', dataIndex: 'faceValue', width: 80, align: 'left' },
                        { title: '实抵金额', dataIndex: 'discountAmount', width: 100, align: 'left' },
                        { title: '状态', dataIndex: 'status', width: 90, align: 'left' },
                      ]}
                    />
                  </Card>
                </Space>
              </>
            ) : null}
          </Card>
        ) : null}

        <div className="rp-member-precise">
        <h3 className="rp-member-section-title">精确查询（稽核 / 工单）</h3>
        <Paragraph type="secondary" className="rp-member-precise-hint">
          已知会员 ID、手机号或支付账号时，可直接拉取交易与券核销明细（区间可选）。导入模式下仅支持会员 ID 维度。
        </Paragraph>
        <Form
          form={preciseForm}
          layout="vertical"
          initialValues={{
            dimension: 'memberId',
            dateRange: [dayjs().subtract(29, 'day'), dayjs()],
          }}
          onFinish={onPreciseSearch}
        >
          <Row gutter={16}>
            <Col xs={24} sm={8}>
              <Form.Item name="dimension" label="查询维度" rules={[{ required: true }]}>
                <Select options={DIM_OPTIONS} />
              </Form.Item>
            </Col>
            <Col xs={24} sm={10}>
              <Form.Item name="value" label="查询值" rules={[{ required: true, message: '请输入会员 ID、手机号或支付账号' }]}>
                <Input placeholder="会员 ID、手机号或支付账号" allowClear />
              </Form.Item>
            </Col>
            <Col xs={24} sm={6}>
              <Form.Item name="dateRange" label="统计区间（可选）">
                <DatePicker.RangePicker style={{ width: '100%' }} />
              </Form.Item>
            </Col>
          </Row>
          <Form.Item>
            <Button type="primary" htmlType="submit" icon={<SearchOutlined />} loading={preciseLoading}>
              查询明细
            </Button>
          </Form.Item>
        </Form>
        </div>
      </Card>
    </div>
  );
};

export default RiskPortraitMember;
