import RiskPortraitNavBar from '@/components/RiskPortraitNavBar';
import { history, useSearchParams } from '@umijs/max';
import { fetchStorePortrait, type StorePortraitData } from '@/services/yubi/riskPortraitController';
import { ShopOutlined } from '@ant-design/icons';
import { Button, Card, DatePicker, Descriptions, Empty, Progress, Space, Spin, Tag, Typography, message } from 'antd';
import dayjs from 'dayjs';
import ReactECharts from 'echarts-for-react';
import React, { useEffect, useMemo, useState } from 'react';

const { Paragraph, Text } = Typography;

const RiskPortraitStore: React.FC = () => {
  const [searchParams] = useSearchParams();
  const qStoreId = searchParams.get('storeId') || '';
  const qName = searchParams.get('storeName') || '';
  const qStart = searchParams.get('startDate') || '';
  const qEnd = searchParams.get('endDate') || '';

  const [loading, setLoading] = useState(false);
  const [data, setData] = useState<StorePortraitData | null>(null);

  const replaceDateQuery = (start: string, end: string) => {
    const params = new URLSearchParams(searchParams.toString());
    params.set('startDate', start);
    params.set('endDate', end);
    if (qStoreId) params.set('storeId', qStoreId);
    if (qName) params.set('storeName', qName);
    history.replace(`/risk_portrait/store?${params.toString()}`);
  };

  useEffect(() => {
    if (!qStoreId) {
      setData(null);
      return;
    }
    let cancelled = false;
    setLoading(true);
    fetchStorePortrait({
      storeId: qStoreId,
      storeName: qName || undefined,
      startDate: qStart || undefined,
      endDate: qEnd || undefined,
    })
      .then((d) => {
        if (!cancelled) setData(d);
      })
      .catch((e: Error) => {
        if (!cancelled) {
          message.error(e?.message || '门店画像加载失败');
          setData(null);
        }
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [qStoreId, qName, qStart, qEnd]);

  const radarOption = useMemo(() => {
    if (!data) return {};
    const { stability, trust, value } = data.scores;
    return {
      radar: {
        indicator: [
          { name: '稳定性', max: 100 },
          { name: '可信度', max: 100 },
          { name: '价值', max: 100 },
        ],
        radius: '65%',
      },
      series: [
        {
          type: 'radar',
          data: [{ value: [stability, trust, value], name: '画像' }],
          areaStyle: { opacity: 0.2 },
          lineStyle: { width: 2 },
        },
      ],
    };
  }, [data]);

  return (
    <div style={{ padding: 24, maxWidth: 960 }}>
      <RiskPortraitNavBar
        extra={
          <Button type="link" size="small" icon={<ShopOutlined />} onClick={() => history.push('/biz/bigscreen')}>
            经营指挥大屏
          </Button>
        }
      />

      {!qStoreId ? (
        <Card>
          <Empty description="未指定门店：请从经营指挥大屏「门店视角」进入，或在 URL 增加 ?storeId=xxx">
            <Button type="primary" onClick={() => history.push('/biz/bigscreen')}>
              去大屏选择门店
            </Button>
          </Empty>
        </Card>
      ) : (
        <Spin spinning={loading}>
          <Card
            title={`门店画像 ${qName ? `· ${qName}` : ''}`}
            extra={
              <Space wrap size="middle" align="center">
                <Text type="secondary">storeId: {qStoreId}</Text>
                <Space size={4} align="center">
                  <Text type="secondary">统计窗口</Text>
                  <DatePicker.RangePicker
                    allowClear={false}
                    value={
                      qStart && qEnd && dayjs(qStart).isValid() && dayjs(qEnd).isValid()
                        ? [dayjs(qStart), dayjs(qEnd)]
                        : undefined
                    }
                    placeholder={['开始', '结束']}
                    onChange={(dates) => {
                      if (!dates?.[0] || !dates?.[1]) return;
                      replaceDateQuery(dates[0].format('YYYY-MM-DD'), dates[1].format('YYYY-MM-DD'));
                    }}
                  />
                </Space>
              </Space>
            }
          >
            {data ? (
              <>
                <Space wrap style={{ marginBottom: 16 }}>
                  <Tag color={data.riskLevel === '高风险' ? 'red' : data.riskLevel === '中风险' ? 'orange' : 'green'}>
                    {data.riskLevel}
                  </Tag>
                  {data.windowNote ? <Text type="secondary">{data.windowNote}</Text> : null}
                </Space>

                <RowMini scores={data.scores} />

                <div style={{ height: 280, marginTop: 8 }}>
                  <ReactECharts option={radarOption} style={{ height: '100%' }} />
                </div>

                <Descriptions column={1} size="small" bordered style={{ marginTop: 16 }}>
                  <Descriptions.Item label="稳定性">{data.scores.stability}</Descriptions.Item>
                  <Descriptions.Item label="可信度">{data.scores.trust}</Descriptions.Item>
                  <Descriptions.Item label="价值">{data.scores.value}</Descriptions.Item>
                </Descriptions>

                <Paragraph strong style={{ marginTop: 16 }}>
                  说明 / 命中原因
                </Paragraph>
                <ul style={{ paddingLeft: 20, marginTop: 0 }}>
                  {data.reasons.map((r, i) => (
                    <li key={i}>{r}</li>
                  ))}
                </ul>
                <Paragraph strong>建议动作</Paragraph>
                <ul style={{ paddingLeft: 20, marginTop: 0 }}>
                  {data.suggestedActions.map((r, i) => (
                    <li key={i}>{r}</li>
                  ))}
                </ul>
              </>
            ) : (
              !loading && <Empty description="暂无数据" />
            )}
          </Card>
        </Spin>
      )}
    </div>
  );
};

function RowMini({ scores }: { scores: StorePortraitData['scores'] }) {
  return (
    <Space direction="vertical" style={{ width: '100%' }} size={8}>
      <div>
        <Text>稳定性</Text>
        <Progress percent={scores.stability} size="small" />
      </div>
      <div>
        <Text>可信度</Text>
        <Progress percent={scores.trust} size="small" />
      </div>
      <div>
        <Text>价值</Text>
        <Progress percent={scores.value} size="small" />
      </div>
    </Space>
  );
}

export default RiskPortraitStore;
