import RiskPortraitNavBar from '@/components/RiskPortraitNavBar';
import { runRiskDecision, type RiskDecisionResult } from '@/services/yubi/riskPortraitController';
import { getRestaurantHqOverview } from '@/services/yubi/restaurantController';
import { history } from '@umijs/max';
import {
  AutoComplete,
  Button,
  Card,
  Checkbox,
  DatePicker,
  Descriptions,
  Form,
  Input,
  List,
  Select,
  Space,
  Typography,
  message,
} from 'antd';
import type { Dayjs } from 'dayjs';
import dayjs from 'dayjs';
import React, { useEffect, useState } from 'react';

const { Paragraph, Title } = Typography;

/** 下拉 value 与后端 scene 一致；展示用语为业务中文 */
const SCENES = [
  { value: 'store_credit', label: '门店铺货与授信（额度、账期）' },
  { value: 'coupon_control', label: '券与促销风控' },
  { value: 'trade_intercept', label: '交易异常拦截与复核' },
];

const sceneDisplayName = (code?: string) => {
  const m: Record<string, string> = {
    store_credit: '门店铺货与授信（额度、账期）',
    coupon_control: '券与促销风控',
    trade_intercept: '交易异常拦截与复核',
  };
  return (code && m[code]) || code || '—';
};

const RiskPortraitRules: React.FC = () => {
  const [form] = Form.useForm();
  const [loading, setLoading] = useState(false);
  const [result, setResult] = useState<RiskDecisionResult | null>(null);
  const [storeOptions, setStoreOptions] = useState<{ value: string; label: string }[]>([]);

  useEffect(() => {
    const loadStores = async () => {
      try {
        const end = dayjs();
        const start = end.subtract(6, 'day');
        const res = await getRestaurantHqOverview({
          startDate: start.format('YYYY-MM-DD'),
          endDate: end.format('YYYY-MM-DD'),
        });
        const rank = ((res?.data as any)?.storeRank || []) as any[];
        setStoreOptions(
          rank
            .filter((i) => i?.storeId != null && String(i.storeId).trim() !== '')
            .map((i) => ({
              value: String(i.storeId).trim(),
              label: `${i.storeName || `门店${i.storeId}`}（${i.storeId}）`,
            })),
        );
      } catch {
        setStoreOptions([]);
      }
    };
    loadStores();
  }, []);

  const onFinish = async (values: {
    scene: string;
    storeId?: string;
    dateRange?: [Dayjs, Dayjs];
    persist: boolean;
  }) => {
    setLoading(true);
    setResult(null);
    try {
      const body: Parameters<typeof runRiskDecision>[0] = {
        scene: values.scene,
        persist: values.persist !== false,
      };
      if (values.scene === 'store_credit') {
        body.subjectType = 'store';
        body.subjectId = values.storeId?.trim();
        body.storeId = values.storeId?.trim();
        if (values.dateRange?.[0] && values.dateRange?.[1]) {
          body.startDate = values.dateRange[0].format('YYYY-MM-DD');
          body.endDate = values.dateRange[1].format('YYYY-MM-DD');
        }
      } else {
        body.subjectType = 'member';
        body.subjectId = values.storeId?.trim() || 'ALL';
      }
      const data = await runRiskDecision(body);
      setResult(data);
      message.success(data.persisted ? '决策完成，已保存留痕' : '决策完成（本次未保存留痕）');
    } catch (e: any) {
      message.error(e?.message || '决策失败');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div style={{ padding: 24, maxWidth: 880 }}>
      <RiskPortraitNavBar />
      <Card>
        <Title level={5}>策略与规则</Title>
        <Paragraph>
          铺货额度 / 账期：高稳定且高可信 → 提额或延长账期；高波动或低可信 → 限额、缩短账期、加审批。
        </Paragraph>
        <Paragraph>
          券控：高风险限制大额券与叠加、限制跨店核销；高价值可提高券面并加到店 / 复购条件；匿名可采用阶梯券。
        </Paragraph>
        <Paragraph type="secondary" style={{ marginBottom: 16 }}>
          在下方选择决策场景并填写对象信息；系统将结合门店画像与经营日指标生成建议。门店类场景与「经营指挥大屏」口径一致。
        </Paragraph>

        <Form
          form={form}
          layout="vertical"
          initialValues={{ scene: 'store_credit', persist: true }}
          onFinish={onFinish}
        >
          <Form.Item name="scene" label="决策场景" rules={[{ required: true, message: '请选择决策场景' }]}>
            <Select options={SCENES} placeholder="请选择" />
          </Form.Item>
          <Form.Item noStyle shouldUpdate={(prev, cur) => prev.scene !== cur.scene}>
            {({ getFieldValue }) =>
              getFieldValue('scene') === 'store_credit' ? (
                <>
                  <Form.Item name="storeId" label="门店编号" rules={[{ required: true, message: '请选择或输入门店编号' }]}>
                    <AutoComplete
                      options={storeOptions}
                      placeholder="可输入编号，或从下拉选择（与经营指挥大屏门店排行同期）"
                      allowClear
                      filterOption={(inputValue, option) => {
                        const v = String((option as { value?: string })?.value ?? '');
                        const lab = String((option as { label?: string })?.label ?? '');
                        const q = inputValue.trim().toLowerCase();
                        if (!q) return true;
                        return v.toLowerCase().includes(q) || lab.toLowerCase().includes(q);
                      }}
                    />
                  </Form.Item>
                  <Form.Item name="dateRange" label="统计日期范围（可选）">
                    <DatePicker.RangePicker style={{ width: '100%' }} placeholder={['开始日期', '结束日期']} />
                  </Form.Item>
                </>
              ) : (
                <Form.Item name="storeId" label="对象编号（可选）">
                  <Input placeholder="不填则按「全部」参与策略试算" />
                </Form.Item>
              )
            }
          </Form.Item>
          <Form.Item name="persist" valuePropName="checked">
            <Checkbox>保存本次决策结果，便于在「命中与留痕」中查询与审计</Checkbox>
          </Form.Item>
          <Form.Item>
            <Space>
              <Button type="primary" htmlType="submit" loading={loading}>
                生成决策建议
              </Button>
              <Button onClick={() => history.push('/risk_portrait/audit')}>查看留痕列表</Button>
            </Space>
          </Form.Item>
        </Form>

        {result ? (
          <Card size="small" title="本次决策结果" style={{ marginTop: 16 }}>
            <Descriptions column={1} size="small" bordered>
              <Descriptions.Item label="决策场景">{sceneDisplayName(result.scene)}</Descriptions.Item>
              <Descriptions.Item label="风险等级">{result.riskLevel}</Descriptions.Item>
              <Descriptions.Item label="是否已留痕">{result.persisted ? '是' : '否'}</Descriptions.Item>
              {result.note ? <Descriptions.Item label="补充说明">{result.note}</Descriptions.Item> : null}
            </Descriptions>
            <Paragraph strong style={{ marginTop: 12 }}>
              命中要点
            </Paragraph>
            <List size="small" dataSource={result.hitRules || []} renderItem={(t) => <List.Item>{t}</List.Item>} />
            <Paragraph strong style={{ marginTop: 12 }}>
              建议动作
            </Paragraph>
            <List
              size="small"
              dataSource={result.recommendedActions || []}
              renderItem={(t) => <List.Item>{t}</List.Item>}
            />
          </Card>
        ) : null}
      </Card>
    </div>
  );
};

export default RiskPortraitRules;
