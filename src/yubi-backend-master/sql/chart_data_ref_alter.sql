-- 图表表增加「外置 CSV 引用」列（与 Chart.java、application.yml 中 storage.chartData 说明一致）
-- 若未执行本脚本，实体已包含 chartDataRef 时，列表/详情等所有 SELECT chart 的接口可能报错：
--   Unknown column 'chartDataRef' in 'field list'
--
-- 在已存在库上执行一次即可（列已存在会报错 1060，可忽略）。

USE yubi;

ALTER TABLE chart
    ADD COLUMN chartDataRef text NULL COMMENT '图表原始数据外置对象引用 JSON（storage.chartData 开启时使用）' AFTER chartData;
