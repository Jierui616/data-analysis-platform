import React, { useEffect, useState } from 'react';
import { PageContainer, ProCard } from '@ant-design/pro-components';
import { Button, Table, Tag, Space, Typography, Empty, message, Popconfirm } from 'antd';
import {
  HistoryOutlined, EyeOutlined, CompassOutlined, ReloadOutlined, DeleteOutlined,
} from '@ant-design/icons';
import { history } from '@umijs/max';
import {
  listExplorationSessionsUsingGET,
  deleteExplorationSessionUsingDELETE,
} from '@/services/yubi/autoEdaController';
import type { ExplorationSessionItem } from '@/services/yubi/autoEdaController';
import './index.less';

const { Text } = Typography;

const statusTag = (status?: string) => {
  switch (status) {
    case 'COMPLETED':
      return <Tag color="success">已完成</Tag>;
    case 'RUNNING':
      return <Tag color="processing">进行中</Tag>;
    case 'FAILED':
      return <Tag color="error">失败</Tag>;
    case 'STOPPED':
      return <Tag color="default">已停止</Tag>;
    default:
      return <Tag>{status || '未知'}</Tag>;
  }
};

const formatTime = (t?: string | Date) => {
  if (!t) return '-';
  const d = typeof t === 'string' ? new Date(t) : t;
  if (Number.isNaN(d.getTime())) return String(t);
  return d.toLocaleString('zh-CN', { hour12: false });
};

const ExplorationHistoryPage: React.FC = () => {
  const [loading, setLoading] = useState(true);
  const [data, setData] = useState<ExplorationSessionItem[]>([]);
  const [deletingId, setDeletingId] = useState<string | null>(null);

  const load = async () => {
    setLoading(true);
    try {
      const res = await listExplorationSessionsUsingGET();
      setData(res?.data ?? []);
    } catch {
      message.error('加载探索记录失败');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    load();
  }, []);

  const handleDelete = async (sessionId: string) => {
    setDeletingId(sessionId);
    try {
      const res = await deleteExplorationSessionUsingDELETE(sessionId);
      if (res?.data) {
        message.success('已删除该探索记录');
        setData((prev) => prev.filter((item) => item.sessionId !== sessionId));
      }
    } catch {
      message.error('删除失败，请稍后重试');
    } finally {
      setDeletingId(null);
    }
  };

  const columns = [
    {
      title: '分析图表',
      dataIndex: 'chartName',
      key: 'chartName',
      ellipsis: true,
      render: (name: string, row: ExplorationSessionItem) => (
        <Space direction="vertical" size={0}>
          <Text strong>{name || `图表 #${row.chartId}`}</Text>
          <Text type="secondary" style={{ fontSize: 12 }}>编号 {row.sessionId}</Text>
        </Space>
      ),
    },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      width: 100,
      render: (s: string) => statusTag(s),
    },
    {
      title: '查询次数',
      dataIndex: 'totalQueries',
      key: 'totalQueries',
      width: 100,
      render: (n: number) => (n != null ? `${n} 次` : '-'),
    },
    {
      title: '开始时间',
      dataIndex: 'createTime',
      key: 'createTime',
      width: 180,
      render: (t: string) => formatTime(t),
    },
    {
      title: '结束时间',
      dataIndex: 'endTime',
      key: 'endTime',
      width: 180,
      render: (t: string) => formatTime(t),
    },
    {
      title: '操作',
      key: 'action',
      width: 260,
      fixed: 'right' as const,
      render: (_: unknown, row: ExplorationSessionItem) => (
        <Space wrap size={0}>
          {row.status === 'COMPLETED' && row.sessionId && (
            <Button
              type="link"
              size="small"
              icon={<EyeOutlined />}
              onClick={() => history.push(`/lineage/report/${row.sessionId}`)}
            >
              查看报告
            </Button>
          )}
          {row.status === 'RUNNING' && row.sessionId && (
            <Button
              type="link"
              size="small"
              icon={<CompassOutlined />}
              onClick={() => history.push('/lineage/explore')}
            >
              继续查看
            </Button>
          )}
          {(row.status === 'FAILED' || row.status === 'STOPPED') && (
            <Button type="link" size="small" onClick={() => history.push('/lineage/explore')}>
              重新探索
            </Button>
          )}
          {row.sessionId && (
            <Popconfirm
              title="确定删除这条探索记录？"
              description="删除后无法恢复，关联的报告与查询明细将一并移除。"
              okText="删除"
              cancelText="取消"
              okButtonProps={{ danger: true }}
              onConfirm={() => handleDelete(row.sessionId!)}
            >
              <Button
                type="link"
                size="small"
                danger
                icon={<DeleteOutlined />}
                loading={deletingId === row.sessionId}
              >
                删除
              </Button>
            </Popconfirm>
          )}
        </Space>
      ),
    },
  ];

  return (
    <PageContainer
      header={{
        title: '探索记录',
        subTitle: '查看历次智能探索的分析报告，随时回看结论与依据',
      }}
      extra={[
        <Button key="refresh" icon={<ReloadOutlined />} onClick={load}>
          刷新
        </Button>,
        <Button key="new" type="primary" icon={<CompassOutlined />} onClick={() => history.push('/lineage/explore')}>
          新建探索
        </Button>,
      ]}
    >
      <ProCard bordered>
        {data.length === 0 && !loading ? (
          <Empty
            image={Empty.PRESENTED_IMAGE_SIMPLE}
            description="还没有探索记录，先去「智能探索」发起一次分析吧"
          >
            <Button type="primary" onClick={() => history.push('/lineage/explore')}>
              去智能探索
            </Button>
          </Empty>
        ) : (
          <Table
            rowKey="sessionId"
            loading={loading}
            columns={columns}
            dataSource={data}
            scroll={{ x: 960 }}
            pagination={{ pageSize: 10, showTotal: (t) => `共 ${t} 条` }}
          />
        )}
      </ProCard>
    </PageContainer>
  );
};

export default ExplorationHistoryPage;
