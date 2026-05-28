import React, { useEffect, useState } from 'react';
import { PageContainer, ProCard } from '@ant-design/pro-components';
import {
  Card, Collapse, Tag, Table, Typography, Spin, Empty, Space, Descriptions, Statistic, Row, Col,
  Timeline, Button, Tooltip,
} from 'antd';
import {
  BulbOutlined, AlertOutlined, LineChartOutlined, QuestionCircleOutlined,
  AuditOutlined, TrophyOutlined, ArrowLeftOutlined, HistoryOutlined,
  WarningOutlined, ExperimentOutlined, ApartmentOutlined,
} from '@ant-design/icons';
import { useParams, history } from '@umijs/max';
import { getReportUsingGET } from '@/services/yubi/autoEdaController';
import type { InsightReport, InsightItem, AnomalyItem, OpenLeadItem } from '@/services/yubi/autoEdaController';
import './index.less';

const { Text, Paragraph, Title } = Typography;

const confidenceBadge = (conf: number | undefined) => {
  const pct = conf != null ? Math.round(conf * 100) : 0;
  if (pct > 75) return <Tag color="success">可信程度 {pct}%</Tag>;
  if (pct > 40) return <Tag color="warning">可信程度 {pct}%</Tag>;
  return <Tag color="error">可信程度 {pct}%</Tag>;
};

const InsightReportPage: React.FC = () => {
  const { sessionId } = useParams<{ sessionId: string }>();
  const [report, setReport] = useState<InsightReport | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (sessionId) {
      getReportUsingGET(sessionId)
        .then((res) => { if (res?.data) setReport(res.data); })
        .finally(() => setLoading(false));
    }
  }, [sessionId]);

  if (loading) return <Spin size="large" style={{ display: 'block', margin: '200px auto' }} />;
  if (!report) return <Empty description="报告尚未生成，请稍后在「探索记录」中查看" />;

  const evidenceColumns = [
    { title: '步骤', dataIndex: 'stepOrder', key: 'stepOrder', width: 50 },
    {
      title: '数据查询',
      dataIndex: 'querySql',
      key: 'querySql',
      width: 280,
      render: (v: string) => (
        <Tooltip title={v}>
          <pre style={{ margin: 0, maxHeight: 60, overflow: 'hidden', fontSize: 12 }}><code>{v}</code></pre>
        </Tooltip>
      ),
    },
    { title: '查到的情况', dataIndex: 'resultSummary', key: 'resultSummary', ellipsis: true },
    { title: '数据条数', dataIndex: 'resultRowCount', key: 'resultRowCount', width: 80 },
    {
      title: '关注程度',
      dataIndex: 'igScore',
      key: 'igScore',
      width: 80,
      render: (v: number) => (v != null ? `${(v * 100).toFixed(0)}%` : '-'),
    },
  ];

  const anomalySeverity = (sigma: number | undefined) => {
    if (!sigma || sigma < 2) return { color: 'warning', label: '需留意' };
    if (sigma < 3) return { color: 'orange', label: '明显异常' };
    return { color: 'red', label: '严重异常' };
  };

  return (
    <PageContainer
      header={{
        title: '智能探索报告',
        subTitle: report.chartName ? `基于图表「${report.chartName}」的自动分析结论` : undefined,
      }}
      extra={[
        <Button key="history" icon={<HistoryOutlined />} onClick={() => history.push('/lineage/history')}>
          探索记录
        </Button>,
        <Button key="back" icon={<ArrowLeftOutlined />} onClick={() => history.push('/lineage/explore')}>
          继续探索
        </Button>,
      ]}
    >
      <ProCard style={{ marginBottom: 16 }} headerBordered>
        <Row gutter={[16, 16]} align="middle">
          <Col xs={24} md={8}>
            <Title level={4} style={{ margin: 0 }}>{report.chartName || '未命名图表'}</Title>
          </Col>
          <Col xs={12} md={4}>
            <Statistic title="查了几次数据" value={report.totalQueriesExecuted || 0} prefix={<LineChartOutlined />} />
          </Col>
          <Col xs={12} md={4}>
            <Statistic title="主要结论" value={(report.executiveSummary || []).length} prefix={<BulbOutlined />} />
          </Col>
          <Col xs={12} md={4}>
            <Statistic
              title="异常提醒"
              value={(report.anomalies || []).length}
              prefix={<WarningOutlined />}
              valueStyle={{ color: (report.anomalies || []).length > 0 ? '#ff4d4f' : '#52c41a' }}
            />
          </Col>
          <Col xs={12} md={4}>
            <Statistic title="延伸分析" value={(report.deepInsights || []).length} prefix={<ExperimentOutlined />} />
          </Col>
        </Row>
      </ProCard>

      <ProCard
        title={<Space><BulbOutlined style={{ color: '#1677ff' }} /> 主要结论（先看这里）</Space>}
        style={{ marginBottom: 16 }}
        headerBordered
      >
        {(report.executiveSummary || []).length === 0 ? (
          <Empty description="本次探索暂未提炼出明确结论" />
        ) : (
          <Timeline
            items={(report.executiveSummary || []).map((item: InsightItem, i: number) => ({
              color: (item.confidence || 0) > 0.7 ? 'green' : (item.confidence || 0) > 0.4 ? 'blue' : 'red',
              dot: <BulbOutlined style={{ fontSize: 16 }} />,
              children: (
                <ProCard
                  key={i}
                  size="small"
                  ghost
                  title={
                    <Space wrap>
                      {confidenceBadge(item.confidence)}
                      <Text strong>{item.title || `结论 ${i + 1}`}</Text>
                    </Space>
                  }
                >
                  <Paragraph>{item.description}</Paragraph>
                  {item.actionableAdvice && (
                    <Paragraph type="success" style={{ marginBottom: 0 }}>
                      <Text strong>建议：</Text>
                      {item.actionableAdvice}
                    </Paragraph>
                  )}
                  {item.evidenceChain && item.evidenceChain.length > 0 && (
                    <Collapse
                      ghost
                      style={{ marginTop: 8 }}
                      items={[{
                        key: 'evidence',
                        label: <Space><ApartmentOutlined /> 查看依据（{item.evidenceChain.length} 步）</Space>,
                        children: (
                          <Table
                            rowKey="stepOrder"
                            dataSource={item.evidenceChain}
                            columns={evidenceColumns}
                            pagination={false}
                            size="small"
                          />
                        ),
                      }]}
                    />
                  )}
                </ProCard>
              ),
            }))}
          />
        )}
      </ProCard>

      {report.anomalies && report.anomalies.length > 0 && (
        <ProCard
          title={<Space><AlertOutlined style={{ color: '#ff4d4f' }} /> 需要关注的异常</Space>}
          style={{ marginBottom: 16, borderColor: '#ffccc7' }}
          headerBordered
        >
          {(report.anomalies || []).map((a: AnomalyItem, i: number) => {
            const sev = anomalySeverity(a.deviationSigma);
            return (
              <ProCard
                key={i}
                size="small"
                style={{ marginBottom: 8, borderLeft: `3px solid ${sev.color}` }}
                title={
                  <Space wrap>
                    <Tag color={sev.color}>{sev.label}</Tag>
                    <Tag>{a.metricName || '指标'}</Tag>
                    <Text>{a.anomalyDescription}</Text>
                  </Space>
                }
              >
                <Descriptions column={2} size="small" bordered>
                  <Descriptions.Item label="可能原因">{a.rootCause || '待进一步核对'}</Descriptions.Item>
                  <Descriptions.Item label="对经营的影响">{a.businessImpact || '待评估'}</Descriptions.Item>
                </Descriptions>
              </ProCard>
            );
          })}
        </ProCard>
      )}

      {report.deepInsights && report.deepInsights.length > 0 && (
        <ProCard
          title={<Space><ExperimentOutlined style={{ color: '#722ed1' }} /> 延伸分析</Space>}
          style={{ marginBottom: 16 }}
          headerBordered
        >
          {(report.deepInsights || []).map((item: InsightItem, i: number) => (
            <ProCard
              key={i}
              size="small"
              ghost
              style={{ marginBottom: 8 }}
              title={<Text strong>{item.title || `分析 ${i + 1}`}</Text>}
              extra={confidenceBadge(item.confidence)}
            >
              <Paragraph>{item.description}</Paragraph>
              {item.actionableAdvice && (
                <Paragraph>
                  <Text strong>建议：</Text>
                  {item.actionableAdvice}
                </Paragraph>
              )}
            </ProCard>
          ))}
        </ProCard>
      )}

      {report.openLeads && report.openLeads.length > 0 && (
        <ProCard
          title={<Space><QuestionCircleOutlined style={{ color: '#fa8c16' }} /> 还不确定、建议再查</Space>}
          style={{ marginBottom: 16 }}
          headerBordered
        >
          {(report.openLeads || []).map((lead: OpenLeadItem, i: number) => (
            <Card key={i} size="small" style={{ marginBottom: 8, borderLeft: '3px solid #fa8c16' }}>
              <Paragraph><Text strong>待核实：</Text>{lead.hypothesis}</Paragraph>
              <Paragraph><Text type="warning">为何不确定：</Text>{lead.reasonIncomplete}</Paragraph>
              <Paragraph type="secondary">建议补充：{lead.suggestedDataToCollect}</Paragraph>
            </Card>
          ))}
        </ProCard>
      )}

      <ProCard
        title={<Space><AuditOutlined /> 分析过程记录（给懂数据的同学看）</Space>}
        headerBordered
        collapsible
        defaultCollapsed
      >
        <Table
          rowKey="stepNumber"
          dataSource={report.auditTrail || []}
          columns={[
            { title: '步骤', dataIndex: 'stepNumber', key: 'stepNumber', width: 50 },
            {
              title: '数据查询',
              dataIndex: 'querySql',
              key: 'querySql',
              width: 320,
              render: (v: string) => (
                <pre className="audit-sql" style={{ margin: 0, fontSize: 12, maxHeight: 80, overflow: 'auto' }}>
                  <code>{v}</code>
                </pre>
              ),
            },
            { title: '说明', dataIndex: 'decisionReason', key: 'decisionReason', width: 140, ellipsis: true },
            { title: '结果摘要', dataIndex: 'resultSummary', key: 'resultSummary', ellipsis: true },
            { title: '耗时(秒)', dataIndex: 'executionMs', key: 'executionMs', width: 90, render: (ms: number) => (ms != null ? (ms / 1000).toFixed(1) : '-') },
          ]}
          pagination={{ pageSize: 10, showTotal: (t) => `共 ${t} 步` }}
          size="small"
        />
      </ProCard>
    </PageContainer>
  );
};

export default InsightReportPage;
