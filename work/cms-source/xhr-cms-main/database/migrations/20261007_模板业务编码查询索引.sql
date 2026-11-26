-- MySQL 8：既有环境增量索引。执行前备份并核对 SHOW INDEX，重复执行前移除已存在的语句。
-- 不执行初始化脚本，不修改业务数据；由运维审核后在合适的维护窗口手动执行。
-- bizcode 单条/IN 查询及内容、子栏目查询的父栏目定位。
ALTER TABLE t_oneshot
    ADD INDEX idx_oneshot_site_biz_front
        (site_project_id, biz_code, deleted, is_system, has_front_channel, sort, id),
    ADD INDEX idx_oneshot_parent_front
        (parent_id, is_loop, deleted, is_system, has_front_channel, sort, id);

-- 等值条件在前，排序列在后；有效期仍作为残余条件过滤，避免范围条件打断排序前缀。
-- sort/create_time/id 均为 DESC，MySQL 可反向扫描此升序联合索引。
ALTER TABLE t_contents
    ADD INDEX idx_contents_class_public_sort
        (class_id, status, deleted, sort, create_time, id);

-- 执行后使用真实站点/栏目参数 EXPLAIN ANALYZE 核对命中索引、扫描行数、排序及耗时。
-- IN 多编码、多父栏目查询仍可能需要排序；不要据索引存在就认定消除了 filesort。
-- 回退（仅需要时手动执行）：
-- ALTER TABLE t_oneshot DROP INDEX idx_oneshot_site_biz_front, DROP INDEX idx_oneshot_parent_front;
-- ALTER TABLE t_contents DROP INDEX idx_contents_class_public_sort;
