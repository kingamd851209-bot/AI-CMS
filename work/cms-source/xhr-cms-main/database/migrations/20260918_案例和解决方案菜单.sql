INSERT INTO `t_menu` (`id`, `parent_id`, `menu_key`, `title`, `path`, `icon`, `category`, `sort_order`, `visible`, `enabled`, `requires_system_admin`, `component_path`, `create_time`, `update_time`, `deleted`) VALUES (907, 9, 'cms-platform-solution-config', '解决方案配置', '/admin/v2/cms-platform/solution-config', 'Management', '内容中台', 903, 1, 1, 0, NULL, '2026-09-15 11:40:44', '2026-09-15 11:42:39', 0);
INSERT INTO `t_menu` (`id`, `parent_id`, `menu_key`, `title`, `path`, `icon`, `category`, `sort_order`, `visible`, `enabled`, `requires_system_admin`, `component_path`, `create_time`, `update_time`, `deleted`) VALUES (908, 9, 'cms-platform-solution-center', '解决方案', '/admin/v2/cms-platform/solution-center', 'Tickets', '内容中台', 908, 1, 1, 0, NULL, '2026-09-15 11:40:44', '2026-09-15 11:40:44', 0);
INSERT INTO `t_menu` (`id`, `parent_id`, `menu_key`, `title`, `path`, `icon`, `category`, `sort_order`, `visible`, `enabled`, `requires_system_admin`, `component_path`, `create_time`, `update_time`, `deleted`) VALUES (909, 9, 'cms-platform-case-config', '案例配置', '/admin/v2/cms-platform/case-config', 'Collection', '内容中台', 904, 1, 1, 0, NULL, '2026-09-16 18:02:57', '2026-09-16 18:02:57', 0);
INSERT INTO `t_menu` (`id`, `parent_id`, `menu_key`, `title`, `path`, `icon`, `category`, `sort_order`, `visible`, `enabled`, `requires_system_admin`, `component_path`, `create_time`, `update_time`, `deleted`) VALUES (910, 9, 'cms-platform-case-center', '案例中心', '/admin/v2/cms-platform/case-center', 'Files', '内容中台', 910, 1, 1, 0, NULL, '2026-09-16 18:02:57', '2026-09-16 18:02:57', 0);


UPDATE `t_menu` SET `parent_id` = 9, `menu_key` = 'cms-platform-product-center', `title` = '产品中心', `path` = '/admin/v2/cms-platform/product-center', `icon` = 'GoodsFilled', `category` = '内容中台', `sort_order` = 906, `visible` = 1, `enabled` = 1, `requires_system_admin` = 0, `component_path` = NULL, `create_time` = '2026-08-27 09:04:34', `update_time` = '2026-09-15 11:43:03', `deleted` = 0 WHERE `id` = 903;
UPDATE `t_menu` SET `parent_id` = 9, `menu_key` = 'cms-platform-news-center', `title` = '新闻中心', `path` = '/admin/v2/cms-platform/news-center', `icon` = 'Newspaper', `category` = '内容中台', `sort_order` = 907, `visible` = 1, `enabled` = 1, `requires_system_admin` = 0, `component_path` = NULL, `create_time` = '2026-08-27 09:04:34', `update_time` = '2026-09-15 11:43:05', `deleted` = 0 WHERE `id` = 904;


ALTER TABLE `t_usergroups`
    ADD COLUMN `platform_setting` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL
      COMMENT '数据中台类别权限（逗号分隔键 类型:类别id，如 product:12,news:3）' AFTER `menu_setting`,
    ADD COLUMN `platform_other_setting` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL
      COMMENT '数据中台操作权限（分号分段，与 platform_setting 逐位对齐；段内逗号分隔操作id：1新增/2编辑/3删除/4查看/5存为草稿/6存为发布/7提交审核/8审批通过/9置顶/10推荐/11一键分发）' AFTER `platform_setting`;


CREATE TABLE `cms_platform_push_record` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `source_content_id` int NOT NULL COMMENT '中台数据ID（源内容ID）',
  `content_type` varchar(32) NOT NULL COMMENT '数据类型 product/news/form/solution',
  `site_id` bigint DEFAULT NULL COMMENT '目标站点ID',
  `column_id` int DEFAULT NULL COMMENT '目标站点栏目ID',
  `target_content_id` int DEFAULT NULL COMMENT '推送生成的目标内容ID',
  `field_mapping` text COMMENT '字段映射关系JSON [{productField,siteField}]',
  `field_content` longtext COMMENT '推送的源字段内容JSON',
  `push_config_id` bigint DEFAULT NULL COMMENT '推送配置ID',
  `push_status` varchar(20) NOT NULL DEFAULT 'SUCCESS' COMMENT '推送状态 SUCCESS/FAILED',
  `target_status` int DEFAULT NULL COMMENT '推送目标状态 0草稿 1待审 2已审核 3已发布',
  `push_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '推送时间',
  `push_user_id` bigint DEFAULT NULL COMMENT '推送人ID',
  `push_user` varchar(100) DEFAULT NULL COMMENT '推送人',
  `error_message` varchar(1000) DEFAULT NULL COMMENT '失败原因',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除 0否 1是',
  `push_type` varchar(20) NOT NULL DEFAULT 'INSERT' COMMENT '推送类型 INSERT新增 UPDATE更新',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_push_record_source` (`content_type`,`source_content_id`) USING BTREE,
  KEY `idx_push_record_config` (`push_config_id`) USING BTREE,
  KEY `idx_push_record_site` (`site_id`,`column_id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=7 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='内容中台推送记录';


CREATE TABLE `cms_ai_ui_package_upload_record` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `site_id` bigint NOT NULL COMMENT '站点ID(cms_ai_site_project.id)',
  `project_code` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '站点编码',
  `version` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'UIPackage 版本标识',
  `package_name` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '上传的ZIP文件名',
  `file_size` bigint DEFAULT NULL COMMENT 'ZIP文件字节数',
  `upload_type` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'UPLOAD' COMMENT '上传类型 UPLOAD首次上传 UPGRADE升级覆盖',
  `upload_user_id` bigint DEFAULT NULL COMMENT '上传人ID',
  `upload_user` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '上传人',
  `upload_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除 0否 1是',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_ui_pkg_upload_site` (`site_id`,`upload_time`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=4 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='UIPackage 上传记录';


-- 删除旧的域名唯一索引（若存在）
SET @has_domain_uk := (
  SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'cms_ai_site_project'
    AND INDEX_NAME = 'uk_cms_ai_site_project_active_domain'
);
SET @drop_domain_uk := IF(@has_domain_uk > 0,
  'ALTER TABLE `cms_ai_site_project` DROP INDEX `uk_cms_ai_site_project_active_domain`',
  'SELECT 1');
PREPARE stmt_drop_domain_uk FROM @drop_domain_uk;
EXECUTE stmt_drop_domain_uk;
DEALLOCATE PREPARE stmt_drop_domain_uk;

-- 补充普通索引（若不存在）
SET @has_domain_idx := (
  SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'cms_ai_site_project'
    AND INDEX_NAME = 'idx_cms_ai_site_project_active_domain'
);
SET @add_domain_idx := IF(@has_domain_idx = 0,
  'ALTER TABLE `cms_ai_site_project` ADD INDEX `idx_cms_ai_site_project_active_domain` (`active_site_domain` ASC)',
  'SELECT 1');
PREPARE stmt_add_domain_idx FROM @add_domain_idx;
EXECUTE stmt_add_domain_idx;
DEALLOCATE PREPARE stmt_add_domain_idx;


