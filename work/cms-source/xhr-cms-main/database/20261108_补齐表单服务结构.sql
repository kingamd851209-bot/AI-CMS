-- 既有环境增量升级：先备份，逐项核对缺失列/表后执行，不用于重复升级。
-- 2026-11-08仅只读核对本地cms_workbench存在这些缺失；未验证其他环境。
-- DDL会隐式提交；本文件不含DROP、不清空旧表、不启用旧分类公开提交。
ALTER TABLE t_from_oneshot
  ADD COLUMN form_captcha TINYINT NOT NULL DEFAULT 0 COMMENT '表单提交验证码：1开启0关闭';
ALTER TABLE t_from_contents
  ADD COLUMN query_token VARCHAR(32) NULL DEFAULT NULL COMMENT '随机提交回查凭证，旧记录为空';
CREATE TABLE cms_form_service_key (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  site_id BIGINT NOT NULL,
  api_key VARCHAR(32) NOT NULL,
  enabled TINYINT NOT NULL DEFAULT 0,
  remark VARCHAR(255) NULL DEFAULT NULL,
  rotate_time DATETIME NULL DEFAULT NULL,
  create_time DATETIME NULL DEFAULT NULL,
  update_time DATETIME NULL DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_form_service_site (site_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
