-- 执行前备份数据库；仅添加可空列，不改变既有字段、记录和栏目单页。
ALTER TABLE t_contents ADD COLUMN extension_fields JSON NULL COMMENT '新增业务字段值';
ALTER TABLE t_oneshot ADD COLUMN extension_fields JSON NULL COMMENT '栏目单页新增业务字段值';
