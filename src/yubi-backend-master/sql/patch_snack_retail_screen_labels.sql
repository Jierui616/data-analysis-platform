-- 量贩零食连锁大屏：将历史「餐饮」预警规则名、示例文案更新为零售语义（指标编码不变）
-- 在已有库上执行一次即可；新库直接执行 restaurant_schema.sql 已含新文案则不必执行
SET NAMES utf8mb4;

-- 拆成单条等值条件，避免 Windows 客户端 GBK 与表 utf8mb4 混用导致 IN 比较排序规则冲突
UPDATE alert_rule
SET ruleName = '店效周转偏低预警', updateTime = CURRENT_TIMESTAMP
WHERE metricCode = 'tableTurnover' AND isDelete = 0 AND ruleName = '翻台率过低预警';

UPDATE alert_rule
SET ruleName = '拣货与收银响应偏慢预警', updateTime = CURRENT_TIMESTAMP
WHERE metricCode = 'prepTimeAvgMin' AND isDelete = 0 AND ruleName = '出餐时长过高预警';

-- 可选：修正示例告警文案中的餐饮用语
UPDATE alert_event
SET message = REPLACE(REPLACE(REPLACE(message, '翻台率', '店效周转'), '出餐时长', '拣货与收银响应时长'), '出餐', '拣货/收银响应')
WHERE message LIKE '%翻台%' OR message LIKE '%出餐%';
