import {
  importRestaurantCostData,
  listRestaurantImportLogs,
  rollbackRestaurantLatestImport,
  importRestaurantStaffData,
} from '@/services/yubi/restaurantController';
import { DownloadOutlined, UploadOutlined } from '@ant-design/icons';
import { Button, Card, message, Popconfirm, Select, Space, Table, Tag, Typography, Upload } from 'antd';
import type { UploadProps } from 'antd';
import React, { useEffect, useState } from 'react';

const COST_TEMPLATE = `date,storeId,storeCode,storeName,region,city,ingredientCost,packagingCost,energyCost,laborCost,wasteAmount,wasteRate,inventoryLossRate,platformCommission
2026-04-01,1,PZH-E01,嗨抢量贩零食·攀枝花东区万达广场店,东区,攀枝花市,19200,1100,650,5400,580,0.032,0.018,2200
`;

const STAFF_TEMPLATE = `date,storeId,storeCode,storeName,region,city,shiftType,plannedStaff,actualStaff,workHours,overtimeHours,absentCount,ordersCount,revenue,avgPrepTimeMin
2026-04-01,1,PZH-E01,嗨抢量贩零食·攀枝花东区万达广场店,东区,攀枝花市,lunch,10,10,38.0,0.8,0,420,42000,11.5
`;

const DataImportCenter: React.FC = () => {
  const [loadingCost, setLoadingCost] = useState(false);
  const [loadingStaff, setLoadingStaff] = useState(false);
  const [rollingCost, setRollingCost] = useState(false);
  const [rollingStaff, setRollingStaff] = useState(false);
  const [importMode, setImportMode] = useState<'overwrite' | 'replace_range'>('overwrite');
  const [logs, setLogs] = useState<any[]>([]);

  const loadLogs = async () => {
    try {
      const res = await listRestaurantImportLogs();
      setLogs((res?.data as any)?.records || []);
    } catch (e) {
      // ignore
    }
  };

  useEffect(() => {
    loadLogs();
  }, []);

  const rollbackLatest = async (importType: 'cost' | 'staff', logId?: number) => {
    if (importType === 'cost') {
      setRollingCost(true);
    } else {
      setRollingStaff(true);
    }
    try {
      const res = await rollbackRestaurantLatestImport(importType, logId);
      if (res?.code === 0) {
        message.success(`回滚成功，删除 ${res?.data?.rolledBack ?? 0} 行`);
        loadLogs();
      } else {
        message.error(res?.message || '回滚失败');
      }
    } catch (e: any) {
      message.error(`回滚失败：${e?.message || String(e)}`);
    } finally {
      if (importType === 'cost') {
        setRollingCost(false);
      } else {
        setRollingStaff(false);
      }
    }
  };

  const downloadTemplate = (filename: string, content: string) => {
    const blob = new Blob([content], { type: 'text/csv;charset=utf-8;' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    a.click();
    URL.revokeObjectURL(url);
  };

  const readFileAsText = (file: File): Promise<string> =>
    new Promise((resolve, reject) => {
      const reader = new FileReader();
      reader.onload = () => resolve(String(reader.result || ''));
      reader.onerror = () => reject(new Error('文件读取失败'));
      reader.readAsText(file, 'utf-8');
    });

  const readFileAsArrayBuffer = (file: File): Promise<ArrayBuffer> =>
    new Promise((resolve, reject) => {
      const reader = new FileReader();
      reader.onload = () => resolve(reader.result as ArrayBuffer);
      reader.onerror = () => reject(new Error('Excel 文件读取失败'));
      reader.readAsArrayBuffer(file);
    });

  const readDataAsCsv = async (file: File): Promise<string> => {
    const filename = (file.name || '').toLowerCase();
    if (filename.endsWith('.csv')) {
      return readFileAsText(file);
    }
    if (filename.endsWith('.xlsx') || filename.endsWith('.xls')) {
      const XLSX = await import('xlsx');
      const buffer = await readFileAsArrayBuffer(file);
      const workbook = XLSX.read(buffer, { type: 'array' });
      const firstSheetName = workbook.SheetNames[0];
      if (!firstSheetName) {
        throw new Error('Excel 无有效工作表');
      }
      const worksheet = workbook.Sheets[firstSheetName];
      return XLSX.utils.sheet_to_csv(worksheet);
    }
    throw new Error('仅支持 CSV / XLSX / XLS 文件');
  };

  const costUploadProps: UploadProps = {
    accept: '.csv,.xlsx,.xls',
    maxCount: 1,
    showUploadList: false,
    beforeUpload: async (file) => {
      if (loadingCost) {
        return Upload.LIST_IGNORE;
      }
      setLoadingCost(true);
      try {
        const csvText = await readDataAsCsv(file as File);
        const res = await importRestaurantCostData(csvText, importMode);
        if (res?.code === 0) {
          const details = (res?.data?.invalidStoreDetails || [])
            .map((i: any) => `第${i.rowNumber}行:${i.reason}`)
            .join('；');
          message.success(
            `成本数据导入成功，入库 ${res?.data?.upsert ?? 0} 行，无效门店行 ${res?.data?.invalidStoreRows ?? 0} 行${details ? `（${details}）` : ''}`,
          );
          loadLogs();
        } else {
          message.error(res?.message || '成本数据导入失败');
        }
      } catch (e: any) {
        message.error(`成本数据导入失败：${e?.message || String(e)}`);
      } finally {
        setLoadingCost(false);
      }
      return Upload.LIST_IGNORE;
    },
  };

  const staffUploadProps: UploadProps = {
    accept: '.csv,.xlsx,.xls',
    maxCount: 1,
    showUploadList: false,
    beforeUpload: async (file) => {
      if (loadingStaff) {
        return Upload.LIST_IGNORE;
      }
      setLoadingStaff(true);
      try {
        const csvText = await readDataAsCsv(file as File);
        const res = await importRestaurantStaffData(csvText, importMode);
        if (res?.code === 0) {
          const details = (res?.data?.invalidStoreDetails || [])
            .map((i: any) => `第${i.rowNumber}行:${i.reason}`)
            .join('；');
          message.success(
            `排班数据导入成功，入库 ${res?.data?.upsert ?? 0} 行，无效门店行 ${res?.data?.invalidStoreRows ?? 0} 行${details ? `（${details}）` : ''}`,
          );
          loadLogs();
        } else {
          message.error(res?.message || '排班数据导入失败');
        }
      } catch (e: any) {
        message.error(`排班数据导入失败：${e?.message || String(e)}`);
      } finally {
        setLoadingStaff(false);
      }
      return Upload.LIST_IGNORE;
    },
  };

  return (
    <div>
      <Typography.Title level={4}>成本与排班导入</Typography.Title>
      <Typography.Paragraph type="secondary">
        每月由运营或财务导出成本、排班 CSV 后上传，系统会自动入库并用于餐饮分析大屏。
      </Typography.Paragraph>
      <Space style={{ marginBottom: 16 }}>
        <Typography.Text>导入策略：</Typography.Text>
        <Select
          style={{ width: 220 }}
          value={importMode}
          options={[
            { label: '覆盖模式（同主键更新）', value: 'overwrite' },
            { label: '清空后重导（按文件日期范围）', value: 'replace_range' },
          ]}
          onChange={(value) => setImportMode(value)}
        />
      </Space>

      <Space direction="vertical" size={16} style={{ width: '100%' }}>
        <Card title="成本数据导入">
          <Space wrap>
            <Button
              icon={<DownloadOutlined />}
              onClick={() => downloadTemplate('stores_cost_template.csv', COST_TEMPLATE)}
            >
              下载成本模板
            </Button>
            <Upload {...costUploadProps}>
              <Button type="primary" loading={loadingCost} icon={<UploadOutlined />}>
                上传成本文件（CSV/Excel）
              </Button>
            </Upload>
            <Popconfirm
              title="确认回滚最近一批成本导入吗？"
              description="将删除该批次覆盖范围内的数据"
              onConfirm={() => rollbackLatest('cost')}
            >
              <Button danger loading={rollingCost}>回滚上一批</Button>
            </Popconfirm>
          </Space>
        </Card>

        <Card title="排班数据导入">
          <Space wrap>
            <Button
              icon={<DownloadOutlined />}
              onClick={() => downloadTemplate('stores_staff_template.csv', STAFF_TEMPLATE)}
            >
              下载排班模板
            </Button>
            <Upload {...staffUploadProps}>
              <Button type="primary" loading={loadingStaff} icon={<UploadOutlined />}>
                上传排班文件（CSV/Excel）
              </Button>
            </Upload>
            <Popconfirm
              title="确认回滚最近一批排班导入吗？"
              description="将删除该批次覆盖范围内的数据"
              onConfirm={() => rollbackLatest('staff')}
            >
              <Button danger loading={rollingStaff}>回滚上一批</Button>
            </Popconfirm>
          </Space>
        </Card>

        <Card title="最近导入记录">
          <Table
            rowKey="id"
            size="small"
            pagination={false}
            dataSource={logs}
            columns={[
              { title: '时间', dataIndex: 'createTime', width: 170 },
              { title: '类型', dataIndex: 'importType', width: 80 },
              {
                title: '策略',
                dataIndex: 'importMode',
                width: 170,
                render: (v: string) => (v === 'replace_range' ? '清空后重导' : '覆盖模式'),
              },
              { title: '总行', dataIndex: 'totalRows', width: 80 },
              { title: '成功', dataIndex: 'successRows', width: 80 },
              { title: '无效行', dataIndex: 'invalidRows', width: 90 },
              {
                title: '状态',
                dataIndex: 'status',
                width: 80,
                render: (v: string) => <Tag color={v === 'ok' ? 'green' : v === 'rolled_back' ? 'orange' : 'red'}>{v}</Tag>,
              },
              {
                title: '范围',
                key: 'range',
                render: (_: any, record: any) => `${record?.startDate || '-'} ~ ${record?.endDate || '-'}`,
              },
              {
                title: '操作',
                key: 'action',
                width: 90,
                render: (_: any, record: any) => (
                  <Popconfirm
                    title="确认回滚该批次吗？"
                    description="将删除该批次覆盖范围内的数据"
                    disabled={record?.status === 'rolled_back'}
                    onConfirm={() => rollbackLatest(record?.importType, Number(record?.id))}
                  >
                    <Button type="link" disabled={record?.status === 'rolled_back'}>
                      回滚
                    </Button>
                  </Popconfirm>
                ),
              },
            ]}
          />
        </Card>
      </Space>
    </div>
  );
};

export default DataImportCenter;
