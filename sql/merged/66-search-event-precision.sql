-- 新版search服务启动前单独执行；只改变搜索库事件时间精度，不涉及64/65资金协议。
-- 先停止搜索消费者和读模型/索引重建，备份后在已选定的llmall-search库执行。
-- 不自动恢复历史已经丢失的毫秒；DDL耗时/锁影响依表大小评估。
USE `llmall-search`;
ALTER TABLE `shop_search_doc`
    MODIFY COLUMN `event_time` DATETIME(3) DEFAULT NULL COMMENT '最近一次事件时间，毫秒精度用于版本比较';
