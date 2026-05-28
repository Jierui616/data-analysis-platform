import React, { useEffect, useState } from 'react';
import { PageContainer } from '@ant-design/pro-components';
import {
  Card, Collapse, Tag, Table, Typography, Spin, Empty, Space, Descriptions, Statistic, Row, Col
} from 'antd';
import {
  BulbOutlined, AlertOutlined, LineChartOutlined, QuestionCircleOutlined,
  AuditOutlined
} from '@ant-design/icons';
import { useParams } from '@umijs/max';
import { getReportUsingGET } from '@/services/yubi/autoEdaController';
import type { InsightReport, InsightItem, AnomalyItem, OpenLeadItem, AuditStep } from '@/services/yubi/autoEdaController';
import './index.less';

const { Text, Paragraph, Title } = Typography;

const InsightReportPage: React.FC = () => {
  const { sessionId } = useParams<{ sessionId: string }>();
  const [report, setReport] = useState<InsightReport | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (sessionId) {
      getReportUsingGET(sessionId)
        .then(res => { if (res?.data) setReport(res.data); })
        .finally(() => setLoading(false));
    }
  }, [sessionId]);

  if (loading) return <Spin size="large" style={{ display: 'block', margin: '200px auto' }} />;
  if (!report) return <Empty description="报告未生成" />;

  const evidenceColumns = [
    { title: '步骤', dataIndex: 'stepOrder', key: 'stepOrder', width: 60 },
    { title: '查询SQL', dataIndex: 'querySql', key: 'querySql', ellipsis: true },
    { title: '结果摘要', dataIndex: 'resultSummary', key: 'resultSummary', ellipsis: true },
    { title: '结果行数', dataIndex: 'resultRowCount', key: 'resultRowCount', width: 80 },
    { title: 'IG', dataIndex: 'igScore', key: 'igScore', width: 60, render: (v: number) => v?.toFixed(3) },
  ];

  return (
    <PageContainer>
      <Title level={4}>{report.chartName} — 自主探索分析报告</Title>
      <Row gutter={16} style={{ marginBottom: 24 }}>
        <Col span={6}><Statistic title="探索查询数" value={report.totalQueriesExecuted} /></Col>
        <Col span={6}><Statistic title="关键发现" value={(report.executiveSummary || []).length} /></Col>
        <Col span={6}><Statistic title="异常发现" value={(report.anomalies || []).length} /></Col>
        <Col span={6}><Statistic title="深层洞察" value={(report.deepInsights || []).length} /></Col>
      </Row>

      {/* 1. Executive Summary */}
      <Card
        title={<><BulbOutlined /> 关键发现 (Executive Summary)</>}
        style={{ marginBottom: 16 }}
      >
        {(report.executiveSummary || []).map((item: InsightItem, i: number) => (
          <Card key={i} type="inner" size="small" style={{ marginBottom: 8 }}
            title={
              <Space>
                <Tag color="blue">置信度 {(item.confidence || 0) * 100}%</Tag>
                {item.title}
              </Space>
            }
          >
            <Paragraph>{item.description}</Paragraph>
            {item.actionableAdvice && (
              <Text type="success">💡 建议: {item.actionableAdvice}</Text>
            )}
            {item.evidenceChain && item.evidenceChain.length > 0 && (
              <Collapse ghost items={[{
                key: 'evidence', label: '证据链',
                children: <Table rowKey="stepOrder" dataSource={item.evidenceChain} columns={evidenceColumns} pagination={false} size="small" />
              }]} />
            )}
          </Card>
        ))}
      </Card>

      {/* 2. Anomalies */}
      {report.anomalies && report.anomalies.length > 0 && (
        <Card
          title={<><AlertOutlined /> 异常与预警 (Anomalies & Alerts)</>}
          style={{ marginBottom: 16 }}
        >
          {(report.anomalies || []).map((a: AnomalyItem, i: number) => (
            <Card key={i} type="inner" size="small" style={{ marginBottom: 8 }}
              title={
                <Space>
                  <Tag color="red">{a.metricName}</Tag>
                  {a.anomalyDescription}
                  <Tag>{a.deviationSigma}σ</Tag>
                </Space>
              }
            >
              <Descriptions column={2} size="small">
                <Descriptions.Item label="根因">{a.rootCause}</Descriptions.Item>
                <Descriptions.Item label="业务影响">{a.businessImpact}</Descriptions.Item>
              </Descriptions>
            </Card>
          ))}
        </Card>
      )}

      {/* 3. Deep Insights */}
      {report.deepInsights && report.deepInsights.length > 0 && (
        <Card
          title={<><LineChartOutlined /> 深层洞察 (Deep Insights)</>}
          style={{ marginBottom: 16 }}
        >
          {(report.deepInsights || []).map((item: InsightItem, i: number) => (
            <Card key={i} type="inner" size="small" style={{ marginBottom: 8 }}
              title={item.title}
              extra={<Tag>置信度 {(item.confidence || 0) * 100}%</Tag>}
            >
              <Paragraph>{item.description}</Paragraph>
              {item.actionableAdvice && <Text type="success">💡 {item.actionableAdvice}</Text>}
            </Card>
          ))}
        </Card>
      )}

      {/* 4. Open Leads */}
      {report.openLeads && report.openLeads.length > 0 && (
        <Card
          title={<><QuestionCircleOutlined /> 待确认线索 (Open Leads)</>}
          style={{ marginBottom: 16 }}
        >
          {(report.openLeads || []).map((lead: OpenLeadItem, i: number) => (
            <Card key={i} type="inner" size="small" style={{ marginBottom: 8 }}>
              <Paragraph><Text strong>假设:</Text> {lead.hypothesis}</Paragraph>
              <Paragraph><Text type="warning">原因:</Text> {lead.reasonIncomplete}</Paragraph>
              <Paragraph><Text type="secondary">建议补充: {lead.suggestedDataToCollect}</Text></Paragraph>
            </Card>
          ))}
        </Card>
      )}

      {/* 5. Audit Trail */}
      <Card title={<><AuditOutlined /> 探索过程 (Audit Trail)</>}>
        <Table
          rowKey="stepNumber"
          dataSource={report.auditTrail || []}
          columns={[
            { title: '#', dataIndex: 'stepNumber', key: 'stepNumber', width: 50 },
            { title: 'SQL', dataIndex: 'querySql', key: 'querySql', ellipsis: true, width: 300 },
            { title: '决策理由', dataIndex: 'decisionReason', key: 'decisionReason', width: 150 },
            { title: '结果摘要', dataIndex: 'resultSummary', key: 'resultSummary', ellipsis: true },
            { title: 'IG', dataIndex: 'igScore', key: 'igScore', width: 70, render: (v: number) => v?.toFixed(3) },
            { title: '耗时ms', dataIndex: 'executionMs', key: 'executionMs', width: 80 },
          ]}
          pagination={{ pageSize: 10 }}
          size="small"
        />
      </Card>
    </PageContainer>
  );
};

export default InsightReportPage;
