import { history } from '@umijs/max';
import { Button, Space, Typography } from 'antd';
import React from 'react';

const { Text } = Typography;

export type RiskPortraitNavBarProps = {
  /** 右侧在「返回总览」之前插入的按钮或链接 */
  extra?: React.ReactNode;
  /** 总览页传 false，仅展示产品线标识 */
  showBack?: boolean;
};

/**
 * 风险画像子页统一顶栏：产品线标识 + 返回入口，便于演示时形成同一产品观感。
 */
const RiskPortraitNavBar: React.FC<RiskPortraitNavBarProps> = ({ extra, showBack = true }) => {
  return (
    <div
      style={{
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'space-between',
        flexWrap: 'wrap',
        gap: 8,
        marginBottom: 16,
        paddingBottom: 12,
        borderBottom: '1px solid rgba(5, 5, 5, 0.06)',
      }}
    >
      <Text type="secondary" style={{ fontSize: 13 }}>
        风险与信用画像
      </Text>
      <Space wrap size="small">
        {extra}
        {showBack ? (
          <Button type="link" size="small" style={{ paddingInline: 4 }} onClick={() => history.push('/risk_portrait/overview')}>
            返回总览
          </Button>
        ) : null}
      </Space>
    </div>
  );
};

export default RiskPortraitNavBar;
