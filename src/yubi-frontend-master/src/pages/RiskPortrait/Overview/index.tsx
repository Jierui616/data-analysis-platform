import RiskPortraitNavBar from '@/components/RiskPortraitNavBar';
import { history } from '@umijs/max';
import { fetchPortraitOverview } from '@/services/yubi/riskPortraitController';
import { ArrowRightOutlined, IdcardOutlined, SafetyCertificateOutlined, TeamOutlined } from '@ant-design/icons';
import { Card, Col, Row, Space, Statistic, Typography, message } from 'antd';
import React, { useEffect, useState } from 'react';

const { Paragraph, Title } = Typography;

const RiskPortraitOverview: React.FC = () => {
  const [summary, setSummary] = useState({ storeEvaluated: 0, highRiskStores: 0, pendingAudits: 0 });

  useEffect(() => {
    fetchPortraitOverview()
      .then(setSummary)
      .catch((e: Error) => {
        message.error(e?.message || '画像总览加载失败');
      });
  }, []);

  const cards = [
    {
      title: '门店 / 加盟商画像',
      icon: <TeamOutlined />,
      desc: '铺货额度、账期、回款风险；稳定性 / 可信度 / 价值三维。',
      path: '/risk_portrait/store',
    },
    {
      title: '会员 / 可识别用户',
      icon: <IdcardOutlined />,
      desc: '券控、反作弊、长期行为；待接入会员与核销明细。',
      path: '/risk_portrait/member',
    },
    {
      title: '匿名用户画像',
      icon: <SafetyCertificateOutlined />,
      desc: '设备与支付渠道行为序列；阶梯券与风险拦截。',
      path: '/risk_portrait/anonymous',
    },
  ];

  return (
    <div style={{ padding: 24, maxWidth: 1200 }}>
      <RiskPortraitNavBar showBack={false} />
      <Title level={4}>画像总览</Title>
      <Paragraph type="secondary">
        与经营指挥大屏共用门店与预警口径；下方指标为当前登录用户可见范围内的汇总，便于快速把握风险面。
      </Paragraph>

      <Row gutter={[16, 16]} style={{ marginBottom: 24 }}>
        <Col xs={24} sm={8}>
          <Card size="small">
            <Statistic title="已评估门店" value={summary.storeEvaluated} />
          </Card>
        </Col>
        <Col xs={24} sm={8}>
          <Card size="small">
            <Statistic title="高风险门店（含 P1 预警）" value={summary.highRiskStores} />
          </Card>
        </Col>
        <Col xs={24} sm={8}>
          <Card size="small">
            <Statistic title="待关注预警（P1/P2）" value={summary.pendingAudits} />
          </Card>
        </Col>
      </Row>

      <Row gutter={[16, 16]}>
        {cards.map((c) => (
          <Col xs={24} md={8} key={c.path}>
            <Card
              hoverable
              title={
                <Space>
                  {c.icon}
                  {c.title}
                </Space>
              }
              extra={
                <a
                  onClick={() => history.push(c.path)}
                  style={{ cursor: 'pointer' }}
                >
                  进入 <ArrowRightOutlined />
                </a>
              }
            >
              <Paragraph style={{ marginBottom: 0 }}>{c.desc}</Paragraph>
            </Card>
          </Col>
        ))}
      </Row>

      <Row gutter={[16, 16]} style={{ marginTop: 16 }}>
        <Col xs={24} md={12}>
          <Card title="策略与规则" size="small">
            <Paragraph>
              配置与试算铺货额度、账期、券控及交易拦截策略；支持一键生成决策建议，并可选择是否写入留痕便于审计。
            </Paragraph>
            <a onClick={() => history.push('/risk_portrait/rules')}>打开策略页</a>
          </Card>
        </Col>
        <Col xs={24} md={12}>
          <Card title="命中与留痕" size="small">
            <Paragraph>
              查看策略命中、拦截与人工复核记录，支持按时间与场景筛选，满足合规审计与事后追溯。
            </Paragraph>
            <a onClick={() => history.push('/risk_portrait/audit')}>打开留痕页</a>
          </Card>
        </Col>
      </Row>
    </div>
  );
};

export default RiskPortraitOverview;
