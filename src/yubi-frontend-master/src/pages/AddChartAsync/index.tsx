import { genChartByAiAsyncUsingPOST } from '@/services/yubi/chartController';
import { UploadOutlined } from '@ant-design/icons';
import { Button, Card, Form, Input, message, Select, Space, Upload } from 'antd';
import { useForm } from 'antd/es/form/Form';
import TextArea from 'antd/es/input/TextArea';
import React, { useRef, useState } from 'react';

/**
 * 添加图表（异步）页面
 * @constructor
 */
const AddChartAsync: React.FC = () => {
  const [form] = useForm();
  const [submitting, setSubmitting] = useState<boolean>(false);
  const randomCaseIndexRef = useRef<number>(-1);

  const randomCases = [
    {
      goal: '分析某电商平台近 12 个月各品类的销售额、毛利率和退货率变化，找出高潜力品类与风险品类，并给出运营优化建议',
      name: '电商品类经营分析',
    },
    {
      goal: '分析某 SaaS 产品最近 6 个月新增用户、活跃用户、付费转化率与流失率趋势，定位增长瓶颈并提出增长方案',
      name: 'SaaS 用户增长分析',
    },
    {
      goal: '分析某门店连锁近 4 个季度营收、利润、库存周转与营销投入产出，识别利润下滑原因并给出改善动作',
      name: '门店连锁盈利诊断',
    },
  ];

  /**
   * 提交
   * @param values
   */
  const onFinish = async (values: any) => {
    // 避免重复提交
    if (submitting) {
      return;
    }
    setSubmitting(true);
    // 对接后端，上传数据
    const params = {
      ...values,
      file: undefined,
    };
    try {
      const res = (await genChartByAiAsyncUsingPOST(
        params,
        {},
        values.file.file.originFileObj,
      )) as API.BaseResponseBiResponse_;
      if (res.code !== 0 && res.code !== undefined) {
        message.error(res.message || '分析失败');
      } else if (!res?.data) {
        message.error('分析失败：无返回数据');
      } else {
        message.success('分析任务提交成功，稍后请在图表管理与结果回看页面查看');
        form.resetFields();
      }
    } catch (e: any) {
      message.error('分析失败：' + (e?.message || String(e)));
    }
    setSubmitting(false);
  };

  const handleRandomCase = () => {
    randomCaseIndexRef.current = (randomCaseIndexRef.current + 1) % randomCases.length;
    const nextCase = randomCases[randomCaseIndexRef.current];
    // 先清空再填充下一个案例，避免残留字段影响
    form.resetFields(['goal', 'name']);
    form.setFieldsValue({
      goal: nextCase.goal,
      name: nextCase.name,
    });
  };

  return (
    <div className="add-chart-async">
      <Card title="智能分析">
        <Form
          form={form}
          name="addChart"
          labelAlign="left"
          labelCol={{ span: 4 }}
          wrapperCol={{ span: 16 }}
          onFinish={onFinish}
          initialValues={{}}
        >
          <Form.Item
            name="goal"
            label="分析目标"
            rules={[{ required: true, message: '请输入分析目标' }]}
          >
            <TextArea placeholder="请输入你的分析需求，比如：分析网站用户的增长情况" />
          </Form.Item>
          <Form.Item name="name" label="图表名称">
            <Input placeholder="请输入图表名称" />
          </Form.Item>
          <Form.Item name="chartType" label="图表类型">
            <Select
              options={[
                { value: '折线图', label: '折线图' },
                { value: '柱状图', label: '柱状图' },
                { value: '堆叠图', label: '堆叠图' },
                { value: '饼图', label: '饼图' },
                { value: '雷达图', label: '雷达图' },
              ]}
            />
          </Form.Item>
          <Form.Item name="file" label="原始数据">
            <Upload name="file" maxCount={1}>
              <Button icon={<UploadOutlined />}>上传 CSV 文件</Button>
            </Upload>
          </Form.Item>

          <Form.Item wrapperCol={{ span: 16, offset: 4 }}>
            <Space>
              <Button type="primary" htmlType="submit" loading={submitting} disabled={submitting}>
                提交
              </Button>
              <Button htmlType="reset">重置</Button>
              <Button onClick={handleRandomCase} disabled={submitting}>
                随机案例
              </Button>
            </Space>
          </Form.Item>
        </Form>
      </Card>
    </div>
  );
};
export default AddChartAsync;
