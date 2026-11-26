/*
 Navicat Premium Dump SQL

 Source Server         : 81.70.95.231-13306
 Source Server Type    : MySQL
 Source Server Version : 80046 (8.0.46-0ubuntu0.22.04.4)
 Source Host           : 81.70.95.231:13306
 Source Schema         : bcsy

 Target Server Type    : MySQL
 Target Server Version : 80046 (8.0.46-0ubuntu0.22.04.4)
 File Encoding         : 65001

 Date: 14/09/2026 16:27:07
*/

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ----------------------------
-- Table structure for cms_ai_provider_config
-- ----------------------------
DROP TABLE IF EXISTS `cms_ai_provider_config`;
CREATE TABLE `cms_ai_provider_config`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `provider_code` varchar(64) NOT NULL,
  `provider_name` varchar(128) NULL DEFAULT NULL,
  `usage_type` varchar(64) NOT NULL,
  `model_name` varchar(128) NULL DEFAULT NULL,
  `base_url` varchar(512) NOT NULL,
  `api_key_cipher` text NULL,
  `api_key_nonce` varchar(128) NULL DEFAULT NULL,
  `enabled` tinyint NOT NULL DEFAULT 1,
  `timeout_ms` int NOT NULL DEFAULT 30000,
  `daily_budget_cents` int NULL DEFAULT NULL,
  `max_retries` int NOT NULL DEFAULT 2,
  `daily_token_budget` int NULL DEFAULT NULL,
  `monthly_cost_budget_cents` int NULL DEFAULT NULL,
  `prompt_version` varchar(128) NOT NULL DEFAULT 'cms-ai-default-v1',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
  `deleted` tinyint NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_ai_provider_code_usage_deleted`(`provider_code` ASC, `usage_type` ASC, `deleted` ASC) USING BTREE,
  INDEX `idx_ai_provider_usage_enabled`(`usage_type` ASC, `enabled` ASC, `deleted` ASC) USING BTREE,
  INDEX `idx_ai_provider_code_usage`(`provider_code` ASC, `usage_type` ASC, `deleted` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of cms_ai_provider_config
-- ----------------------------

-- ----------------------------
-- Table structure for cms_ai_site_project
-- ----------------------------
DROP TABLE IF EXISTS `cms_ai_site_project`;
CREATE TABLE `cms_ai_site_project`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `project_name` varchar(128) NOT NULL,
  `project_type` varchar(32) NOT NULL DEFAULT 'TEMPLATE_PREVIEW',
  `project_code` varchar(64) NOT NULL,
  `root_menu_id` int NULL DEFAULT NULL,
  `site_mode` varchar(32) NOT NULL DEFAULT 'CMS_MANAGED',
  `is_current` tinyint NOT NULL DEFAULT 0,
  `template_entry_path` varchar(512) NULL DEFAULT NULL,
  `template_package_name` varchar(255) NULL DEFAULT NULL,
  `template_package_status` varchar(32) NOT NULL DEFAULT 'NOT_UPLOADED',
  `sample_base_url` varchar(1024) NULL DEFAULT NULL,
  `site_domain` varchar(255) NULL DEFAULT NULL,
  `publish_directory` varchar(128) NULL DEFAULT NULL,
  `default_language` varchar(32) NOT NULL DEFAULT 'zh-CN',
  `site_description` varchar(512) NULL DEFAULT NULL,
  `current_batch_id` bigint NULL DEFAULT NULL,
  `last_recognition_batch_id` bigint NULL DEFAULT NULL,
  `status` varchar(32) NOT NULL DEFAULT 'DRAFT',
  `apply_status` varchar(32) NOT NULL DEFAULT 'NOT_APPLIED',
  `preview_status` varchar(32) NOT NULL DEFAULT 'NOT_READY',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
  `deleted` tinyint NOT NULL DEFAULT 0,
  `active_site_domain` varchar(255) GENERATED ALWAYS AS ((case when ((`deleted` = 0) and (`site_domain` is not null) and (trim(`site_domain`) <> _utf8mb4'')) then lower(trim(`site_domain`)) else NULL end)) STORED NULL,
  `traditional_template_package_name` varchar(255) NULL DEFAULT NULL COMMENT '传统模板包原始文件名',
  `traditional_template_package_version` varchar(128) NULL DEFAULT NULL COMMENT '传统模板包活动版本',
  `traditional_template_manifest` longtext NULL COMMENT '传统模板包活动 manifest',
  `traditional_template_root` varchar(1024) NULL DEFAULT NULL COMMENT '传统模板包服务端部署根目录',
  `ui_package_status` varchar(32) NOT NULL DEFAULT 'NOT_UPLOADED' COMMENT 'UIPackage 状态: NOT_UPLOADED, UPLOADED, RUNNING, STOPPED',
  `ui_package_name` varchar(255) NULL DEFAULT NULL COMMENT 'UIPackage ZIP 包文件名',
  `ui_package_version` varchar(100) NULL DEFAULT NULL COMMENT 'UIPackage 版本标识',
  `site_service_port` int NULL DEFAULT NULL COMMENT '站点服务端口 (8080-9000)',
  `site_service_status` varchar(20) NULL DEFAULT 'STOPPED' COMMENT '站点服务状态: STOPPED/RUNNING',
  `site_service_ip` varchar(255) NULL DEFAULT NULL COMMENT 'UI 静态资源域名/IP',
  `preview_access_enabled` tinyint(1) NOT NULL DEFAULT 0 COMMENT 'HTML页面是否需要访问密码',
  `preview_access_password` varchar(255) NULL DEFAULT NULL COMMENT '访问认证密码',
  `parent_id` bigint NULL DEFAULT NULL COMMENT '父级站点ID',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_ai_site_project_code`(`project_code` ASC, `deleted` ASC) USING BTREE,
  INDEX `idx_cms_ai_site_project_active_domain`(`active_site_domain` ASC) USING BTREE,
  UNIQUE INDEX `uk_cms_ai_site_project_active_endpoint`(`site_service_port` ASC, `active_site_domain` ASC) USING BTREE,
  INDEX `idx_ai_site_project_type_status`(`project_type` ASC, `status` ASC, `deleted` ASC) USING BTREE,
  INDEX `idx_ai_site_project_current_batch`(`current_batch_id` ASC, `deleted` ASC) USING BTREE,
  INDEX `idx_ai_site_project_root_menu`(`root_menu_id` ASC, `deleted` ASC) USING BTREE,
  INDEX `idx_ai_site_project_current`(`is_current` ASC, `deleted` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of cms_ai_site_project
-- ----------------------------

-- ----------------------------
-- Table structure for cms_language
-- ----------------------------
DROP TABLE IF EXISTS `cms_language`;
CREATE TABLE `cms_language`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `language_code` varchar(32) NOT NULL,
  `language_name` varchar(128) NOT NULL,
  `native_name` varchar(128) NULL DEFAULT NULL,
  `locale` varchar(32) NULL DEFAULT NULL,
  `is_default` tinyint NOT NULL DEFAULT 0,
  `enabled` tinyint NOT NULL DEFAULT 1,
  `sort` int NOT NULL DEFAULT 0,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
  `deleted` tinyint NOT NULL DEFAULT 0,
  `active_language_code` varchar(32) GENERATED ALWAYS AS ((case when (`deleted` = 0) then `language_code` else NULL end)) STORED NULL,
  `active_default_marker` tinyint GENERATED ALWAYS AS ((case when ((`deleted` = 0) and (`enabled` = 1) and (`is_default` = 1)) then 1 else NULL end)) STORED NULL,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_language_code_active`(`active_language_code` ASC) USING BTREE,
  INDEX `idx_language_enabled`(`enabled` ASC) USING BTREE,
  INDEX `idx_language_default`(`is_default` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of cms_language
-- ----------------------------
INSERT INTO `cms_language` VALUES (1, 'zh-CN', '简体中文', '简体中文', 'zh-CN', 0, 1, 0, '2026-07-18 11:56:00', NULL, 0, DEFAULT, DEFAULT);
INSERT INTO `cms_language` VALUES (2, 'en-US', 'English', 'English', 'en-US', 0, 1, 1, '2026-07-27 15:23:14', NULL, 0, DEFAULT, DEFAULT);
INSERT INTO `cms_language` VALUES (4, 'es-ES', '西班牙语', 'Español', 'es-ES', 0, 1, 2, '2026-08-27 16:10:20', NULL, 0, DEFAULT, DEFAULT);
INSERT INTO `cms_language` VALUES (5, 'pt-PT', '葡萄牙语', 'Português', 'pt-PT', 0, 1, 3, '2026-08-27 16:10:20', NULL, 0, DEFAULT, DEFAULT);
INSERT INTO `cms_language` VALUES (6, 'ru-RU', '俄语', 'Русский', 'ru-RU', 0, 1, 4, '2026-08-27 16:10:20', NULL, 0, DEFAULT, DEFAULT);
INSERT INTO `cms_language` VALUES (7, 'id-ID', '印度尼西亚语', 'Indonesia', 'id-ID', 0, 1, 5, '2026-08-27 16:10:20', NULL, 0, DEFAULT, DEFAULT);
INSERT INTO `cms_language` VALUES (8, 'fr-FR', '法语', 'Français', 'fr-FR', 0, 1, 6, '2026-08-27 16:10:20', NULL, 0, DEFAULT, DEFAULT);
INSERT INTO `cms_language` VALUES (9, 'de-DE', '德语', 'Deutsch', 'de-DE', 0, 1, 7, '2026-08-27 16:10:20', NULL, 0, DEFAULT, DEFAULT);
INSERT INTO `cms_language` VALUES (10, 'ar-SA', '阿拉伯语', 'ةيبرعلا', 'ar-SA', 0, 1, 8, '2026-08-27 16:10:20', NULL, 0, DEFAULT, DEFAULT);
INSERT INTO `cms_language` VALUES (11, 'th-TH', '泰语', 'ไทย', 'th-TH', 0, 1, 9, '2026-08-27 16:10:20', NULL, 0, DEFAULT, DEFAULT);
INSERT INTO `cms_language` VALUES (12, 'it-IT', '意大利语', 'Italiano', 'it-IT', 0, 1, 10, '2026-08-27 16:10:20', NULL, 0, DEFAULT, DEFAULT);
INSERT INTO `cms_language` VALUES (13, 'km-KH', '高棉语（柬埔寨语）', 'ភាសាខ្មែរ', 'km-KH', 0, 1, 11, '2026-08-27 16:10:20', NULL, 0, DEFAULT, DEFAULT);
INSERT INTO `cms_language` VALUES (14, 'ja-JP', '日语', '日本語', 'ja-JP', 0, 1, 12, '2026-08-27 16:10:20', NULL, 0, DEFAULT, DEFAULT);

-- ----------------------------
-- Table structure for cms_operation_task
-- ----------------------------
DROP TABLE IF EXISTS `cms_operation_task`;
CREATE TABLE `cms_operation_task`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `site_id` bigint NOT NULL DEFAULT 0,
  `task_type` varchar(64) NOT NULL,
  `idempotency_key` varchar(160) NOT NULL,
  `status` enum('QUEUED','RUNNING','SUCCEEDED','FAILED','CANCELLED') NOT NULL DEFAULT 'QUEUED',
  `progress` int NOT NULL DEFAULT 0,
  `owner_token` varchar(128) NULL DEFAULT NULL,
  `lease_until` datetime(6) NULL DEFAULT NULL,
  `fencing_token` bigint NOT NULL DEFAULT 0,
  `cancel_requested` tinyint NOT NULL DEFAULT 0,
  `retry_count` int NOT NULL DEFAULT 0,
  `business_route` varchar(255) NULL DEFAULT NULL,
  `input_summary` varchar(1000) NULL DEFAULT NULL,
  `command_json` longtext NULL,
  `result_summary` varchar(2000) NULL DEFAULT NULL,
  `result_json` longtext NULL,
  `failure_summary` varchar(2000) NULL DEFAULT NULL,
  `operator_id` bigint NULL DEFAULT NULL,
  `operator_ref` varchar(128) NOT NULL DEFAULT 'SYSTEM',
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `started_at` datetime(6) NULL DEFAULT NULL,
  `finished_at` datetime(6) NULL DEFAULT NULL,
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  `progress_stage` varchar(64) NULL DEFAULT NULL,
  `progress_total` int NOT NULL DEFAULT 0,
  `progress_completed` int NOT NULL DEFAULT 0,
  `progress_succeeded` int NOT NULL DEFAULT 0,
  `progress_skipped` int NOT NULL DEFAULT 0,
  `progress_failed` int NOT NULL DEFAULT 0,
  `progress_current_item` varchar(1000) NULL DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_cms_operation_task_idempotency`(`site_id` ASC, `task_type` ASC, `idempotency_key` ASC) USING BTREE,
  INDEX `idx_cms_operation_task_claim`(`status` ASC, `cancel_requested` ASC, `lease_until` ASC, `id` ASC) USING BTREE,
  INDEX `idx_cms_operation_task_site_status`(`site_id` ASC, `status` ASC, `updated_at` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of cms_operation_task
-- ----------------------------

-- ----------------------------
-- Table structure for cms_operation_task_attempt
-- ----------------------------
DROP TABLE IF EXISTS `cms_operation_task_attempt`;
CREATE TABLE `cms_operation_task_attempt`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `task_id` bigint NOT NULL,
  `attempt_no` int NOT NULL,
  `owner_token` varchar(128) NOT NULL,
  `fencing_token` bigint NOT NULL,
  `status` enum('RUNNING','SUCCEEDED','FAILED','CANCELLED','LEASE_LOST') NOT NULL,
  `failure_code` varchar(64) NULL DEFAULT NULL,
  `started_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `finished_at` datetime(6) NULL DEFAULT NULL,
  `failure_summary` varchar(2000) NULL DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_cms_operation_task_attempt`(`task_id` ASC, `attempt_no` ASC) USING BTREE,
  INDEX `idx_cms_operation_task_attempt_task`(`task_id` ASC, `id` ASC) USING BTREE,
  CONSTRAINT `fk_cms_operation_task_attempt_task` FOREIGN KEY (`task_id`) REFERENCES `cms_operation_task` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of cms_operation_task_attempt
-- ----------------------------

-- ----------------------------
-- Table structure for cms_platform_push_config
-- ----------------------------
DROP TABLE IF EXISTS `cms_platform_push_config`;
CREATE TABLE `cms_platform_push_config`  (
  `id` bigint UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `config_type` varchar(20) NOT NULL COMMENT '类型 product/news',
  `category_id` int NOT NULL COMMENT '内容中台分类ID',
  `site_id` bigint NOT NULL COMMENT '目标站点ID',
  `site_column_id` int NOT NULL COMMENT '目标站点栏目ID',
  `target_status` int NOT NULL DEFAULT 0 COMMENT '推送目标默认状态 0草稿 1待审 2已审核 3已发布',
  `field_mapping` mediumtext  NULL COMMENT '字段映射JSON [{productField,siteField}]',
  `deleted` int NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  `create_time` datetime NULL DEFAULT NULL COMMENT '创建时间',
  `update_time` datetime NULL DEFAULT NULL COMMENT '更新时间',
  `create_by` varchar(100) NULL DEFAULT NULL COMMENT '创建人',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_push_config_category`(`config_type` ASC, `category_id` ASC, `deleted` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of cms_platform_push_config
-- ----------------------------

-- ----------------------------
-- Table structure for cms_site_locale
-- ----------------------------
DROP TABLE IF EXISTS `cms_site_locale`;
CREATE TABLE `cms_site_locale`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `site_id` bigint NOT NULL,
  `language_id` bigint NOT NULL,
  `is_default` tinyint NOT NULL DEFAULT 0,
  `enabled` tinyint NOT NULL DEFAULT 1,
  `required_for_publish` tinyint NOT NULL DEFAULT 0,
  `path_prefix` varchar(64) NOT NULL DEFAULT '',
  `fallback_language_id` bigint NULL DEFAULT NULL,
  `sort` int NOT NULL DEFAULT 0,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
  `deleted` tinyint NOT NULL DEFAULT 0,
  `active_default_site_id` bigint GENERATED ALWAYS AS ((case when ((`deleted` = 0) and (`is_default` = 1)) then `site_id` else NULL end)) STORED NULL,
  `active_path_prefix` varchar(64) GENERATED ALWAYS AS ((case when (`deleted` = 0) then `path_prefix` else NULL end)) STORED NULL,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_cms_site_locale_site_language`(`site_id` ASC, `language_id` ASC) USING BTREE,
  UNIQUE INDEX `uk_cms_site_locale_default`(`active_default_site_id` ASC) USING BTREE,
  UNIQUE INDEX `uk_cms_site_locale_prefix`(`site_id` ASC, `active_path_prefix` ASC) USING BTREE,
  INDEX `idx_cms_site_locale_enabled`(`site_id` ASC, `enabled` ASC, `sort` ASC) USING BTREE,
  INDEX `idx_cms_site_locale_fallback`(`site_id` ASC, `fallback_language_id` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of cms_site_locale
-- ----------------------------

-- ----------------------------
-- Table structure for cms_site_settings
-- ----------------------------
DROP TABLE IF EXISTS `cms_site_settings`;
CREATE TABLE `cms_site_settings`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `site_id` bigint NULL DEFAULT NULL COMMENT '站点ID',
  `column_id` int NULL DEFAULT NULL COMMENT '栏目ID(t_oneshot.id)',
  `column_name` varchar(255) NULL DEFAULT NULL COMMENT '栏目名称',
  `parent_id` int NULL DEFAULT 0 COMMENT '父栏目ID',
  `kind` varchar(20) NULL DEFAULT NULL COMMENT 'list列表页/detail详情页',
  `page_url` varchar(500) NULL DEFAULT NULL COMMENT '主页/详情地址',
  `redirect_301` varchar(500) NULL DEFAULT NULL COMMENT '301配置页',
  `redirect_302` varchar(500) NULL DEFAULT NULL COMMENT '302配置页',
  `fallback_lang` varchar(500) NULL DEFAULT NULL COMMENT '多语言回退页',
  `cross_mapping_json` mediumtext NULL COMMENT '跨多站点栏目映射[{siteId,columnId}]',
  `deleted` int NOT NULL DEFAULT 0,
  `create_time` datetime NULL DEFAULT NULL,
  `update_time` datetime NULL DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_site_settings`(`site_id` ASC, `column_id` ASC, `kind` ASC, `deleted` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of cms_site_settings
-- ----------------------------

-- ----------------------------
-- Table structure for sensitive_word
-- ----------------------------
DROP TABLE IF EXISTS `sensitive_word`;
CREATE TABLE `sensitive_word`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `word` varchar(128) NOT NULL COMMENT '敏感词',
  `action` varchar(20) NULL DEFAULT 'reject' COMMENT '行动类型: reject-拒绝, alert-\r\n    告警, replace-替换, select_replace-选择替换',
  `replacement` varchar(128) NULL DEFAULT NULL COMMENT '替换词',
  `level` tinyint NOT NULL DEFAULT 1 COMMENT '敏感级别：1-低，2-中，3-高',
  `remark` varchar(512) NULL DEFAULT NULL COMMENT '备注',
  `scope` varchar(10) NOT NULL DEFAULT 'site' COMMENT '作用范围: global-全局, site-指定站点',
  `site_ids` varchar(1024) NULL DEFAULT NULL COMMENT '逗号分隔站点ID，scope=site 时有效',
  `status` tinyint NOT NULL DEFAULT 1 COMMENT '状态：0-禁用，1-启用',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `create_by` bigint NULL DEFAULT NULL COMMENT '创建人ID',
  `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '删除标记',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_sensitive_word_site`(`deleted` ASC) USING BTREE,
  INDEX `idx_sensitive_word_level`(`level` ASC, `deleted` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '敏感词管理表' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of sensitive_word
-- ----------------------------

-- ----------------------------
-- Table structure for seo_geo
-- ----------------------------
DROP TABLE IF EXISTS `seo_geo`;
CREATE TABLE `seo_geo`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `content` text NOT NULL COMMENT 'GEO内容',
  `site_id` bigint NOT NULL COMMENT '应用站点ID',
  `menu_id` int NULL DEFAULT NULL COMMENT '应用栏目ID',
  `content_id` int NULL DEFAULT NULL COMMENT '应用内容页ID',
  `status` tinyint NOT NULL DEFAULT 1 COMMENT '状态：0-禁用，1-启用',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `create_by` bigint NULL DEFAULT NULL COMMENT '创建人ID',
  `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '删除标记：0-未删除，1-已删除',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_seo_geo_site`(`site_id` ASC, `deleted` ASC) USING BTREE,
  INDEX `idx_seo_geo_menu`(`menu_id` ASC, `deleted` ASC) USING BTREE,
  INDEX `idx_seo_geo_content`(`content_id` ASC, `deleted` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = 'GEO管理表' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of seo_geo
-- ----------------------------

-- ----------------------------
-- Table structure for seo_hreflang
-- ----------------------------
DROP TABLE IF EXISTS `seo_hreflang`;
CREATE TABLE `seo_hreflang`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `hreflang` varchar(32) NOT NULL COMMENT '语言代码，如zh-CN,en-US',
  `href` varchar(512) NOT NULL COMMENT '链接地址',
  `site_id` bigint NOT NULL COMMENT '应用站点ID',
  `menu_id` int NULL DEFAULT NULL COMMENT '应用栏目ID',
  `content_id` int NULL DEFAULT NULL COMMENT '应用内容页ID',
  `status` tinyint NOT NULL DEFAULT 1 COMMENT '状态：0-禁用，1-启用',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `create_by` bigint NULL DEFAULT NULL COMMENT '创建人ID',
  `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '删除标记：0-未删除，1-已删除',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_seo_hreflang_site_lang`(`site_id` ASC, `hreflang` ASC, `menu_id` ASC, `content_id` ASC, `deleted` ASC) USING BTREE,
  INDEX `idx_seo_hreflang_site`(`site_id` ASC, `deleted` ASC) USING BTREE,
  INDEX `idx_seo_hreflang_menu`(`menu_id` ASC, `deleted` ASC) USING BTREE,
  INDEX `idx_seo_hreflang_content`(`content_id` ASC, `deleted` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = 'Hreflang管理表' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of seo_hreflang
-- ----------------------------

-- ----------------------------
-- Table structure for seo_meta
-- ----------------------------
DROP TABLE IF EXISTS `seo_meta`;
CREATE TABLE `seo_meta`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `seo_title` varchar(256) NULL DEFAULT NULL COMMENT 'SEO标题',
  `seo_keywords` varchar(512) NULL DEFAULT NULL COMMENT 'SEO关键词',
  `seo_description` varchar(1024) NULL DEFAULT NULL COMMENT 'SEO描述',
  `site_id` bigint NOT NULL COMMENT '应用站点ID',
  `menu_id` int NULL DEFAULT NULL COMMENT '应用栏目ID',
  `content_id` int NULL DEFAULT NULL COMMENT '应用内容页ID',
  `canonical_url` varchar(512) NULL DEFAULT NULL COMMENT '规范链接',
  `og_title` varchar(256) NULL DEFAULT NULL COMMENT 'Open Graph标题',
  `og_description` varchar(512) NULL DEFAULT NULL COMMENT 'Open Graph描述',
  `og_image` varchar(512) NULL DEFAULT NULL COMMENT 'Open Graph图片',
  `status` tinyint NOT NULL DEFAULT 1 COMMENT '状态：0-禁用，1-启用',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `create_by` bigint NULL DEFAULT NULL COMMENT '创建人ID',
  `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '删除标记：0-未删除，1-已删除',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_seo_meta_target`(`site_id` ASC, `menu_id` ASC, `content_id` ASC, `deleted` ASC) USING BTREE,
  INDEX `idx_seo_meta_site`(`site_id` ASC, `deleted` ASC) USING BTREE,
  INDEX `idx_seo_meta_menu`(`menu_id` ASC, `deleted` ASC) USING BTREE,
  INDEX `idx_seo_meta_content`(`content_id` ASC, `deleted` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = 'SEO管理表' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of seo_meta
-- ----------------------------

-- ----------------------------
-- Table structure for seo_robots
-- ----------------------------
DROP TABLE IF EXISTS `seo_robots`;
CREATE TABLE `seo_robots`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `content` text NOT NULL COMMENT 'Robots内容',
  `site_id` bigint NOT NULL COMMENT '应用站点ID',
  `status` tinyint NOT NULL DEFAULT 1 COMMENT '状态：0-禁用，1-启用',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `create_by` bigint NULL DEFAULT NULL COMMENT '创建人ID',
  `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '删除标记：0-未删除，1-已删除',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_seo_robots_site`(`site_id` ASC, `deleted` ASC) USING BTREE,
  INDEX `idx_seo_robots_status`(`status` ASC, `deleted` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = 'Robots管理表' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of seo_robots
-- ----------------------------

-- ----------------------------
-- Table structure for seo_sitemap
-- ----------------------------
DROP TABLE IF EXISTS `seo_sitemap`;
CREATE TABLE `seo_sitemap`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `content` text NOT NULL COMMENT 'SiteMap内容',
  `site_id` bigint NOT NULL COMMENT '应用站点ID',
  `sitemap_type` varchar(32) NOT NULL DEFAULT 'XML' COMMENT '类型：XML/TXT/HTML',
  `status` tinyint NOT NULL DEFAULT 1 COMMENT '状态：0-禁用，1-启用',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `create_by` bigint NULL DEFAULT NULL COMMENT '创建人ID',
  `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '删除标记：0-未删除，1-已删除',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_seo_sitemap_site`(`site_id` ASC, `deleted` ASC) USING BTREE,
  INDEX `idx_seo_sitemap_type`(`sitemap_type` ASC, `deleted` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = 'SiteMap管理表' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of seo_sitemap
-- ----------------------------

-- ----------------------------
-- Table structure for t_attach
-- ----------------------------
DROP TABLE IF EXISTS `t_attach`;
CREATE TABLE `t_attach`  (
  `id` int UNSIGNED NOT NULL,
  `fname` varchar(100) NOT NULL DEFAULT '',
  `ftype` varchar(50) NULL DEFAULT '',
  `fkey` mediumtext NOT NULL,
  `author_id` int NULL DEFAULT NULL,
  `created` int NOT NULL,
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of t_attach
-- ----------------------------

-- ----------------------------
-- Table structure for t_check_record
-- ----------------------------
DROP TABLE IF EXISTS `t_check_record`;
CREATE TABLE `t_check_record`  (
  `id` int NOT NULL AUTO_INCREMENT COMMENT 'ID',
  `content_id` int NULL DEFAULT 0 COMMENT '数据ID',
  `content_title` varchar(100) NULL DEFAULT NULL COMMENT '数据标题',
  `content_history_id` int NULL DEFAULT 0 COMMENT '数据版本ID',
  `submit_menu_id` int NULL DEFAULT 0 COMMENT '栏目ID',
  `submit_menu_name` varchar(50) NULL DEFAULT NULL COMMENT '栏目名称',
  `submit_user_id` int NULL DEFAULT 0 COMMENT '提交人ID',
  `submit_user_name` varchar(40) NULL DEFAULT NULL COMMENT '提交人名称',
  `submit_description` varchar(255) NULL DEFAULT NULL COMMENT '申请说明',
  `submit_time` datetime NULL DEFAULT NULL COMMENT '提交时间',
  `check_user_id` int NULL DEFAULT 0 COMMENT '审批人ID',
  `check_user_name` varchar(40) NULL DEFAULT NULL COMMENT '审批人名称',
  `check_status` tinyint NULL DEFAULT 0 COMMENT '审批状态，0待审批，1通过，2未通过，3作废',
  `check_description` varchar(255) NULL DEFAULT NULL COMMENT '审批备注',
  `check_time` datetime NULL DEFAULT NULL COMMENT '审批时间',
  `create_time` datetime NULL DEFAULT NULL COMMENT '创建时间',
  `del_flag` tinyint NULL DEFAULT 0 COMMENT '删除状态',
  `type` varchar(255) NULL DEFAULT NULL COMMENT '增加add、删除 del、修改update',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '审批记录' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of t_check_record
-- ----------------------------

-- ----------------------------
-- Table structure for t_comments
-- ----------------------------
DROP TABLE IF EXISTS `t_comments`;
CREATE TABLE `t_comments`  (
  `coid` int UNSIGNED NOT NULL AUTO_INCREMENT,
  `cid` int UNSIGNED NULL DEFAULT 0,
  `created` int UNSIGNED NULL DEFAULT 0,
  `author` varchar(200) NULL DEFAULT NULL,
  `author_id` int UNSIGNED NULL DEFAULT 0,
  `owner_id` int UNSIGNED NULL DEFAULT 0,
  `mail` varchar(200) NULL DEFAULT NULL,
  `url` varchar(200) NULL DEFAULT NULL,
  `ip` varchar(64) NULL DEFAULT NULL,
  `agent` varchar(200) NULL DEFAULT NULL,
  `content` mediumtext NULL,
  `type` varchar(16) NULL DEFAULT 'comment',
  `status` varchar(16) NULL DEFAULT 'approved',
  `parent` int UNSIGNED NULL DEFAULT 0,
  PRIMARY KEY (`coid`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of t_comments
-- ----------------------------

-- ----------------------------
-- Table structure for t_contents
-- ----------------------------
DROP TABLE IF EXISTS `t_member_credentials`;
DROP TABLE IF EXISTS `t_contents`;
CREATE TABLE `t_contents`  (
  `extension_fields` JSON NULL COMMENT '新增业务字段值',
  `id` int UNSIGNED NOT NULL AUTO_INCREMENT,
  `content_code` varchar(64) NULL DEFAULT NULL COMMENT '内容业务编码：新增内容时默认取自身id，多站点按(content_code,site_project_id)定位',
  `site_project_id` bigint NULL DEFAULT NULL COMMENT '站点项目ID：新增内容时记录所属站点',
  `class_id` int NULL DEFAULT 0 COMMENT '分类编号',
  `title` varchar(500) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '标题',
  `tags` varchar(200) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '标签',
  `hits` int UNSIGNED NOT NULL DEFAULT 0 COMMENT '访问次数',
  `sort` int NOT NULL DEFAULT 0 COMMENT '排序号',
  `status` tinyint(1) NOT NULL DEFAULT 0 COMMENT '发布状态',
  `single_line1` varchar(500) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '单行文本',
  `single_line2` varchar(500) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '单行文本',
  `single_line3` varchar(500) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '单行文本',
  `single_line4` varchar(500) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '单行文本',
  `single_line5` varchar(500) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '单行文本',
  `single_line6` varchar(500) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '单行文本',
  `single_line7` varchar(500) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '单行文本',
  `single_line8` varchar(500) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '单行文本',
  `single_line9` varchar(500) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '单行文本',
  `single_line10` varchar(500) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '单行文本',
  `single_line11` varchar(500) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '单行文本',
  `single_line12` varchar(500) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '单行文本',
  `single_line13` varchar(500) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '单行文本',
  `single_line14` varchar(500) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '单行文本',
  `single_line15` varchar(500) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '单行文本',
  `single_line16` varchar(500) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '单行文本',
  `single_line17` varchar(500) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '单行文本',
  `single_line18` varchar(500) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '单行文本',
  `single_line19` varchar(500) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '单行文本',
  `single_line20` varchar(500) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '单行文本',
  `xgid` int NOT NULL DEFAULT 0 COMMENT '关联数据ID',
  `note1` mediumtext CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL COMMENT '多行文本',
  `note2` mediumtext CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL COMMENT '多行文本',
  `note3` mediumtext CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL COMMENT '多行文本',
  `note4` mediumtext CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL COMMENT '多行文本',
  `note5` mediumtext CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL COMMENT '多行文本',
  `note6` mediumtext CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL COMMENT '多行文本',
  `note7` mediumtext CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL COMMENT '多行文本',
  `note8` mediumtext CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL COMMENT '多行文本',
  `note9` mediumtext CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL COMMENT '多行文本',
  `note10` mediumtext CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL COMMENT '多行文本',
  `note11` mediumtext CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL COMMENT '多行文本',
  `note12` mediumtext CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL COMMENT '多行文本',
  `note13` mediumtext CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL COMMENT '多行文本',
  `note14` mediumtext CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL COMMENT '多行文本',
  `note15` mediumtext CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL COMMENT '多行文本',
  `photo1` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '图片',
  `photo2` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '图片',
  `photo3` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '图片',
  `photo4` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '图片',
  `photo5` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '图片',
  `photo6` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '图片',
  `photo7` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '图片',
  `photo8` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '图片',
  `photo9` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '图片',
  `photo10` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '图片',
  `photo11` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '图片',
  `photo12` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '图片',
  `photo13` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '图片',
  `photo14` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '图片',
  `photo15` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '图片',
  `seo_title` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT 'seo标题',
  `seo_keywords` mediumtext CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL COMMENT 'seo关键字',
  `seo_description` mediumtext CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL COMMENT 'seo描述',
  `media1` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '视频',
  `media2` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '视频',
  `media3` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '视频',
  `media4` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '视频',
  `file1` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '视频',
  `file2` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '视频',
  `file3` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '视频',
  `file4` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '视频',
  `content1` longtext NULL,
  `content2` longtext NULL,
  `content3` longtext NULL,
  `content4` longtext NULL,
  `content5` longtext NULL,
  `content6` longtext NULL,
  `index_top` tinyint UNSIGNED NOT NULL DEFAULT 0 COMMENT '首页置顶',
  `index_elite` tinyint UNSIGNED NOT NULL DEFAULT 0 COMMENT '首页推荐',
  `index_hot` tinyint UNSIGNED NOT NULL DEFAULT 0 COMMENT '首页热门',
  `channel_top` tinyint UNSIGNED NOT NULL DEFAULT 0 COMMENT '频道置顶',
  `channel_elite` tinyint UNSIGNED NOT NULL DEFAULT 0 COMMENT '频道推荐',
  `channel_hot` tinyint UNSIGNED NOT NULL DEFAULT 0 COMMENT '频道热门',
  `menu_top` tinyint UNSIGNED NOT NULL DEFAULT 0 COMMENT '栏目置顶',
  `menu_elite` tinyint UNSIGNED NOT NULL DEFAULT 0 COMMENT '栏目推荐',
  `menu_hot` tinyint UNSIGNED NOT NULL DEFAULT 0 COMMENT '栏目热门',
  `nav_elite` tinyint NOT NULL DEFAULT 0 COMMENT '导航推荐',
  `right_elite` tinyint UNSIGNED NOT NULL DEFAULT 0 COMMENT '右侧推荐',
  `left_elite` tinyint UNSIGNED NOT NULL DEFAULT 0 COMMENT '左侧推荐',
  `deleted` tinyint UNSIGNED NOT NULL DEFAULT 0 COMMENT '是否删除',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  `begin_time` datetime NULL DEFAULT NULL COMMENT '开始时间',
  `end_time` datetime NULL DEFAULT NULL COMMENT '结束时间',
  `create_user` varchar(100) NULL DEFAULT NULL,
  `delete_user` varchar(100) NULL DEFAULT NULL,
  `delete_time` datetime NULL DEFAULT NULL COMMENT '删除时间',
  `entry_into_force_time` datetime NULL DEFAULT CURRENT_TIMESTAMP COMMENT '生效时间',
  `expiration_time` datetime NULL DEFAULT '9999-12-31 23:59:00' COMMENT '失效时间',
  `published` tinyint NOT NULL DEFAULT 0 COMMENT '是否已经发布静态页面',
  `create_by` varchar(100) NULL DEFAULT NULL,
  `create_date` datetime NULL DEFAULT NULL,
  `is_deleted` int NULL DEFAULT 0,
  `language_id` int NULL DEFAULT 0,
  `last_modified_by` varchar(100) NULL DEFAULT NULL,
  `last_modified_date` datetime NULL DEFAULT NULL,
  `version` int NULL DEFAULT 0,
  `mhits` int NULL DEFAULT NULL,
  `publish` int NULL DEFAULT NULL,
  `public_search_text` LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL COMMENT '公开搜索清洗文本投影',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_contents_class`(`class_id` ASC, `id` ASC) USING BTREE,
  INDEX `idx_contents_class_public_sort` (`class_id`, `status`, `deleted`, `sort`, `create_time`, `id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of t_contents
-- ----------------------------

-- 导入身份与内容、历史同事务提交，内容删除后保留收据。
DROP TABLE IF EXISTS `t_content_seed_receipts`;
CREATE TABLE `t_content_seed_receipts` (
  `site_id` bigint NOT NULL,
  `menu_id` int NOT NULL,
  `seed_key` char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `source_signature` char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `content_id` int UNSIGNED NOT NULL,
  PRIMARY KEY (`site_id`, `menu_id`, `seed_key`)
) ENGINE = InnoDB;

-- ----------------------------
-- Table structure for t_contents_history
-- ----------------------------
DROP TABLE IF EXISTS `t_contents_history`;
CREATE TABLE `t_member_credentials` (
  `content_id` int UNSIGNED NOT NULL,
  `password_hash` varchar(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  PRIMARY KEY (`content_id`),
  CONSTRAINT `fk_member_credentials_content` FOREIGN KEY (`content_id`)
    REFERENCES `t_contents` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE `t_contents_history`  (
  `id` int NOT NULL AUTO_INCREMENT COMMENT 'id',
  `content_id` int NULL DEFAULT 0 COMMENT '内容id',
  `title` varchar(500) NULL DEFAULT NULL COMMENT '标题',
  `menu_id` int NULL DEFAULT 0 COMMENT '栏目ID，用于生成编辑链接等',
  `params` longtext NULL COMMENT '字段',
  `update_description` varchar(100) NULL DEFAULT NULL,
  `user_id` int NULL DEFAULT 0,
  `user_account` varchar(60) NULL DEFAULT NULL,
  `user_name` varchar(50) NULL DEFAULT NULL,
  `check_status` tinyint NULL DEFAULT 0,
  `check_description` varchar(100) NULL DEFAULT NULL,
  `create_time` datetime NULL DEFAULT NULL COMMENT '创建时间',
  `comparison` longtext NULL COMMENT '比对更新字段',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_contents_history_menu`(`menu_id` ASC, `id` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of t_contents_history
-- ----------------------------

-- ----------------------------
-- Table structure for t_fieldsetting
-- ----------------------------
DROP TABLE IF EXISTS `t_fieldsetting`;
CREATE TABLE `t_fieldsetting`  (
  `id` int NOT NULL AUTO_INCREMENT COMMENT 'ID',
  `field_name` varchar(255) NOT NULL COMMENT '字段名称',
  `from_name` varchar(255) NOT NULL COMMENT '字段别名',
  `field_type` varchar(255) NOT NULL COMMENT '字段类型',
  `type` tinyint(1) NOT NULL DEFAULT 0 COMMENT '1为列表字段，0为单篇字段',
  `sort` int UNSIGNED NOT NULL DEFAULT 0 COMMENT '排序',
  `bak` varchar(255) NULL DEFAULT NULL COMMENT '默认提示信息',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_fieldsetting_type_sort`(`type` ASC, `sort` DESC, `id` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of t_fieldsetting
-- ----------------------------
INSERT INTO `t_fieldsetting` VALUES (1, 'title', '标题', 'SingleLine', 1, 900, NULL);
INSERT INTO `t_fieldsetting` VALUES (3, 'singleLine1', '副标题', 'SingleLine', 1, 900, NULL);
INSERT INTO `t_fieldsetting` VALUES (4, 'singleLine2', '作者', 'SingleLine', 1, 900, NULL);
INSERT INTO `t_fieldsetting` VALUES (5, 'singleLine3', '来源', 'SingleLine', 1, 900, NULL);
INSERT INTO `t_fieldsetting` VALUES (6, 'singleLine4', '外部链接', 'SingleLine', 1, 900, '请输入以http://或https://开头的完整网址');
INSERT INTO `t_fieldsetting` VALUES (7, 'singleLine5', '单行文本', 'SingleLine', 1, 900, NULL);
INSERT INTO `t_fieldsetting` VALUES (8, 'singleLine6', '单行文本', 'SingleLine', 1, 900, NULL);
INSERT INTO `t_fieldsetting` VALUES (9, 'singleLine7', '单行文本', 'SingleLine', 1, 900, NULL);
INSERT INTO `t_fieldsetting` VALUES (10, 'singleLine8', '单行文本', 'SingleLine', 1, 900, NULL);
INSERT INTO `t_fieldsetting` VALUES (11, 'singleLine9', '单行文本', 'SingleLine', 1, 900, NULL);
INSERT INTO `t_fieldsetting` VALUES (12, 'singleLine10', '单行文本', 'SingleLine', 1, 900, NULL);
INSERT INTO `t_fieldsetting` VALUES (13, 'singleLine11', '单行文本', 'SingleLine', 1, 900, NULL);
INSERT INTO `t_fieldsetting` VALUES (14, 'singleLine12', '单行文本', 'SingleLine', 1, 900, NULL);
INSERT INTO `t_fieldsetting` VALUES (15, 'note1', '简介', 'MultiLine', 1, 800, NULL);
INSERT INTO `t_fieldsetting` VALUES (16, 'note2', '多行文本', 'MultiLine', 1, 800, NULL);
INSERT INTO `t_fieldsetting` VALUES (17, 'note3', '多行文本', 'MultiLine', 1, 800, NULL);
INSERT INTO `t_fieldsetting` VALUES (18, 'note4', '多行文本', 'MultiLine', 1, 800, NULL);
INSERT INTO `t_fieldsetting` VALUES (19, 'note5', '多行文本', 'MultiLine', 1, 800, NULL);
INSERT INTO `t_fieldsetting` VALUES (20, 'note6', '多行文本', 'MultiLine', 1, 800, NULL);
INSERT INTO `t_fieldsetting` VALUES (21, 'note7', '多行文本', 'MultiLine', 1, 800, NULL);
INSERT INTO `t_fieldsetting` VALUES (22, 'photo1', '图片', 'Image', 1, 700, '图片建议尺寸：()*()');
INSERT INTO `t_fieldsetting` VALUES (23, 'photo2', '图片', 'Image', 1, 700, '图片建议尺寸：()*()');
INSERT INTO `t_fieldsetting` VALUES (24, 'photo3', '图片', 'Image', 1, 700, '图片建议尺寸：()*()');
INSERT INTO `t_fieldsetting` VALUES (25, 'photo4', '图片', 'Image', 1, 700, '图片建议尺寸：()*()');
INSERT INTO `t_fieldsetting` VALUES (26, 'photo5', '图片', 'Image', 1, 700, '图片建议尺寸：()*()');
INSERT INTO `t_fieldsetting` VALUES (28, 'media1', '视频', 'Video', 1, 500, '请上传格式为(MP4且编码为H264)的文件');
INSERT INTO `t_fieldsetting` VALUES (29, 'media2', '视频', 'Video', 1, 500, '请上传格式为(MP4且编码为H264)的文件');
INSERT INTO `t_fieldsetting` VALUES (30, 'media3', '视频', 'Video', 1, 500, '请上传格式为(MP4且编码为H264)的文件');
INSERT INTO `t_fieldsetting` VALUES (31, 'content1', '内容', 'Editor', 1, 400, NULL);
INSERT INTO `t_fieldsetting` VALUES (32, 'content2', '编辑器', 'Editor', 1, 400, NULL);
INSERT INTO `t_fieldsetting` VALUES (33, 'content3', '编辑器', 'Editor', 1, 400, NULL);
INSERT INTO `t_fieldsetting` VALUES (34, 'singleLine1', '标题', 'SingleLine', 0, 900, NULL);
INSERT INTO `t_fieldsetting` VALUES (35, 'singleLine2', '单行文本', 'SingleLine', 0, 900, NULL);
INSERT INTO `t_fieldsetting` VALUES (36, 'singleLine3', '单行文本', 'SingleLine', 0, 900, NULL);
INSERT INTO `t_fieldsetting` VALUES (37, 'singleLine4', '单行文本', 'SingleLine', 0, 900, NULL);
INSERT INTO `t_fieldsetting` VALUES (38, 'singleLine5', '单行文本', 'SingleLine', 0, 900, NULL);
INSERT INTO `t_fieldsetting` VALUES (39, 'singleLine6', '单行文本', 'SingleLine', 0, 900, NULL);
INSERT INTO `t_fieldsetting` VALUES (40, 'singleLine7', '单行文本', 'SingleLine', 0, 900, NULL);
INSERT INTO `t_fieldsetting` VALUES (41, 'singleLine8', '单行文本', 'SingleLine', 0, 900, NULL);
INSERT INTO `t_fieldsetting` VALUES (42, 'singleLine9', '单行文本', 'SingleLine', 0, 900, NULL);
INSERT INTO `t_fieldsetting` VALUES (43, 'singleLine10', '单行文本', 'SingleLine', 0, 900, NULL);
INSERT INTO `t_fieldsetting` VALUES (44, 'singleLine11', '单行文本', 'SingleLine', 0, 900, NULL);
INSERT INTO `t_fieldsetting` VALUES (45, 'singleLine12', '单行文本', 'SingleLine', 0, 900, NULL);
INSERT INTO `t_fieldsetting` VALUES (46, 'singleLine13', '单行文本', 'SingleLine', 0, 900, NULL);
INSERT INTO `t_fieldsetting` VALUES (47, 'singleLine14', '单行文本', 'SingleLine', 0, 900, NULL);
INSERT INTO `t_fieldsetting` VALUES (48, 'singleLine15', '单行文本', 'SingleLine', 0, 900, NULL);
INSERT INTO `t_fieldsetting` VALUES (49, 'note1', '简介', 'MultiLine', 0, 800, NULL);
INSERT INTO `t_fieldsetting` VALUES (50, 'note2', '多行文本', 'MultiLine', 0, 800, NULL);
INSERT INTO `t_fieldsetting` VALUES (51, 'note3', '多行文本', 'MultiLine', 0, 800, NULL);
INSERT INTO `t_fieldsetting` VALUES (52, 'note4', '多行文本', 'MultiLine', 0, 800, NULL);
INSERT INTO `t_fieldsetting` VALUES (53, 'note5', '多行文本', 'MultiLine', 0, 800, NULL);
INSERT INTO `t_fieldsetting` VALUES (54, 'note6', '多行文本', 'MultiLine', 0, 800, NULL);
INSERT INTO `t_fieldsetting` VALUES (55, 'photo1', '图片', 'Image', 0, 700, '图片建议尺寸：()*()');
INSERT INTO `t_fieldsetting` VALUES (56, 'photo2', '图片', 'Image', 0, 700, '图片建议尺寸：()*()');
INSERT INTO `t_fieldsetting` VALUES (57, 'photo3', '图片', 'Image', 0, 700, '图片建议尺寸：()*()');
INSERT INTO `t_fieldsetting` VALUES (58, 'photo4', '图片', 'Image', 0, 700, '图片建议尺寸：()*()');
INSERT INTO `t_fieldsetting` VALUES (59, 'photo5', '图片', 'Image', 0, 700, '图片建议尺寸：()*()');
INSERT INTO `t_fieldsetting` VALUES (60, 'photo6', '图片', 'Image', 0, 700, '图片建议尺寸：()*()');
INSERT INTO `t_fieldsetting` VALUES (61, 'photo7', '图片', 'Image', 0, 700, '图片建议尺寸：()*()');
INSERT INTO `t_fieldsetting` VALUES (62, 'photo8', '图片', 'Image', 0, 700, '图片建议尺寸：()*()');
INSERT INTO `t_fieldsetting` VALUES (63, 'photo9', '图片', 'Image', 0, 700, '图片建议尺寸：()*()');
INSERT INTO `t_fieldsetting` VALUES (64, 'photo10', '图片', 'Image', 0, 700, '图片建议尺寸：()*()');
INSERT INTO `t_fieldsetting` VALUES (65, 'file1', '附件', 'File', 0, 600, '请上传格式为(pdf、doc、docx、xls、xlsx、ppt、pptx、rar、zip等)的文件');
INSERT INTO `t_fieldsetting` VALUES (66, 'file2', '附件', 'File', 0, 600, '请上传格式为()的文件');
INSERT INTO `t_fieldsetting` VALUES (67, 'file3', '附件', 'File', 0, 600, '请上传格式为()的文件');
INSERT INTO `t_fieldsetting` VALUES (68, 'content1', '内容', 'Editor', 0, 500, NULL);
INSERT INTO `t_fieldsetting` VALUES (69, 'content2', '编辑器', 'Editor', 0, 500, NULL);
INSERT INTO `t_fieldsetting` VALUES (70, 'content3', '编辑器', 'Editor', 0, 500, NULL);
INSERT INTO `t_fieldsetting` VALUES (71, 'content4', '编辑器', 'Editor', 0, 500, NULL);
INSERT INTO `t_fieldsetting` VALUES (72, 'file1', '附件', 'File', 1, 600, '请上传格式为(pdf、doc、docx、xls、xlsx、ppt、pptx、rar、zip等)的文件');
INSERT INTO `t_fieldsetting` VALUES (73, 'file2', '附件', 'File', 1, 600, '请上传格式为()的文件');
INSERT INTO `t_fieldsetting` VALUES (74, 'file3', '附件', 'File', 1, 600, '请上传格式为()的文件');
INSERT INTO `t_fieldsetting` VALUES (75, 'media4', '视频', 'Video', 1, 500, '请上传格式为(MP4且编码为H264)的文件');
INSERT INTO `t_fieldsetting` VALUES (76, 'media1', '视频', 'Video', 0, 400, '请上传格式为(MP4且编码为H264)的文件');
INSERT INTO `t_fieldsetting` VALUES (77, 'media2', '视频', 'Video', 0, 400, '请上传格式为(MP4且编码为H264)的文件');
INSERT INTO `t_fieldsetting` VALUES (78, 'media3', '视频', 'Video', 0, 400, '请上传格式为(MP4且编码为H264)的文件');
INSERT INTO `t_fieldsetting` VALUES (79, 'media4', '视频', 'Video', 0, 400, '请上传格式为(MP4且编码为H264)的文件');
INSERT INTO `t_fieldsetting` VALUES (80, 'file4', '附件', 'File', 0, 600, '请上传格式为()的文件');
INSERT INTO `t_fieldsetting` VALUES (81, 'beginTime', '开始时间', 'DateTime', 0, 300, NULL);
INSERT INTO `t_fieldsetting` VALUES (82, 'endTime', '结束时间', 'DateTime', 0, 300, NULL);
INSERT INTO `t_fieldsetting` VALUES (83, 'beginTime', '开始时间', 'DateTime', 1, 300, NULL);
INSERT INTO `t_fieldsetting` VALUES (84, 'endTime', '结束时间', 'DateTime', 1, 300, NULL);
INSERT INTO `t_fieldsetting` VALUES (85, 'createTime', '发布时间', 'DateTime', 0, 300, NULL);
INSERT INTO `t_fieldsetting` VALUES (86, 'createTime', '发布时间', 'DateTime', 1, 300, NULL);
INSERT INTO `t_fieldsetting` VALUES (87, 'singleLine13', '单行文本', 'SingleLine', 1, 900, NULL);
INSERT INTO `t_fieldsetting` VALUES (88, 'singleLine14', '单行文本', 'SingleLine', 1, 900, NULL);
INSERT INTO `t_fieldsetting` VALUES (89, 'singleLine15', '单行文本', 'SingleLine', 1, 900, NULL);
INSERT INTO `t_fieldsetting` VALUES (90, 'singleLine16', '单行文本', 'SingleLine', 1, 900, NULL);
INSERT INTO `t_fieldsetting` VALUES (91, 'singleLine17', '单行文本', 'SingleLine', 1, 900, NULL);
INSERT INTO `t_fieldsetting` VALUES (92, 'singleLine18', '单行文本', 'SingleLine', 1, 900, NULL);
INSERT INTO `t_fieldsetting` VALUES (93, 'singleLine19', '单行文本', 'SingleLine', 1, 900, NULL);
INSERT INTO `t_fieldsetting` VALUES (94, 'singleLine20', '单行文本', 'SingleLine', 1, 900, NULL);
INSERT INTO `t_fieldsetting` VALUES (95, 'note8', '多行文本', 'MultiLine', 1, 800, NULL);
INSERT INTO `t_fieldsetting` VALUES (96, 'note9', '多行文本', 'MultiLine', 1, 800, NULL);
INSERT INTO `t_fieldsetting` VALUES (97, 'note10', '多行文本', 'MultiLine', 1, 800, NULL);
INSERT INTO `t_fieldsetting` VALUES (98, 'note11', '多行文本', 'MultiLine', 1, 800, NULL);
INSERT INTO `t_fieldsetting` VALUES (99, 'note12', '多行文本', 'MultiLine', 1, 800, NULL);
INSERT INTO `t_fieldsetting` VALUES (100, 'note13', '多行文本', 'MultiLine', 1, 800, NULL);
INSERT INTO `t_fieldsetting` VALUES (101, 'note14', '多行文本', 'MultiLine', 1, 800, NULL);
INSERT INTO `t_fieldsetting` VALUES (102, 'note15', '多行文本', 'MultiLine', 1, 800, NULL);
INSERT INTO `t_fieldsetting` VALUES (103, 'photo6', '图片', 'Image', 1, 700, '图片建议尺寸：()*()');
INSERT INTO `t_fieldsetting` VALUES (104, 'photo7', '图片', 'Image', 1, 700, '图片建议尺寸：()*()');
INSERT INTO `t_fieldsetting` VALUES (105, 'photo8', '图片', 'Image', 1, 700, '图片建议尺寸：()*()');
INSERT INTO `t_fieldsetting` VALUES (106, 'photo9', '图片', 'Image', 1, 700, '图片建议尺寸：()*()');
INSERT INTO `t_fieldsetting` VALUES (107, 'photo10', '图片', 'Image', 1, 700, '图片建议尺寸：()*()');
INSERT INTO `t_fieldsetting` VALUES (108, 'photo11', '图片', 'Image', 1, 700, '图片建议尺寸：()*()');
INSERT INTO `t_fieldsetting` VALUES (109, 'photo12', '图片', 'Image', 1, 700, '图片建议尺寸：()*()');
INSERT INTO `t_fieldsetting` VALUES (110, 'photo13', '图片', 'Image', 1, 700, '图片建议尺寸：()*()');
INSERT INTO `t_fieldsetting` VALUES (111, 'photo14', '图片', 'Image', 1, 700, '图片建议尺寸：()*()');
INSERT INTO `t_fieldsetting` VALUES (112, 'photo15', '图片', 'Image', 1, 700, '图片建议尺寸：()*()');
INSERT INTO `t_fieldsetting` VALUES (113, 'content4', '编辑器', 'Editor', 1, 400, NULL);
INSERT INTO `t_fieldsetting` VALUES (114, 'content5', '编辑器', 'Editor', 1, 400, NULL);
INSERT INTO `t_fieldsetting` VALUES (115, 'content6', '编辑器', 'Editor', 1, 400, NULL);
INSERT INTO `t_fieldsetting` VALUES (116, 'file4', '附件', 'File', 1, 600, '请上传格式为()的文件');
INSERT INTO `t_fieldsetting` VALUES (117, 'tags', 'tags标签', 'SingleLine', 1, 900, NULL);
INSERT INTO `t_fieldsetting` VALUES (118, 'singleLine16', '单行文本', 'SingleLine', 0, 900, NULL);
INSERT INTO `t_fieldsetting` VALUES (119, 'singleLine17', '单行文本', 'SingleLine', 0, 900, NULL);
INSERT INTO `t_fieldsetting` VALUES (120, 'singleLine18', '单行文本', 'SingleLine', 0, 900, NULL);
INSERT INTO `t_fieldsetting` VALUES (121, 'singleLine19', '单行文本', 'SingleLine', 0, 900, NULL);
INSERT INTO `t_fieldsetting` VALUES (122, 'singleLine20', '单行文本', 'SingleLine', 0, 900, NULL);
INSERT INTO `t_fieldsetting` VALUES (123, 'singleLine21', '单行文本', 'SingleLine', 0, 900, NULL);
INSERT INTO `t_fieldsetting` VALUES (124, 'singleLine22', '单行文本', 'SingleLine', 0, 900, NULL);
INSERT INTO `t_fieldsetting` VALUES (125, 'singleLine23', '单行文本', 'SingleLine', 0, 900, NULL);
INSERT INTO `t_fieldsetting` VALUES (126, 'singleLine24', '单行文本', 'SingleLine', 0, 900, NULL);
INSERT INTO `t_fieldsetting` VALUES (127, 'singleLine25', '单行文本', 'SingleLine', 0, 900, NULL);
INSERT INTO `t_fieldsetting` VALUES (128, 'singleLine26', '单行文本', 'SingleLine', 0, 900, NULL);
INSERT INTO `t_fieldsetting` VALUES (129, 'singleLine27', '单行文本', 'SingleLine', 0, 900, NULL);
INSERT INTO `t_fieldsetting` VALUES (130, 'singleLine28', '单行文本', 'SingleLine', 0, 900, NULL);
INSERT INTO `t_fieldsetting` VALUES (131, 'singleLine29', '单行文本', 'SingleLine', 0, 900, NULL);
INSERT INTO `t_fieldsetting` VALUES (132, 'singleLine30', '单行文本', 'SingleLine', 0, 900, NULL);
INSERT INTO `t_fieldsetting` VALUES (133, 'note7', '多行文本', 'MultiLine', 0, 800, NULL);
INSERT INTO `t_fieldsetting` VALUES (134, 'note8', '多行文本', 'MultiLine', 0, 800, NULL);
INSERT INTO `t_fieldsetting` VALUES (135, 'note9', '多行文本', 'MultiLine', 0, 800, NULL);
INSERT INTO `t_fieldsetting` VALUES (136, 'note10', '多行文本', 'MultiLine', 0, 800, NULL);
INSERT INTO `t_fieldsetting` VALUES (137, 'note11', '多行文本', 'MultiLine', 0, 800, NULL);
INSERT INTO `t_fieldsetting` VALUES (138, 'note12', '多行文本', 'MultiLine', 0, 800, NULL);
INSERT INTO `t_fieldsetting` VALUES (139, 'note13', '多行文本', 'MultiLine', 0, 800, NULL);
INSERT INTO `t_fieldsetting` VALUES (140, 'note14', '多行文本', 'MultiLine', 0, 800, NULL);
INSERT INTO `t_fieldsetting` VALUES (141, 'note15', '多行文本', 'MultiLine', 0, 800, NULL);
INSERT INTO `t_fieldsetting` VALUES (142, 'photo11', '图片', 'Image', 0, 700, '图片建议尺寸：()*()');
INSERT INTO `t_fieldsetting` VALUES (143, 'photo12', '图片', 'Image', 0, 700, '图片建议尺寸：()*()');
INSERT INTO `t_fieldsetting` VALUES (144, 'photo13', '图片', 'Image', 0, 700, '图片建议尺寸：()*()');
INSERT INTO `t_fieldsetting` VALUES (145, 'photo14', '图片', 'Image', 0, 700, '图片建议尺寸：()*()');
INSERT INTO `t_fieldsetting` VALUES (146, 'photo15', '图片', 'Image', 0, 700, '图片建议尺寸：()*()');
INSERT INTO `t_fieldsetting` VALUES (147, 'content5', '编辑器', 'Editor', 0, 500, NULL);
INSERT INTO `t_fieldsetting` VALUES (148, 'content6', '编辑器', 'Editor', 0, 500, NULL);
INSERT INTO `t_fieldsetting` VALUES (149, 'entryIntoForceTime', '生效时间', 'DateTime', 0, 300, NULL);
INSERT INTO `t_fieldsetting` VALUES (150, 'expirationTime', '过期时间', 'DateTime', 0, 300, NULL);

-- ----------------------------
-- Table structure for t_file_info
-- ----------------------------
DROP TABLE IF EXISTS `t_file_info`;
CREATE TABLE `t_file_info`  (
  `id` int NOT NULL AUTO_INCREMENT,
  `create_by` varchar(255) NULL DEFAULT NULL,
  `create_date` datetime NULL DEFAULT NULL,
  `is_deleted` int NULL DEFAULT 0,
  `status` int NULL DEFAULT 0,
  `language_id` int NULL DEFAULT 0,
  `last_modified_by` varchar(255) NULL DEFAULT NULL,
  `last_modified_date` datetime NULL DEFAULT NULL,
  `sort` int NULL DEFAULT 0,
  `version` int NULL DEFAULT 0,
  `access_path` varchar(255) NULL DEFAULT NULL,
  `content_type` varchar(255) NULL DEFAULT NULL,
  `extension` varchar(255) NULL DEFAULT NULL,
  `file_size` bigint NULL DEFAULT NULL,
  `file_size_string` varchar(255) NULL DEFAULT NULL,
  `image_height` int NULL DEFAULT NULL,
  `image_width` int NULL DEFAULT NULL,
  `material_type` varchar(255) NULL DEFAULT NULL,
  `new_name` varchar(255) NULL DEFAULT NULL,
  `old_name` varchar(255) NULL DEFAULT NULL,
  `deleted` int NULL DEFAULT 0,
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = MyISAM AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of t_file_info
-- ----------------------------

-- ----------------------------
-- Table structure for t_from_contents
-- ----------------------------
DROP TABLE IF EXISTS `t_from_contents`;
CREATE TABLE `t_from_contents`  (
  `id` int UNSIGNED NOT NULL AUTO_INCREMENT,
  `class_id` int NULL DEFAULT 0 COMMENT '分类编号',
  `title` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '标题',
  `tags` varchar(200) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '标签',
  `hits` int UNSIGNED NOT NULL DEFAULT 0 COMMENT '访问次数',
  `sort` int NOT NULL DEFAULT 0 COMMENT '排序号',
  `status` tinyint(1) NOT NULL DEFAULT 0 COMMENT '发布状态',
  `single_line1` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '单行文本',
  `single_line2` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '单行文本',
  `single_line3` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '单行文本',
  `single_line4` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '单行文本',
  `single_line5` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '单行文本',
  `single_line6` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '单行文本',
  `single_line7` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '单行文本',
  `single_line8` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '单行文本',
  `single_line9` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '单行文本',
  `single_line10` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '单行文本',
  `single_line11` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '单行文本',
  `single_line12` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '单行文本',
  `single_line13` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '单行文本',
  `single_line14` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '单行文本',
  `single_line15` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '单行文本',
  `single_line16` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '单行文本',
  `single_line17` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '单行文本',
  `single_line18` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '单行文本',
  `single_line19` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '单行文本',
  `single_line20` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '单行文本',
  `xgid` int NOT NULL DEFAULT 0 COMMENT '关联数据ID',
  `note1` mediumtext CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL COMMENT '多行文本',
  `note2` mediumtext CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL COMMENT '多行文本',
  `note3` mediumtext CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL COMMENT '多行文本',
  `note4` mediumtext CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL COMMENT '多行文本',
  `note5` mediumtext CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL COMMENT '多行文本',
  `note6` mediumtext CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL COMMENT '多行文本',
  `note7` mediumtext CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL COMMENT '多行文本',
  `note8` mediumtext CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL COMMENT '多行文本',
  `note9` mediumtext CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL COMMENT '多行文本',
  `note10` mediumtext CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL COMMENT '多行文本',
  `note11` mediumtext CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL COMMENT '多行文本',
  `note12` mediumtext CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL COMMENT '多行文本',
  `note13` mediumtext CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL COMMENT '多行文本',
  `note14` mediumtext CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL COMMENT '多行文本',
  `note15` mediumtext CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL COMMENT '多行文本',
  `photo1` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '图片',
  `photo2` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '图片',
  `photo3` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '图片',
  `photo4` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '图片',
  `photo5` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '图片',
  `photo6` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '图片',
  `photo7` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '图片',
  `photo8` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '图片',
  `photo9` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '图片',
  `photo10` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '图片',
  `photo11` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '图片',
  `photo12` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '图片',
  `photo13` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '图片',
  `photo14` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '图片',
  `photo15` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '图片',
  `seo_title` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT 'seo标题',
  `seo_keywords` mediumtext CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL COMMENT 'seo关键字',
  `seo_description` mediumtext CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL COMMENT 'seo描述',
  `media1` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '视频',
  `media2` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '视频',
  `media3` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '视频',
  `media4` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '视频',
  `file1` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '视频',
  `file2` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '视频',
  `file3` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '视频',
  `file4` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '视频',
  `content1` longtext NULL,
  `content2` longtext NULL,
  `content3` longtext NULL,
  `content4` longtext NULL,
  `content5` longtext NULL,
  `content6` longtext NULL,
  `index_top` tinyint UNSIGNED NOT NULL DEFAULT 0 COMMENT '首页置顶',
  `index_elite` tinyint UNSIGNED NOT NULL DEFAULT 0 COMMENT '首页推荐',
  `index_hot` tinyint UNSIGNED NOT NULL DEFAULT 0 COMMENT '首页热门',
  `channel_top` tinyint UNSIGNED NOT NULL DEFAULT 0 COMMENT '频道置顶',
  `channel_elite` tinyint UNSIGNED NOT NULL DEFAULT 0 COMMENT '频道推荐',
  `channel_hot` tinyint UNSIGNED NOT NULL DEFAULT 0 COMMENT '频道热门',
  `menu_top` tinyint UNSIGNED NOT NULL DEFAULT 0 COMMENT '栏目置顶',
  `menu_elite` tinyint UNSIGNED NOT NULL DEFAULT 0 COMMENT '栏目推荐',
  `menu_hot` tinyint UNSIGNED NOT NULL DEFAULT 0 COMMENT '栏目热门',
  `nav_elite` tinyint NOT NULL DEFAULT 0 COMMENT '导航推荐',
  `right_elite` tinyint UNSIGNED NOT NULL DEFAULT 0 COMMENT '右侧推荐',
  `left_elite` tinyint UNSIGNED NOT NULL DEFAULT 0 COMMENT '左侧推荐',
  `deleted` tinyint UNSIGNED NOT NULL DEFAULT 0 COMMENT '是否删除',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  `begin_time` datetime NULL DEFAULT NULL COMMENT '开始时间',
  `end_time` datetime NULL DEFAULT NULL COMMENT '结束时间',
  `create_user` varchar(100) NULL DEFAULT NULL,
  `delete_user` varchar(100) NULL DEFAULT NULL,
  `delete_time` datetime NULL DEFAULT NULL COMMENT '删除时间',
  `entry_into_force_time` datetime NULL DEFAULT CURRENT_TIMESTAMP COMMENT '生效时间',
  `expiration_time` datetime NULL DEFAULT '9999-12-31 23:59:00' COMMENT '失效时间',
  `published` tinyint NOT NULL DEFAULT 0 COMMENT '是否已经发布静态页面',
  `create_by` varchar(100) NULL DEFAULT NULL,
  `create_date` datetime NULL DEFAULT NULL,
  `is_deleted` int NULL DEFAULT 0,
  `language_id` int NULL DEFAULT 0,
  `last_modified_by` varchar(100) NULL DEFAULT NULL,
  `last_modified_date` datetime NULL DEFAULT NULL,
  `version` int NULL DEFAULT 0,
  `mhits` int NULL DEFAULT NULL,
  `publish` int NULL DEFAULT NULL,
  `query_token` VARCHAR(32) NULL DEFAULT NULL COMMENT '随机提交回查凭证，旧记录为空',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_contents_class`(`class_id` ASC, `id` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of t_from_contents
-- ----------------------------

-- ----------------------------
-- Table structure for t_from_oneshot
-- ----------------------------
DROP TABLE IF EXISTS `t_from_oneshot`;
CREATE TABLE `t_from_oneshot`  (
  `id` int UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `site_project_id` bigint NULL DEFAULT NULL,
  `site_root_id` int NULL DEFAULT NULL,
  `class_name` varchar(255) NOT NULL COMMENT '栏目名称',
  `class_en_name` varchar(500) NULL DEFAULT NULL COMMENT '栏目英文名称',
  `single_line1` varchar(500) NULL DEFAULT NULL COMMENT '标题',
  `single_line2` varchar(500) NULL DEFAULT NULL,
  `single_line3` varchar(500) NULL DEFAULT NULL,
  `single_line4` varchar(500) NULL DEFAULT NULL,
  `single_line5` varchar(500) NULL DEFAULT NULL,
  `single_line6` varchar(500) NULL DEFAULT NULL,
  `single_line7` varchar(500) NULL DEFAULT NULL,
  `single_line8` varchar(500) NULL DEFAULT NULL,
  `single_line9` varchar(500) NULL DEFAULT NULL,
  `single_line10` varchar(500) NULL DEFAULT NULL,
  `single_line11` varchar(255) NULL DEFAULT NULL,
  `single_line12` varchar(255) NULL DEFAULT NULL,
  `single_line13` varchar(255) NULL DEFAULT NULL,
  `single_line14` varchar(255) NULL DEFAULT NULL,
  `single_line15` varchar(255) NULL DEFAULT NULL,
  `single_line16` varchar(255) NULL DEFAULT NULL,
  `single_line17` varchar(255) NULL DEFAULT NULL,
  `single_line18` varchar(255) NULL DEFAULT NULL,
  `single_line19` varchar(255) NULL DEFAULT NULL,
  `single_line20` varchar(255) NULL DEFAULT NULL,
  `single_line21` varchar(255) NULL DEFAULT NULL,
  `single_line22` varchar(255) NULL DEFAULT NULL,
  `single_line23` varchar(255) NULL DEFAULT NULL,
  `single_line24` varchar(255) NULL DEFAULT NULL,
  `single_line25` varchar(255) NULL DEFAULT NULL,
  `single_line26` varchar(255) NULL DEFAULT NULL,
  `single_line27` varchar(255) NULL DEFAULT NULL,
  `single_line28` varchar(255) NULL DEFAULT NULL,
  `single_line29` varchar(255) NULL DEFAULT NULL,
  `single_line30` varchar(255) NULL DEFAULT NULL,
  `note1` mediumtext NULL,
  `note2` mediumtext NULL,
  `note3` mediumtext NULL,
  `note4` mediumtext NULL,
  `note5` mediumtext NULL,
  `note6` mediumtext NULL,
  `note7` mediumtext NULL,
  `note8` mediumtext NULL,
  `note9` mediumtext NULL,
  `note10` mediumtext NULL,
  `note11` mediumtext NULL,
  `note12` mediumtext NULL,
  `note13` mediumtext NULL,
  `note14` mediumtext NULL,
  `note15` mediumtext NULL,
  `photo1` varchar(255) NULL DEFAULT NULL,
  `photo2` varchar(255) NULL DEFAULT NULL,
  `photo3` varchar(255) NULL DEFAULT NULL,
  `photo4` varchar(255) NULL DEFAULT NULL,
  `photo5` varchar(255) NULL DEFAULT NULL,
  `photo6` varchar(255) NULL DEFAULT NULL,
  `photo7` varchar(255) NULL DEFAULT NULL,
  `photo8` varchar(255) NULL DEFAULT NULL,
  `photo9` varchar(255) NULL DEFAULT NULL,
  `photo10` varchar(255) NULL DEFAULT NULL,
  `photo11` varchar(255) NULL DEFAULT NULL,
  `photo12` varchar(255) NULL DEFAULT NULL,
  `photo13` varchar(255) NULL DEFAULT NULL,
  `photo14` varchar(255) NULL DEFAULT NULL,
  `photo15` varchar(255) NULL DEFAULT NULL,
  `create_time` datetime NULL DEFAULT NULL,
  `begin_time` datetime NULL DEFAULT NULL,
  `end_time` datetime NULL DEFAULT NULL,
  `file1` varchar(500) NULL DEFAULT NULL COMMENT '附件',
  `file2` varchar(500) NULL DEFAULT NULL COMMENT '附件',
  `file3` varchar(500) NULL DEFAULT NULL COMMENT '附件',
  `file4` varchar(500) NULL DEFAULT NULL COMMENT '附件',
  `content1` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL,
  `content2` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL,
  `content3` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL,
  `content4` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL,
  `content5` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL,
  `content6` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL,
  `list_page_url` varchar(255) NULL DEFAULT NULL COMMENT '前台栏目页地址',
  `list_page_template` varchar(100) NULL DEFAULT NULL COMMENT '前台列表页模板',
  `detail_page_url` varchar(255) NULL DEFAULT NULL COMMENT '前台内容页地址',
  `detail_page_template` varchar(100) NULL DEFAULT NULL COMMENT '前台内容页模板',
  `seo_title` varchar(255) NULL DEFAULT NULL COMMENT 'seo标题',
  `seo_keywords` mediumtext NULL COMMENT 'seo关键字',
  `seo_description` mediumtext NULL COMMENT 'seo描述',
  `parent_id` int NOT NULL DEFAULT 0 COMMENT '父级ID',
  `sort` int NOT NULL DEFAULT 0 COMMENT '排序',
  `menu_field` mediumtext NULL COMMENT '单篇栏目字段',
  `identifies` varchar(255) NULL DEFAULT NULL COMMENT '唯一值',
  `model_field` mediumtext NULL COMMENT '列表栏目字段',
  `model_list` mediumtext NULL COMMENT '列表栏目列表显示字段',
  `model_search` mediumtext NULL COMMENT '列表栏目搜索字段',
  `setting` varchar(500) NULL DEFAULT NULL COMMENT '权限集合',
  `has_manage_channel` tinyint(1) NOT NULL DEFAULT 1 COMMENT '是否后台显示',
  `url` varchar(255) NULL DEFAULT NULL COMMENT '后台访问路径',
  `has_front_channel` tinyint(1) NOT NULL DEFAULT 1 COMMENT '是否前台显示',
  `page_type` int NOT NULL DEFAULT 0 COMMENT '页面类型 0单篇 1列表',
  `hits` int UNSIGNED NULL DEFAULT 0 COMMENT '访问次数',
  `is_sub_menu` tinyint UNSIGNED NOT NULL DEFAULT 0 COMMENT '是否二级栏目',
  `is_can_deleted` tinyint UNSIGNED NOT NULL DEFAULT 0 COMMENT '是否可以删除',
  `is_module_menu` tinyint UNSIGNED NOT NULL DEFAULT 0 COMMENT '是否为模块基础栏目',
  `is_can_cascade_deleted` tinyint UNSIGNED NOT NULL DEFAULT 0 COMMENT '是否可以级联删除(当删除父栏目时同时删除此栏目)',
  `is_can_add_sub` tinyint UNSIGNED NOT NULL DEFAULT 0 COMMENT '是否可以添加子栏目',
  `is_can_edit` tinyint UNSIGNED NOT NULL DEFAULT 1 COMMENT '是否可以修改',
  `is_can_sort` tinyint UNSIGNED NOT NULL DEFAULT 1 COMMENT '是否可以排序',
  `is_can_show_hidden` tinyint UNSIGNED NOT NULL DEFAULT 0 COMMENT '是否可以由管理员控制显示隐藏',
  `is_show_crumbs` tinyint UNSIGNED NOT NULL DEFAULT 1 COMMENT '是否在面包屑导航显示',
  `is_loop` tinyint UNSIGNED NOT NULL DEFAULT 1 COMMENT '是否在根据父级栏目遍历子栏目显示',
  `media1` varchar(500) NULL DEFAULT NULL COMMENT '视频',
  `is_deleted` tinyint UNSIGNED NOT NULL DEFAULT 0 COMMENT '是否已经删除',
  `icon_class` varchar(255) NULL DEFAULT NULL COMMENT '后台ICON样式名',
  `media2` varchar(500) NULL DEFAULT NULL COMMENT '视频',
  `media3` varchar(500) NULL DEFAULT NULL COMMENT '视频',
  `media4` varchar(500) NULL DEFAULT NULL COMMENT '视频',
  `is_system` tinyint UNSIGNED NULL DEFAULT 0 COMMENT '是否系统栏目',
  `published` tinyint UNSIGNED NOT NULL DEFAULT 0 COMMENT '是否已经发布静态页面',
  `website_public` tinyint NOT NULL DEFAULT 0 COMMENT '整站公共栏目,用于静态页发布时来判断是否全量发布',
  `create_by` varchar(255) NULL DEFAULT NULL,
  `create_date` datetime NULL DEFAULT NULL,
  `status` int NULL DEFAULT 0,
  `language_id` int NULL DEFAULT 0,
  `last_modified_by` varchar(255) NULL DEFAULT NULL,
  `last_modified_date` datetime NULL DEFAULT NULL,
  `version` int NULL DEFAULT 0,
  `nav_elite` datetime NULL DEFAULT NULL,
  `deleted` int NULL DEFAULT 0,
  `show_position` varchar(10) NULL DEFAULT NULL COMMENT '显示位置，1为顶部，2为右上侧，3为左侧，4为热度榜',
  `is_add_article` tinyint NOT NULL DEFAULT 0 COMMENT '是否允许前台会员添加文章',
  `is_can_add_article` tinyint NOT NULL DEFAULT 0 COMMENT '是否可以设置前台添加文章',
  `is_form` tinyint NOT NULL DEFAULT 0 COMMENT '是否为表单栏目',
  `depth` int NULL DEFAULT 0 COMMENT '节点深度',
  `form_captcha` TINYINT NOT NULL DEFAULT 0 COMMENT '表单提交验证码：1开启0关闭',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_oneshot_id_site_project`(`id` ASC, `site_project_id` ASC) USING BTREE,
  INDEX `idx_oneshot_site_project`(`site_project_id` ASC, `deleted` ASC) USING BTREE,
  INDEX `idx_oneshot_site_root`(`site_root_id` ASC, `deleted` ASC) USING BTREE,
  INDEX `idx_oneshot_deleted_sort`(`deleted` ASC, `sort` ASC, `id` ASC) USING BTREE,
  INDEX `idx_oneshot_parent_deleted`(`parent_id` ASC, `deleted` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb3 COLLATE = utf8mb3_general_ci ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of t_from_oneshot
-- ----------------------------

-- ----------------------------
-- Table structure for t_logs
-- ----------------------------
DROP TABLE IF EXISTS `t_logs`;
CREATE TABLE `t_logs`  (
  `id` int UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键编号',
  `action` varchar(100) NULL DEFAULT NULL COMMENT '事件',
  `request_data` longtext NULL,
  `user_id` int NULL DEFAULT NULL COMMENT '管理员编号',
  `ip` varchar(20) NULL DEFAULT NULL COMMENT 'ip地址',
  `create_time` datetime NULL DEFAULT NULL COMMENT '操作时间',
  `url` varchar(500) NULL DEFAULT NULL COMMENT '请求地址',
  `http_method` varchar(50) NULL DEFAULT NULL COMMENT '请求谓词类型',
  `class_method` varchar(255) NULL DEFAULT NULL COMMENT '请求方法',
  `spend_time` bigint NULL DEFAULT NULL COMMENT '响应时间单位毫秒',
  `response_data` longtext NULL,
  `user_name` varchar(255) NULL DEFAULT NULL COMMENT '管理员',
  `create_by` varchar(255) NULL DEFAULT NULL,
  `create_date` datetime NULL DEFAULT NULL,
  `is_deleted` int NULL DEFAULT 0,
  `status` int NULL DEFAULT 0,
  `language_id` int NULL DEFAULT 0,
  `last_modified_by` varchar(255) NULL DEFAULT NULL,
  `last_modified_date` datetime NULL DEFAULT NULL,
  `sort` int NULL DEFAULT 0,
  `version` int NULL DEFAULT 0,
  `deleted` int NULL DEFAULT 0,
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of t_logs
-- ----------------------------

-- ----------------------------
-- Table structure for t_logs_operation
-- ----------------------------
DROP TABLE IF EXISTS `t_logs_operation`;
CREATE TABLE `t_logs_operation`  (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '主键编号',
  `action` varchar(255) NOT NULL COMMENT '事件描述',
  `request_data` longtext NULL,
  `user_id` int NOT NULL COMMENT '管理员编号',
  `ip` varchar(255) NOT NULL COMMENT 'ip地址',
  `created` datetime NOT NULL COMMENT '操作时间',
  `response_data` longtext NULL,
  `user_name` varchar(255) NOT NULL COMMENT '管理员登录名',
  `class_id` int NOT NULL COMMENT '栏目ID',
  `info_id` int UNSIGNED NOT NULL DEFAULT 0 COMMENT '数据ID',
  `create_by` varchar(255) NULL DEFAULT NULL,
  `create_date` datetime NULL DEFAULT NULL,
  `is_deleted` int NULL DEFAULT 0,
  `status` int NULL DEFAULT 0,
  `language_id` int NULL DEFAULT 0,
  `last_modified_by` varchar(255) NULL DEFAULT NULL,
  `last_modified_date` datetime NULL DEFAULT NULL,
  `sort` int NULL DEFAULT 0,
  `version` int NULL DEFAULT 0,
  `deleted` int NULL DEFAULT 0,
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of t_logs_operation
-- ----------------------------

-- ----------------------------
-- Table structure for t_menu
-- ----------------------------
DROP TABLE IF EXISTS `t_menu`;
CREATE TABLE `t_menu`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '菜单ID',
  `parent_id` bigint NOT NULL DEFAULT 0 COMMENT '父菜单ID，0表示顶级菜单',
  `menu_key` varchar(100) NOT NULL COMMENT '菜单唯一标识（用于前端路由匹配）',
  `title` varchar(100) NOT NULL COMMENT '菜单标题',
  `path` varchar(255) NOT NULL COMMENT '路由路径',
  `icon` varchar(50) NULL DEFAULT NULL COMMENT '图标名称',
  `category` varchar(100) NULL DEFAULT NULL COMMENT '菜单分类',
  `sort_order` int NOT NULL DEFAULT 0 COMMENT '排序号',
  `visible` tinyint(1) NOT NULL DEFAULT 1 COMMENT '是否显示（0隐藏，1显示）',
  `enabled` tinyint(1) NOT NULL DEFAULT 1 COMMENT '是否启用（0禁用，1启用）',
  `requires_system_admin` tinyint(1) NOT NULL DEFAULT 0 COMMENT '是否需要系统管理员权限',
  `component_path` varchar(255) NULL DEFAULT NULL COMMENT '前端组件路径（用于动态路由）',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint(1) NOT NULL DEFAULT 0 COMMENT '删除标记',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_menu_key`(`menu_key` ASC) USING BTREE,
  INDEX `idx_parent_id`(`parent_id` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '系统菜单表' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of t_menu
-- ----------------------------
INSERT INTO `t_menu` VALUES (1, 0, 'workspace', '工作台', '/admin/v2', 'HomeFilled', '工作台', 10, 1, 1, 0, NULL, '2026-08-21 09:52:17', '2026-08-21 09:52:17', 0);
INSERT INTO `t_menu` VALUES (2, 0, 'site-intelligence', '站点智能管理', '/admin/v2/sites', 'Promotion', '站点智能管理', 20, 1, 1, 0, NULL, '2026-08-21 09:52:17', '2026-08-21 09:52:17', 0);
INSERT INTO `t_menu` VALUES (3, 0, 'site-service', '站点服务', '/admin/v2/site-service', 'Monitor', '站点服务', 30, 1, 1, 0, NULL, '2026-08-21 09:52:17', '2026-08-21 09:52:17', 0);
INSERT INTO `t_menu` VALUES (4, 0, 'cms', '站点内容', '/admin/v2/cms/content', 'Grid', '站点内容', 40, 1, 1, 0, NULL, '2026-08-21 09:52:17', '2026-08-21 09:52:17', 0);
INSERT INTO `t_menu` VALUES (5, 0, 'seo-marketing', '营销管理', '/admin/v2/seo/robots', 'TrendCharts', '营销管理', 50, 1, 1, 0, NULL, '2026-08-21 09:52:17', '2026-08-21 09:52:17', 0);
INSERT INTO `t_menu` VALUES (6, 0, 'security-compliance', '安全合规', '/admin/v2/sensitive-word', 'Shield', '安全合规', 60, 1, 1, 0, NULL, '2026-08-21 09:52:17', '2026-08-21 09:52:17', 0);
INSERT INTO `t_menu` VALUES (7, 0, 'templates-models', '模板与模型', '/admin/v2/cms/template-bindings', 'Files', '模板与模型', 70, 1, 1, 0, NULL, '2026-08-21 09:52:17', '2026-08-25 15:17:25', 1);
INSERT INTO `t_menu` VALUES (8, 0, 'system', '系统', '/admin/v2/system/access', 'Setting', '系统管理', 80, 1, 1, 0, NULL, '2026-08-21 09:52:17', '2026-08-21 09:52:17', 0);
INSERT INTO `t_menu` VALUES (9, 0, 'cms-platform', '内容中台', '/admin/v2/cms-platform/product-config', 'FolderOpened', '内容中台', 45, 1, 1, 0, NULL, '2026-08-27 09:04:29', '2026-08-27 09:04:29', 0);
INSERT INTO `t_menu` VALUES (101, 1, 'workspace-home', '工作台', '/admin/v2', 'House', '工作台', 101, 1, 1, 0, NULL, '2026-08-21 09:52:17', '2026-08-21 09:52:17', 0);
INSERT INTO `t_menu` VALUES (201, 2, 'site-management-v2', '站点管理', '/admin/v2/sites-v2', 'OfficeBuilding', '站点智能管理', 201, 1, 1, 1, NULL, '2026-08-21 09:52:18', '2026-08-21 09:52:18', 0);
INSERT INTO `t_menu` VALUES (202, 2, 'site-management', '新增站点', '/admin/v2/sites', 'OfficeBuilding', '站点智能管理', 202, 1, 1, 1, NULL, '2026-08-21 09:52:18', '2026-08-25 15:18:26', 1);
INSERT INTO `t_menu` VALUES (203, 2, 'headless-api-doc', 'Headless 接口文档', '/admin/v2/headless-api-doc', 'Document', '站点智能管理', 203, 1, 1, 0, NULL, '2026-08-21 09:52:18', '2026-08-21 09:52:18', 0);
INSERT INTO `t_menu` VALUES (204, 2, 'ai-config', 'AI 模型配置', '/admin/v2/ai/config', 'Connection', '站点智能管理', 204, 1, 1, 1, NULL, '2026-08-21 09:52:18', '2026-09-08 11:54:52', 1);
INSERT INTO `t_menu` VALUES (205, 2, 'ai-workbench', 'AI 工作台', '/admin/v2/ai/workbench', 'Promotion', '站点智能管理', 205, 1, 1, 0, NULL, '2026-08-21 09:52:18', '2026-08-21 09:57:52', 1);
INSERT INTO `t_menu` VALUES (206, 2, 'diagnostics', '绑定诊断', '/admin/v2/diagnostics', 'Monitor', '站点智能管理', 206, 1, 1, 0, NULL, '2026-08-21 09:52:18', '2026-08-21 09:57:55', 1);
INSERT INTO `t_menu` VALUES (207, 2, 'sample-capability', '样本能力', '/admin/v2/ai/sample-capability', 'DocumentCopy', '站点智能管理', 207, 1, 1, 0, NULL, '2026-08-21 09:52:18', '2026-08-21 09:57:57', 1);
INSERT INTO `t_menu` VALUES (208, 2, 'binding-efficiency', '绑定效率', '/admin/v2/ai/binding-efficiency', 'Compass', '站点智能管理', 208, 1, 1, 0, NULL, '2026-08-21 09:52:18', '2026-08-21 09:58:01', 1);
INSERT INTO `t_menu` VALUES (301, 3, 'site-service-main', '站点服务', '/admin/v2/site-service', 'Monitor', '站点服务', 301, 1, 1, 0, NULL, '2026-08-21 09:52:18', '2026-08-21 09:52:18', 0);
INSERT INTO `t_menu` VALUES (302, 3, 'site-logs', '站点日志', '/admin/v2/site-logs', 'Document', '站点服务', 302, 1, 1, 0, NULL, '2026-08-24 16:09:56', '2026-08-24 16:09:56', 0);
INSERT INTO `t_menu` VALUES (401, 4, 'cms-content-overview', '内容总览', '/admin/v2/cms/content', 'Grid', '站点内容', 401, 1, 1, 0, NULL, '2026-08-21 09:52:18', '2026-08-21 09:52:18', 0);
INSERT INTO `t_menu` VALUES (402, 4, 'cms-menus', '栏目管理', '/admin/v2/cms/menus', 'Collection', '站点内容', 402, 1, 1, 0, NULL, '2026-08-21 09:52:18', '2026-08-21 09:52:18', 0);
INSERT INTO `t_menu` VALUES (403, 4, 'content-management', '内容管理', '/admin/v2/cms/content-management', 'Document', '站点内容', 403, 1, 1, 0, NULL, '2026-08-21 09:52:18', '2026-08-21 09:52:18', 0);
INSERT INTO `t_menu` VALUES (404, 4, 'template-management', '模板管理', '/admin/v2/cms/template-management', 'Files', '站点内容', 404, 1, 1, 0, NULL, '2026-08-21 09:52:18', '2026-08-21 09:52:18', 0);
INSERT INTO `t_menu` VALUES (405, 4, 'static-publish', '静态页发布', '/admin/v2/cms/static-publish', 'Promotion', '站点内容', 405, 1, 1, 0, NULL, '2026-08-21 09:52:18', '2026-08-21 09:52:18', 0);
INSERT INTO `t_menu` VALUES (406, 4, 'cms-site-settings', '站点设置', '/admin/v2/cms/site-settings', 'Setting', '站点内容', 406, 1, 1, 0, NULL, '2026-08-27 14:07:05', '2026-08-27 14:07:05', 0);
INSERT INTO `t_menu` VALUES (407, 4, 'form-config', '表单设置', '/admin/v2/cms/form-config', 'Tickets', '站点内容', 407, 1, 1, 0, NULL, '2026-08-27 15:15:56', '2026-08-27 15:15:56', 0);
INSERT INTO `t_menu` VALUES (408, 4, 'form-center', '表单内容', '/admin/v2/cms/form-center', 'DocumentCopy', '站点内容', 408, 1, 1, 0, NULL, '2026-08-27 15:15:56', '2026-08-27 15:15:56', 0);
INSERT INTO `t_menu` VALUES (409, 4, 'schedule-publish', '定时发布', '/admin/v2/cms/schedule-publish', 'Timer', '站点内容', 409, 1, 1, 0, NULL, '2026-08-27 15:59:28', '2026-08-27 15:59:28', 0);
INSERT INTO `t_menu` VALUES (501, 5, 'seo-robots', 'Robots管理', '/admin/v2/seo/robots', 'Document', '营销管理', 501, 1, 1, 0, NULL, '2026-08-21 09:52:18', '2026-08-21 09:52:18', 0);
INSERT INTO `t_menu` VALUES (502, 5, 'seo-sitemap', 'SiteMap管理', '/admin/v2/seo/sitemap', 'MapLocation', '营销管理', 502, 1, 1, 0, NULL, '2026-08-21 09:52:18', '2026-08-21 09:52:18', 0);
INSERT INTO `t_menu` VALUES (503, 5, 'seo-hreflang', 'Hreflang管理', '/admin/v2/seo/hreflang', 'Link', '营销管理', 503, 1, 1, 0, NULL, '2026-08-21 09:52:18', '2026-08-21 09:52:18', 0);
INSERT INTO `t_menu` VALUES (504, 5, 'seo-geo', 'GEO管理', '/admin/v2/seo/geo', 'Location', '营销管理', 504, 1, 1, 0, NULL, '2026-08-21 09:52:18', '2026-08-21 09:52:18', 0);
INSERT INTO `t_menu` VALUES (505, 5, 'seo-meta', 'SEO管理', '/admin/v2/seo/meta', 'Search', '营销管理', 505, 1, 1, 0, NULL, '2026-08-21 09:52:18', '2026-08-21 09:52:18', 0);
INSERT INTO `t_menu` VALUES (601, 6, 'sensitive-word', '敏感词', '/admin/v2/sensitive-word', 'Warning', '安全合规', 601, 1, 1, 0, NULL, '2026-08-21 09:52:18', '2026-08-21 09:52:18', 0);
INSERT INTO `t_menu` VALUES (701, 7, 'template-bindings', '模板绑定', '/admin/v2/cms/template-bindings', 'Connection', '模板与模型', 701, 1, 1, 0, NULL, '2026-08-21 09:52:18', '2026-08-25 15:17:10', 1);
INSERT INTO `t_menu` VALUES (702, 7, 'models', '模型管理', '/admin/v2/cms/models', 'Files', '模板与模型', 702, 1, 1, 0, NULL, '2026-08-21 09:52:18', '2026-08-25 15:17:11', 1);
INSERT INTO `t_menu` VALUES (703, 7, 'fields', '字段管理', '/admin/v2/cms/fields', 'Document', '模板与模型', 703, 1, 1, 0, NULL, '2026-08-21 09:52:18', '2026-08-25 15:17:20', 1);
INSERT INTO `t_menu` VALUES (801, 8, 'i18n', '国际化管理', '/admin/v2/i18n', 'Operation', '系统管理', 801, 1, 1, 0, NULL, '2026-08-21 09:52:18', '2026-08-25 15:17:36', 1);
INSERT INTO `t_menu` VALUES (802, 8, 'resource-library', '资源库', '/admin/v2/tools/assets', 'FolderOpened', '系统管理', 802, 1, 1, 1, NULL, '2026-08-21 09:52:18', '2026-09-08 09:14:39', 1);
INSERT INTO `t_menu` VALUES (803, 8, 'system-access', '权限中心', '/admin/v2/system/access', 'Lock', '系统管理', 803, 1, 1, 0, NULL, '2026-08-21 09:52:18', '2026-08-25 15:17:40', 1);
INSERT INTO `t_menu` VALUES (804, 8, 'system-admin-groups', '管理员组管理', '/admin/v2/system/admin-groups', 'UserFilled', '系统管理', 804, 1, 1, 1, NULL, '2026-08-21 09:52:18', '2026-08-21 09:52:18', 0);
INSERT INTO `t_menu` VALUES (805, 8, 'system-admin-users', '管理员管理', '/admin/v2/system/admin-users', 'Avatar', '系统管理', 805, 1, 1, 1, NULL, '2026-08-21 09:52:18', '2026-08-21 09:52:18', 0);
INSERT INTO `t_menu` VALUES (806, 8, 'system-menus', '菜单管理', '/admin/v2/system/menus', 'Menu', '系统管理', 806, 1, 1, 1, NULL, '2026-08-21 09:52:18', '2026-08-21 09:52:18', 0);
INSERT INTO `t_menu` VALUES (807, 8, 'system-logs', '系统日志', '/admin/v2/system/logs', 'Document', '系统管理', 807, 1, 1, 1, NULL, '2026-08-21 09:52:18', '2026-08-21 09:52:18', 0);
INSERT INTO `t_menu` VALUES (808, 8, 'system-settings', '系统设置', '/admin/v2/system/settings', 'Setting', '系统管理', 810, 1, 1, 0, NULL, '2026-08-21 09:52:18', '2026-08-25 15:26:27', 0);
INSERT INTO `t_menu` VALUES (809, 8, 'operation-logs', '操作日志', '/admin/v2/system/operation-logs', 'Tickets', '系统管理', 808, 1, 1, 1, NULL, '2026-08-25 15:25:06', '2026-08-25 15:26:33', 0);

INSERT INTO `t_menu` VALUES (811, 8, 'system-languages', '语言配置', '/admin/v2/system/languages', 'Notebook', '系统管理', 810, 1, 1, 1, NULL, '2026-09-14 10:40:30', '2026-09-14 10:40:30', 0);
INSERT INTO `t_menu` VALUES (901, 9, 'cms-platform-product-config', '产品配置', '/admin/v2/cms-platform/product-config', 'Goods', '内容中台', 901, 1, 1, 0, NULL, '2026-08-27 09:04:34', '2026-08-27 09:04:34', 0);
INSERT INTO `t_menu` VALUES (902, 9, 'cms-platform-news-config', '新闻配置', '/admin/v2/cms-platform/news-config', 'News', '内容中台', 902, 1, 1, 0, NULL, '2026-08-27 09:04:34', '2026-08-27 09:04:34', 0);
INSERT INTO `t_menu` VALUES (903, 9, 'cms-platform-product-center', '产品中心', '/admin/v2/cms-platform/product-center', 'GoodsFilled', '内容中台', 903, 1, 1, 0, NULL, '2026-08-27 09:04:34', '2026-08-27 09:04:34', 0);
INSERT INTO `t_menu` VALUES (904, 9, 'cms-platform-news-center', '新闻中心', '/admin/v2/cms-platform/news-center', 'Newspaper', '内容中台', 904, 1, 1, 0, NULL, '2026-08-27 09:04:34', '2026-08-27 09:04:34', 0);
INSERT INTO `t_menu` VALUES (905, 9, 'cms-platform-static-assets', '资源库', '/admin/v2/cms-platform/static-assets', 'Folder', '内容中台', 905, 1, 1, 0, NULL, '2026-08-27 09:04:34', '2026-09-08 08:54:25', 0);
INSERT INTO `t_menu` VALUES (906, 9, 'cms-platform-ai-page', 'AI页面生成', '/admin/v2/cms-platform/ai-page', 'MagicStick', '内容中台', 906, 1, 1, 0, NULL, '2026-08-27 13:03:06', '2026-08-27 13:03:06', 0);





-- ----------------------------
-- Table structure for t_metas
-- ----------------------------
DROP TABLE IF EXISTS `t_metas`;
CREATE TABLE `t_metas`  (
  `mid` int UNSIGNED NOT NULL AUTO_INCREMENT,
  `name` varchar(200) NULL DEFAULT NULL,
  `slug` varchar(200) NULL DEFAULT NULL,
  `type` varchar(32) NOT NULL DEFAULT '',
  `content_type` varchar(32) NULL DEFAULT NULL,
  `description` varchar(200) NULL DEFAULT NULL,
  `sort` int UNSIGNED NULL DEFAULT 0,
  `parent` int UNSIGNED NULL DEFAULT 0,
  PRIMARY KEY (`mid`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of t_metas
-- ----------------------------

-- ----------------------------
-- Table structure for t_module
-- ----------------------------
DROP TABLE IF EXISTS `t_module`;
CREATE TABLE `t_module`  (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '主键',
  `title` varchar(255) NOT NULL COMMENT '模块名称',
  `photo` varchar(255) NULL DEFAULT NULL COMMENT '模块图片',
  `note` longtext NULL COMMENT '模块说明',
  `menu_ids` longtext NOT NULL COMMENT '关联栏目ID集合',
  `is_children` tinyint(1) NOT NULL COMMENT '是否子栏目',
  `can_add_menu_ids` longtext NOT NULL COMMENT '可以使用此模块的栏目集合',
  `create_time` datetime NOT NULL COMMENT '模块创建时间',
  `sort` int UNSIGNED NOT NULL DEFAULT 0 COMMENT '排序',
  `status` tinyint UNSIGNED NOT NULL DEFAULT 1 COMMENT '启用/禁用',
  `deleted` tinyint UNSIGNED NOT NULL DEFAULT 0 COMMENT '是否删除',
  `create_by` varchar(255) NULL DEFAULT NULL,
  `create_date` datetime NULL DEFAULT NULL,
  `is_deleted` int NULL DEFAULT 0,
  `language_id` int NULL DEFAULT 0,
  `last_modified_by` varchar(255) NULL DEFAULT NULL,
  `last_modified_date` datetime NULL DEFAULT NULL,
  `version` int NULL DEFAULT 0,
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of t_module
-- ----------------------------

-- ----------------------------
-- Table structure for t_module_menu
-- ----------------------------
DROP TABLE IF EXISTS `t_module_menu`;
CREATE TABLE `t_module_menu`  (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '主键',
  `class_name` varchar(500) NOT NULL COMMENT '栏目名称',
  `class_en_name` varchar(255) NULL DEFAULT NULL COMMENT '栏目英文名称',
  `create_time` datetime NOT NULL,
  `list_page_url` varchar(255) NULL DEFAULT NULL COMMENT '前台栏目页地址',
  `detail_page_url` varchar(255) NULL DEFAULT NULL COMMENT '前台内容页地址',
  `parent_id` int NOT NULL DEFAULT 0 COMMENT '父级ID',
  `sort` int NOT NULL DEFAULT 0 COMMENT '排序',
  `menu_field` longtext NULL COMMENT '单篇栏目字段',
  `identifies` varchar(255) NULL DEFAULT NULL COMMENT '唯一值',
  `model_field` longtext NULL COMMENT '列表栏目字段',
  `model_list` longtext NULL COMMENT '列表栏目列表显示字段',
  `model_search` longtext NULL COMMENT '列表栏目搜索字段',
  `setting` varchar(500) NULL DEFAULT NULL COMMENT '权限集合',
  `has_manage_channel` tinyint(1) NOT NULL DEFAULT 1 COMMENT '是否后台显示',
  `url` varchar(255) NULL DEFAULT NULL COMMENT '后台访问路径',
  `has_front_channel` tinyint(1) NOT NULL DEFAULT 1 COMMENT '是否前台显示',
  `page_type` int NOT NULL DEFAULT 0 COMMENT '页面类型 0单篇 1列表',
  `is_sub_menu` tinyint UNSIGNED NOT NULL DEFAULT 0 COMMENT '是否二级栏目',
  `is_can_deleted` tinyint UNSIGNED NOT NULL DEFAULT 0 COMMENT '是否可以删除',
  `is_can_add_sub` tinyint UNSIGNED NOT NULL DEFAULT 0 COMMENT '是否可以添加子栏目',
  `is_can_edit` tinyint UNSIGNED NOT NULL DEFAULT 1 COMMENT '是否可以修改',
  `is_can_sort` tinyint(1) NOT NULL DEFAULT 1 COMMENT '是否可以排序',
  `is_show_crumbs` tinyint UNSIGNED NOT NULL DEFAULT 1 COMMENT '是否在面包屑导航显示',
  `module_id` int NOT NULL COMMENT '模块ID',
  `is_module_menu` tinyint UNSIGNED NOT NULL DEFAULT 0 COMMENT '是否为模块基础栏目',
  `is_can_cascade_deleted` tinyint UNSIGNED NOT NULL DEFAULT 0 COMMENT '是否可以级联删除(当删除父栏目时同时删除此栏目)',
  `is_can_show_hidden` tinyint UNSIGNED NOT NULL DEFAULT 0 COMMENT '是否可以由管理员控制显示隐藏',
  `website_public` tinyint NOT NULL DEFAULT 0 COMMENT '整站公共栏目,用于静态页发布时来判断是否全量发布',
  `create_by` varchar(255) NULL DEFAULT NULL,
  `create_date` datetime NULL DEFAULT NULL,
  `is_deleted` int NULL DEFAULT 0,
  `status` int NULL DEFAULT 0,
  `language_id` int NULL DEFAULT 0,
  `last_modified_by` varchar(255) NULL DEFAULT NULL,
  `last_modified_date` datetime NULL DEFAULT NULL,
  `version` int NULL DEFAULT 0,
  `is_loop` tinyint UNSIGNED NULL DEFAULT 1,
  `hits` int UNSIGNED NOT NULL DEFAULT 0 COMMENT '访问次数',
  `published` tinyint UNSIGNED NOT NULL DEFAULT 0 COMMENT '是否已经发布静态页面',
  `deleted` int NULL DEFAULT 0,
  `is_add_article` bit(1) NULL DEFAULT NULL,
  `is_can_add_article` bit(1) NULL DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of t_module_menu
-- ----------------------------

-- ----------------------------
-- Table structure for t_platform_contents
-- ----------------------------
DROP TABLE IF EXISTS `t_platform_contents`;
CREATE TABLE `t_platform_contents`  (
  `id` int UNSIGNED NOT NULL AUTO_INCREMENT,
  `class_id` int NULL DEFAULT 0 COMMENT '分类编号',
  `title` varchar(500) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '标题',
  `tags` varchar(200) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '标签',
  `hits` int UNSIGNED NOT NULL DEFAULT 0 COMMENT '访问次数',
  `sort` int NOT NULL DEFAULT 0 COMMENT '排序号',
  `status` tinyint(1) NOT NULL DEFAULT 0 COMMENT '发布状态',
  `single_line1` varchar(500) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '单行文本',
  `single_line2` varchar(500) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '单行文本',
  `single_line3` varchar(500) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '单行文本',
  `single_line4` varchar(500) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '单行文本',
  `single_line5` varchar(500) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '单行文本',
  `single_line6` varchar(500) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '单行文本',
  `single_line7` varchar(500) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '单行文本',
  `single_line8` varchar(500) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '单行文本',
  `single_line9` varchar(500) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '单行文本',
  `single_line10` varchar(500) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '单行文本',
  `single_line11` varchar(500) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '单行文本',
  `single_line12` varchar(500) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '单行文本',
  `single_line13` varchar(500) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '单行文本',
  `single_line14` varchar(500) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '单行文本',
  `single_line15` varchar(500) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '单行文本',
  `single_line16` varchar(500) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '单行文本',
  `single_line17` varchar(500) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '单行文本',
  `single_line18` varchar(500) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '单行文本',
  `single_line19` varchar(500) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '单行文本',
  `single_line20` varchar(500) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '单行文本',
  `xgid` int NOT NULL DEFAULT 0 COMMENT '关联数据ID',
  `note1` mediumtext CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL COMMENT '多行文本',
  `note2` mediumtext CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL COMMENT '多行文本',
  `note3` mediumtext CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL COMMENT '多行文本',
  `note4` mediumtext CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL COMMENT '多行文本',
  `note5` mediumtext CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL COMMENT '多行文本',
  `note6` mediumtext CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL COMMENT '多行文本',
  `note7` mediumtext CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL COMMENT '多行文本',
  `note8` mediumtext CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL COMMENT '多行文本',
  `note9` mediumtext CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL COMMENT '多行文本',
  `note10` mediumtext CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL COMMENT '多行文本',
  `note11` mediumtext CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL COMMENT '多行文本',
  `note12` mediumtext CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL COMMENT '多行文本',
  `note13` mediumtext CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL COMMENT '多行文本',
  `note14` mediumtext CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL COMMENT '多行文本',
  `note15` mediumtext CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL COMMENT '多行文本',
  `photo1` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '图片',
  `photo2` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '图片',
  `photo3` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '图片',
  `photo4` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '图片',
  `photo5` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '图片',
  `photo6` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '图片',
  `photo7` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '图片',
  `photo8` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '图片',
  `photo9` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '图片',
  `photo10` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '图片',
  `photo11` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '图片',
  `photo12` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '图片',
  `photo13` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '图片',
  `photo14` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '图片',
  `photo15` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '图片',
  `seo_title` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT 'seo标题',
  `seo_keywords` mediumtext CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL COMMENT 'seo关键字',
  `seo_description` mediumtext CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL COMMENT 'seo描述',
  `media1` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '视频',
  `media2` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '视频',
  `media3` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '视频',
  `media4` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '视频',
  `file1` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '视频',
  `file2` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '视频',
  `file3` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '视频',
  `file4` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '视频',
  `content1` longtext NULL,
  `content2` longtext NULL,
  `content3` longtext NULL,
  `content4` longtext NULL,
  `content5` longtext NULL,
  `content6` longtext NULL,
  `index_top` tinyint UNSIGNED NOT NULL DEFAULT 0 COMMENT '首页置顶',
  `index_elite` tinyint UNSIGNED NOT NULL DEFAULT 0 COMMENT '首页推荐',
  `index_hot` tinyint UNSIGNED NOT NULL DEFAULT 0 COMMENT '首页热门',
  `channel_top` tinyint UNSIGNED NOT NULL DEFAULT 0 COMMENT '频道置顶',
  `channel_elite` tinyint UNSIGNED NOT NULL DEFAULT 0 COMMENT '频道推荐',
  `channel_hot` tinyint UNSIGNED NOT NULL DEFAULT 0 COMMENT '频道热门',
  `menu_top` tinyint UNSIGNED NOT NULL DEFAULT 0 COMMENT '栏目置顶',
  `menu_elite` tinyint UNSIGNED NOT NULL DEFAULT 0 COMMENT '栏目推荐',
  `menu_hot` tinyint UNSIGNED NOT NULL DEFAULT 0 COMMENT '栏目热门',
  `nav_elite` tinyint NOT NULL DEFAULT 0 COMMENT '导航推荐',
  `right_elite` tinyint UNSIGNED NOT NULL DEFAULT 0 COMMENT '右侧推荐',
  `left_elite` tinyint UNSIGNED NOT NULL DEFAULT 0 COMMENT '左侧推荐',
  `deleted` tinyint UNSIGNED NOT NULL DEFAULT 0 COMMENT '是否删除',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  `begin_time` datetime NULL DEFAULT NULL COMMENT '开始时间',
  `end_time` datetime NULL DEFAULT NULL COMMENT '结束时间',
  `create_user` varchar(100) NULL DEFAULT NULL,
  `delete_user` varchar(100) NULL DEFAULT NULL,
  `delete_time` datetime NULL DEFAULT NULL COMMENT '删除时间',
  `entry_into_force_time` datetime NULL DEFAULT CURRENT_TIMESTAMP COMMENT '生效时间',
  `expiration_time` datetime NULL DEFAULT '9999-12-31 23:59:00' COMMENT '失效时间',
  `published` tinyint NOT NULL DEFAULT 0 COMMENT '是否已经发布静态页面',
  `create_by` varchar(100) NULL DEFAULT NULL,
  `create_date` datetime NULL DEFAULT NULL,
  `is_deleted` int NULL DEFAULT 0,
  `language_id` int NULL DEFAULT 0,
  `last_modified_by` varchar(100) NULL DEFAULT NULL,
  `last_modified_date` datetime NULL DEFAULT NULL,
  `version` int NULL DEFAULT 0,
  `mhits` int NULL DEFAULT NULL,
  `publish` int NULL DEFAULT NULL,
  `data_type` int NULL DEFAULT NULL COMMENT '数据类型：1产品 2新闻 3案例 4解决方案',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_contents_class`(`class_id` ASC, `id` ASC) USING BTREE,
  INDEX `idx_contents_data_class`(`data_type` ASC, `class_id` ASC, `deleted` ASC, `sort` ASC, `id` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of t_platform_contents
-- ----------------------------

-- ----------------------------
-- Table structure for t_platform_oneshot
-- ----------------------------
DROP TABLE IF EXISTS `t_platform_oneshot`;
CREATE TABLE `t_platform_oneshot`  (
  `id` int UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `site_project_id` bigint NULL DEFAULT NULL,
  `site_root_id` int NULL DEFAULT NULL,
  `class_name` varchar(64) NOT NULL COMMENT '栏目名称',
  `class_en_name` varchar(100) NULL DEFAULT NULL COMMENT '栏目英文名称',
  `single_line1` varchar(255) NULL DEFAULT NULL COMMENT '标题',
  `single_line2` varchar(255) NULL DEFAULT NULL,
  `single_line3` varchar(255) NULL DEFAULT NULL,
  `single_line4` varchar(255) NULL DEFAULT NULL,
  `single_line5` varchar(255) NULL DEFAULT NULL,
  `single_line6` varchar(255) NULL DEFAULT NULL,
  `single_line7` varchar(255) NULL DEFAULT NULL,
  `single_line8` varchar(255) NULL DEFAULT NULL,
  `single_line9` varchar(255) NULL DEFAULT NULL,
  `single_line10` varchar(255) NULL DEFAULT NULL,
  `single_line11` varchar(255) NULL DEFAULT NULL,
  `single_line12` varchar(255) NULL DEFAULT NULL,
  `single_line13` varchar(255) NULL DEFAULT NULL,
  `single_line14` varchar(255) NULL DEFAULT NULL,
  `single_line15` varchar(255) NULL DEFAULT NULL,
  `single_line16` varchar(255) NULL DEFAULT NULL,
  `single_line17` varchar(255) NULL DEFAULT NULL,
  `single_line18` varchar(255) NULL DEFAULT NULL,
  `single_line19` varchar(255) NULL DEFAULT NULL,
  `single_line20` varchar(255) NULL DEFAULT NULL,
  `single_line21` varchar(255) NULL DEFAULT NULL,
  `single_line22` varchar(255) NULL DEFAULT NULL,
  `single_line23` varchar(255) NULL DEFAULT NULL,
  `single_line24` varchar(255) NULL DEFAULT NULL,
  `single_line25` varchar(255) NULL DEFAULT NULL,
  `single_line26` varchar(255) NULL DEFAULT NULL,
  `single_line27` varchar(255) NULL DEFAULT NULL,
  `single_line28` varchar(255) NULL DEFAULT NULL,
  `single_line29` varchar(255) NULL DEFAULT NULL,
  `single_line30` varchar(255) NULL DEFAULT NULL,
  `note1` mediumtext NULL,
  `note2` mediumtext NULL,
  `note3` mediumtext NULL,
  `note4` mediumtext NULL,
  `note5` mediumtext NULL,
  `note6` mediumtext NULL,
  `note7` mediumtext NULL,
  `note8` mediumtext NULL,
  `note9` mediumtext NULL,
  `note10` mediumtext NULL,
  `note11` mediumtext NULL,
  `note12` mediumtext NULL,
  `note13` mediumtext NULL,
  `note14` mediumtext NULL,
  `note15` mediumtext NULL,
  `photo1` varchar(255) NULL DEFAULT NULL,
  `photo2` varchar(255) NULL DEFAULT NULL,
  `photo3` varchar(255) NULL DEFAULT NULL,
  `photo4` varchar(255) NULL DEFAULT NULL,
  `photo5` varchar(255) NULL DEFAULT NULL,
  `photo6` varchar(255) NULL DEFAULT NULL,
  `photo7` varchar(255) NULL DEFAULT NULL,
  `photo8` varchar(255) NULL DEFAULT NULL,
  `photo9` varchar(255) NULL DEFAULT NULL,
  `photo10` varchar(255) NULL DEFAULT NULL,
  `photo11` varchar(255) NULL DEFAULT NULL,
  `photo12` varchar(255) NULL DEFAULT NULL,
  `photo13` varchar(255) NULL DEFAULT NULL,
  `photo14` varchar(255) NULL DEFAULT NULL,
  `photo15` varchar(255) NULL DEFAULT NULL,
  `create_time` datetime NULL DEFAULT NULL,
  `begin_time` datetime NULL DEFAULT NULL,
  `end_time` datetime NULL DEFAULT NULL,
  `file1` varchar(255) NULL DEFAULT NULL COMMENT '附件',
  `file2` varchar(255) NULL DEFAULT NULL COMMENT '附件',
  `file3` varchar(255) NULL DEFAULT NULL COMMENT '附件',
  `file4` varchar(255) NULL DEFAULT NULL COMMENT '附件',
  `content1` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL,
  `content2` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL,
  `content3` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL,
  `content4` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL,
  `content5` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL,
  `content6` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL,
  `list_page_url` varchar(255) NULL DEFAULT NULL COMMENT '前台栏目页地址',
  `list_page_template` varchar(255) NULL DEFAULT NULL COMMENT '前台列表页模板',
  `detail_page_url` varchar(255) NULL DEFAULT NULL COMMENT '前台内容页地址',
  `detail_page_template` varchar(255) NULL DEFAULT NULL COMMENT '前台内容页模板',
  `seo_title` varchar(255) NULL DEFAULT NULL COMMENT 'seo标题',
  `seo_keywords` mediumtext NULL COMMENT 'seo关键字',
  `seo_description` mediumtext NULL COMMENT 'seo描述',
  `parent_id` int NOT NULL DEFAULT 0 COMMENT '父级ID',
  `sort` int NOT NULL DEFAULT 0 COMMENT '排序',
  `menu_field` mediumtext NULL COMMENT '单篇栏目字段',
  `identifies` varchar(64) NULL DEFAULT NULL COMMENT '唯一值',
  `model_field` mediumtext NULL COMMENT '列表栏目字段',
  `model_list` mediumtext NULL COMMENT '列表栏目列表显示字段',
  `model_search` mediumtext NULL COMMENT '列表栏目搜索字段',
  `setting` varchar(100) NULL DEFAULT NULL COMMENT '权限集合',
  `has_manage_channel` tinyint(1) NOT NULL DEFAULT 1 COMMENT '是否后台显示',
  `url` varchar(255) NULL DEFAULT NULL COMMENT '后台访问路径',
  `has_front_channel` tinyint(1) NOT NULL DEFAULT 1 COMMENT '是否前台显示',
  `page_type` int NOT NULL DEFAULT 0 COMMENT '页面类型 0单篇 1列表',
  `hits` int UNSIGNED NULL DEFAULT 0 COMMENT '访问次数',
  `is_sub_menu` tinyint UNSIGNED NOT NULL DEFAULT 0 COMMENT '是否二级栏目/在父栏目列表显示跳转列',
  `parent_list_filter_field` varchar(64) NULL DEFAULT NULL COMMENT '从父栏目跳转进入本栏目时用于筛选的字段名(模型字段 fieldName)',
  `is_can_deleted` tinyint UNSIGNED NOT NULL DEFAULT 0 COMMENT '是否可以删除',
  `is_module_menu` tinyint UNSIGNED NOT NULL DEFAULT 0 COMMENT '是否为模块基础栏目',
  `is_can_cascade_deleted` tinyint UNSIGNED NOT NULL DEFAULT 0 COMMENT '是否可以级联删除(当删除父栏目时同时删除此栏目)',
  `is_can_add_sub` tinyint UNSIGNED NOT NULL DEFAULT 0 COMMENT '是否可以添加子栏目',
  `is_can_edit` tinyint UNSIGNED NOT NULL DEFAULT 1 COMMENT '是否可以修改',
  `is_can_sort` tinyint UNSIGNED NOT NULL DEFAULT 1 COMMENT '是否可以排序',
  `is_can_show_hidden` tinyint UNSIGNED NOT NULL DEFAULT 0 COMMENT '是否可以由管理员控制显示隐藏',
  `is_show_crumbs` tinyint UNSIGNED NOT NULL DEFAULT 1 COMMENT '是否在面包屑导航显示',
  `is_loop` tinyint UNSIGNED NOT NULL DEFAULT 1 COMMENT '是否在根据父级栏目遍历子栏目显示',
  `is_deleted` tinyint UNSIGNED NOT NULL DEFAULT 0 COMMENT '是否已经删除',
  `icon_class` varchar(255) NULL DEFAULT NULL COMMENT '后台ICON样式名',
  `media1` varchar(255) NULL DEFAULT NULL COMMENT '视频',
  `media2` varchar(255) NULL DEFAULT NULL COMMENT '视频',
  `media3` varchar(255) NULL DEFAULT NULL COMMENT '视频',
  `media4` varchar(255) NULL DEFAULT NULL COMMENT '视频',
  `is_system` tinyint UNSIGNED NULL DEFAULT 0 COMMENT '是否系统栏目',
  `published` tinyint UNSIGNED NOT NULL DEFAULT 0 COMMENT '是否已经发布静态页面',
  `website_public` tinyint NOT NULL DEFAULT 0 COMMENT '整站公共栏目,用于静态页发布时来判断是否全量发布',
  `create_by` varchar(255) NULL DEFAULT NULL,
  `create_date` datetime NULL DEFAULT NULL,
  `status` int NULL DEFAULT 0,
  `language_id` int NULL DEFAULT 0,
  `last_modified_by` varchar(255) NULL DEFAULT NULL,
  `last_modified_date` datetime NULL DEFAULT NULL,
  `version` int NULL DEFAULT 0,
  `nav_elite` datetime NULL DEFAULT NULL,
  `deleted` int NULL DEFAULT 0,
  `show_position` varchar(10) NULL DEFAULT NULL COMMENT '显示位置，1为顶部，2为右上侧，3为左侧，4为热度榜',
  `is_add_article` tinyint NOT NULL DEFAULT 0 COMMENT '是否允许前台会员添加文章',
  `is_can_add_article` tinyint NOT NULL DEFAULT 0 COMMENT '是否可以设置前台添加文章',
  `is_form` tinyint NOT NULL DEFAULT 0 COMMENT '是否为表单栏目',
  `depth` int NULL DEFAULT 0 COMMENT '节点深度',
  `data_type` int NULL DEFAULT NULL COMMENT '数据类型：1产品 2新闻 3案例 4解决方案',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_oneshot_id_site_project`(`id` ASC, `site_project_id` ASC) USING BTREE,
  INDEX `idx_oneshot_site_project`(`site_project_id` ASC, `deleted` ASC) USING BTREE,
  INDEX `idx_oneshot_site_root`(`site_root_id` ASC, `deleted` ASC) USING BTREE,
  INDEX `idx_oneshot_deleted_sort`(`deleted` ASC, `sort` ASC, `id` ASC) USING BTREE,
  INDEX `idx_oneshot_parent_deleted`(`parent_id` ASC, `deleted` ASC) USING BTREE,
  INDEX `idx_oneshot_data_parent`(`data_type` ASC, `parent_id` ASC, `is_deleted` ASC, `sort` ASC, `id` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb3 COLLATE = utf8mb3_general_ci ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of t_platform_oneshot
-- ----------------------------



-- ----------------------------
-- Table structure for t_oneshot
-- ----------------------------
DROP TABLE IF EXISTS `t_oneshot`;
CREATE TABLE `t_oneshot`  (
  `extension_fields` JSON NULL COMMENT '新增业务字段值',
  `id` int UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `site_project_id` bigint NULL DEFAULT NULL,
  `site_root_id` int NULL DEFAULT NULL,
  `class_name` varchar(64) NOT NULL COMMENT '栏目名称',
  `class_en_name` varchar(100) NULL DEFAULT NULL COMMENT '栏目英文名称',
  `single_line1` varchar(255) NULL DEFAULT NULL COMMENT '标题',
  `single_line2` varchar(255) NULL DEFAULT NULL,
  `single_line3` varchar(255) NULL DEFAULT NULL,
  `single_line4` varchar(255) NULL DEFAULT NULL,
  `single_line5` varchar(255) NULL DEFAULT NULL,
  `single_line6` varchar(255) NULL DEFAULT NULL,
  `single_line7` varchar(255) NULL DEFAULT NULL,
  `single_line8` varchar(255) NULL DEFAULT NULL,
  `single_line9` varchar(255) NULL DEFAULT NULL,
  `single_line10` varchar(255) NULL DEFAULT NULL,
  `single_line11` varchar(255) NULL DEFAULT NULL,
  `single_line12` varchar(255) NULL DEFAULT NULL,
  `single_line13` varchar(255) NULL DEFAULT NULL,
  `single_line14` varchar(255) NULL DEFAULT NULL,
  `single_line15` varchar(255) NULL DEFAULT NULL,
  `single_line16` varchar(255) NULL DEFAULT NULL,
  `single_line17` varchar(255) NULL DEFAULT NULL,
  `single_line18` varchar(255) NULL DEFAULT NULL,
  `single_line19` varchar(255) NULL DEFAULT NULL,
  `single_line20` varchar(255) NULL DEFAULT NULL,
  `single_line21` varchar(255) NULL DEFAULT NULL,
  `single_line22` varchar(255) NULL DEFAULT NULL,
  `single_line23` varchar(255) NULL DEFAULT NULL,
  `single_line24` varchar(255) NULL DEFAULT NULL,
  `single_line25` varchar(255) NULL DEFAULT NULL,
  `single_line26` varchar(255) NULL DEFAULT NULL,
  `single_line27` varchar(255) NULL DEFAULT NULL,
  `single_line28` varchar(255) NULL DEFAULT NULL,
  `single_line29` varchar(255) NULL DEFAULT NULL,
  `single_line30` varchar(255) NULL DEFAULT NULL,
  `note1` mediumtext NULL,
  `note2` mediumtext NULL,
  `note3` mediumtext NULL,
  `note4` mediumtext NULL,
  `note5` mediumtext NULL,
  `note6` mediumtext NULL,
  `note7` mediumtext NULL,
  `note8` mediumtext NULL,
  `note9` mediumtext NULL,
  `note10` mediumtext NULL,
  `note11` mediumtext NULL,
  `note12` mediumtext NULL,
  `note13` mediumtext NULL,
  `note14` mediumtext NULL,
  `note15` mediumtext NULL,
  `photo1` varchar(255) NULL DEFAULT NULL,
  `photo2` varchar(255) NULL DEFAULT NULL,
  `photo3` varchar(255) NULL DEFAULT NULL,
  `photo4` varchar(255) NULL DEFAULT NULL,
  `photo5` varchar(255) NULL DEFAULT NULL,
  `photo6` varchar(255) NULL DEFAULT NULL,
  `photo7` varchar(255) NULL DEFAULT NULL,
  `photo8` varchar(255) NULL DEFAULT NULL,
  `photo9` varchar(255) NULL DEFAULT NULL,
  `photo10` varchar(255) NULL DEFAULT NULL,
  `photo11` varchar(255) NULL DEFAULT NULL,
  `photo12` varchar(255) NULL DEFAULT NULL,
  `photo13` varchar(255) NULL DEFAULT NULL,
  `photo14` varchar(255) NULL DEFAULT NULL,
  `photo15` varchar(255) NULL DEFAULT NULL,
  `create_time` datetime NULL DEFAULT NULL,
  `begin_time` datetime NULL DEFAULT NULL,
  `end_time` datetime NULL DEFAULT NULL,
  `file1` varchar(255) NULL DEFAULT NULL COMMENT '附件',
  `file2` varchar(255) NULL DEFAULT NULL COMMENT '附件',
  `file3` varchar(255) NULL DEFAULT NULL COMMENT '附件',
  `file4` varchar(255) NULL DEFAULT NULL COMMENT '附件',
  `content1` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL,
  `content2` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL,
  `content3` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL,
  `content4` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL,
  `content5` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL,
  `content6` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL,
  `list_page_url` varchar(255) NULL DEFAULT NULL COMMENT '前台栏目页地址',
  `list_page_template` varchar(255) NULL DEFAULT NULL COMMENT '前台列表页模板',
  `detail_page_url` varchar(255) NULL DEFAULT NULL COMMENT '前台内容页地址',
  `detail_page_template` varchar(255) NULL DEFAULT NULL COMMENT '前台内容页模板',
  `seo_title` varchar(255) NULL DEFAULT NULL COMMENT 'seo标题',
  `seo_keywords` mediumtext NULL COMMENT 'seo关键字',
  `seo_description` mediumtext NULL COMMENT 'seo描述',
  `parent_id` int NOT NULL DEFAULT 0 COMMENT '父级ID',
  `sort` int NOT NULL DEFAULT 0 COMMENT '排序',
  `menu_field` mediumtext NULL COMMENT '单篇栏目字段',
  `identifies` varchar(64) NULL DEFAULT NULL COMMENT '唯一值',
  `model_field` mediumtext NULL COMMENT '列表栏目字段',
  `model_list` mediumtext NULL COMMENT '列表栏目列表显示字段',
  `model_search` mediumtext NULL COMMENT '列表栏目搜索字段',
  `setting` varchar(100) NULL DEFAULT NULL COMMENT '权限集合',
  `has_manage_channel` tinyint(1) NOT NULL DEFAULT 1 COMMENT '是否后台显示',
  `url` varchar(255) NULL DEFAULT NULL COMMENT '后台访问路径',
  `has_front_channel` tinyint(1) NOT NULL DEFAULT 1 COMMENT '是否前台显示',
  `page_type` int NOT NULL DEFAULT 0 COMMENT '页面类型 0单篇 1列表',
  `hits` int UNSIGNED NULL DEFAULT 0 COMMENT '访问次数',
  `is_sub_menu` tinyint UNSIGNED NOT NULL DEFAULT 0 COMMENT '是否二级栏目/在父栏目列表显示跳转列',
  `parent_list_filter_field` varchar(64) NULL DEFAULT NULL COMMENT '从父栏目跳转进入本栏目时用于筛选的字段名(模型字段 fieldName)',
  `is_can_deleted` tinyint UNSIGNED NOT NULL DEFAULT 0 COMMENT '是否可以删除',
  `is_module_menu` tinyint UNSIGNED NOT NULL DEFAULT 0 COMMENT '是否为模块基础栏目',
  `is_can_cascade_deleted` tinyint UNSIGNED NOT NULL DEFAULT 0 COMMENT '是否可以级联删除(当删除父栏目时同时删除此栏目)',
  `is_can_add_sub` tinyint UNSIGNED NOT NULL DEFAULT 0 COMMENT '是否可以添加子栏目',
  `is_can_edit` tinyint UNSIGNED NOT NULL DEFAULT 1 COMMENT '是否可以修改',
  `is_can_sort` tinyint UNSIGNED NOT NULL DEFAULT 1 COMMENT '是否可以排序',
  `is_can_show_hidden` tinyint UNSIGNED NOT NULL DEFAULT 0 COMMENT '是否可以由管理员控制显示隐藏',
  `is_show_crumbs` tinyint UNSIGNED NOT NULL DEFAULT 1 COMMENT '是否在面包屑导航显示',
  `is_loop` tinyint UNSIGNED NOT NULL DEFAULT 1 COMMENT '是否在根据父级栏目遍历子栏目显示',
  `is_deleted` tinyint UNSIGNED NOT NULL DEFAULT 0 COMMENT '是否已经删除',
  `icon_class` varchar(255) NULL DEFAULT NULL COMMENT '后台ICON样式名',
  `media1` varchar(255) NULL DEFAULT NULL COMMENT '视频',
  `media2` varchar(255) NULL DEFAULT NULL COMMENT '视频',
  `media3` varchar(255) NULL DEFAULT NULL COMMENT '视频',
  `media4` varchar(255) NULL DEFAULT NULL COMMENT '视频',
  `is_system` tinyint UNSIGNED NULL DEFAULT 0 COMMENT '是否系统栏目',
  `published` tinyint UNSIGNED NOT NULL DEFAULT 0 COMMENT '是否已经发布静态页面',
  `website_public` tinyint NOT NULL DEFAULT 0 COMMENT '整站公共栏目,用于静态页发布时来判断是否全量发布',
  `create_by` varchar(255) NULL DEFAULT NULL,
  `create_date` datetime NULL DEFAULT NULL,
  `status` int NULL DEFAULT 0,
  `language_id` int NULL DEFAULT 0,
  `last_modified_by` varchar(255) NULL DEFAULT NULL,
  `last_modified_date` datetime NULL DEFAULT NULL,
  `version` int NULL DEFAULT 0,
  `nav_elite` datetime NULL DEFAULT NULL,
  `deleted` int NULL DEFAULT 0,
  `show_position` varchar(10) NULL DEFAULT NULL COMMENT '显示位置，1为顶部，2为右上侧，3为左侧，4为热度榜',
  `is_add_article` tinyint NOT NULL DEFAULT 0 COMMENT '是否允许前台会员添加文章',
  `is_can_add_article` tinyint NOT NULL DEFAULT 0 COMMENT '是否可以设置前台添加文章',
  `is_form` tinyint NOT NULL DEFAULT 0 COMMENT '是否为表单栏目',
  `biz_code` varchar(10) NULL DEFAULT NULL COMMENT '业务编码：新增栏目时默认取自身id，多站点按(biz_code,site_project_id)定位',
  `platform_source_ref` varchar(50) NULL DEFAULT NULL COMMENT '内容中台推送来源标识：<dataType>:<categoryId>，用于推送时判重',
  `public_search_text` LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL COMMENT '公开搜索清洗文本投影',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_oneshot_id_site_project`(`id` ASC, `site_project_id` ASC) USING BTREE,
  INDEX `idx_oneshot_site_project`(`site_project_id` ASC, `deleted` ASC) USING BTREE,
  INDEX `idx_oneshot_site_root`(`site_root_id` ASC, `deleted` ASC) USING BTREE,
  INDEX `idx_oneshot_deleted_sort`(`deleted` ASC, `sort` ASC, `id` ASC) USING BTREE,
  INDEX `idx_oneshot_parent_deleted`(`parent_id` ASC, `deleted` ASC) USING BTREE,
  INDEX `idx_oneshot_platform_ref`(`site_project_id` ASC, `platform_source_ref` ASC) USING BTREE,
  INDEX `idx_oneshot_site_biz_front` (`site_project_id`, `biz_code`, `deleted`, `is_system`, `has_front_channel`, `sort`, `id`) USING BTREE,
  INDEX `idx_oneshot_parent_front` (`parent_id`, `is_loop`, `deleted`, `is_system`, `has_front_channel`, `sort`, `id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb3 COLLATE = utf8mb3_general_ci ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of t_oneshot
-- ----------------------------

-- ----------------------------
-- Table structure for t_options
-- ----------------------------
DROP TABLE IF EXISTS `t_options`;
CREATE TABLE `t_options`  (
  `name` varchar(32) NOT NULL DEFAULT '',
  `value` varchar(1000) NULL DEFAULT '',
  `description` varchar(200) NULL DEFAULT NULL,
  `id` int NOT NULL,
  `create_by` varchar(255) NULL DEFAULT NULL,
  `create_date` datetime NULL DEFAULT NULL,
  `is_deleted` int NULL DEFAULT 0,
  `status` int NULL DEFAULT 0,
  `language_id` int NULL DEFAULT 0,
  `last_modified_by` varchar(255) NULL DEFAULT NULL,
  `last_modified_date` datetime NULL DEFAULT NULL,
  `sort` int NULL DEFAULT 0,
  `version` int NULL DEFAULT 0,
  `deleted` int NULL DEFAULT 0,
  PRIMARY KEY (`name`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of t_options
-- ----------------------------

















































-- ----------------------------
-- Table structure for t_relationships
-- ----------------------------
DROP TABLE IF EXISTS `t_relationships`;
CREATE TABLE `t_relationships`  (
  `cid` int UNSIGNED NOT NULL,
  `mid` int UNSIGNED NOT NULL,
  PRIMARY KEY (`cid`, `mid`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of t_relationships
-- ----------------------------

-- ----------------------------
-- Table structure for t_schedule_publish
-- ----------------------------
DROP TABLE IF EXISTS `t_schedule_publish`;
CREATE TABLE `t_schedule_publish`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `site_id` bigint NULL DEFAULT NULL COMMENT '站点ID（站点隔离）',
  `cron_expression` varchar(64) NULL DEFAULT NULL COMMENT '发布 cron 表达式',
  `cron_description` varchar(255) NULL DEFAULT NULL COMMENT 'cron 描述',
  `column_ids` text NULL COMMENT '发布栏目范围（栏目id JSON 数组）',
  `column_names` varchar(1024) NULL DEFAULT NULL COMMENT '发布栏目范围显示快照',
  `remark` varchar(500) NULL DEFAULT NULL COMMENT '备注',
  `enabled` tinyint(1) NOT NULL DEFAULT 1 COMMENT '生效状态 0关闭 1开启',
  `deleted` tinyint(1) NOT NULL DEFAULT 0 COMMENT '是否删除',
  `create_time` datetime NULL DEFAULT NULL COMMENT '创建时间',
  `update_time` datetime NULL DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_schedule_site`(`site_id` ASC, `deleted` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb3 COLLATE = utf8mb3_general_ci ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of t_schedule_publish
-- ----------------------------

-- ----------------------------
-- Table structure for t_schedule_publish_history
-- ----------------------------
DROP TABLE IF EXISTS `t_schedule_publish_history`;
CREATE TABLE `t_schedule_publish_history`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `schedule_id` bigint NOT NULL COMMENT '定时发布配置ID',
  `run_time` datetime NULL DEFAULT NULL COMMENT '执行时间',
  `status` varchar(32) NULL DEFAULT NULL COMMENT '状态 success/failed/pending',
  `message` varchar(500) NULL DEFAULT NULL COMMENT '执行信息',
  `deleted` tinyint(1) NOT NULL DEFAULT 0 COMMENT '是否删除',
  `create_time` datetime NULL DEFAULT NULL COMMENT '创建时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_schedule_history`(`schedule_id` ASC, `deleted` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb3 COLLATE = utf8mb3_general_ci ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of t_schedule_publish_history
-- ----------------------------

-- ----------------------------
-- Table structure for t_user_site_group
-- ----------------------------
DROP TABLE IF EXISTS `t_user_site_group`;
CREATE TABLE `t_user_site_group`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` int NOT NULL COMMENT '用户id(t_users.id)',
  `site_id` bigint NOT NULL COMMENT '站点id(cms_ai_site_project.id)',
  `group_id` int NOT NULL COMMENT '管理员组id(t_usergroups.id, 须属于该站点)',
  `created` datetime NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_user_site_group`(`user_id` ASC, `site_id` ASC, `group_id` ASC) USING BTREE,
  INDEX `idx_site_id`(`site_id` ASC) USING BTREE,
  INDEX `idx_group_id`(`group_id` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '用户-站点-管理员组关联' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of t_user_site_group
-- ----------------------------

-- ----------------------------
-- Table structure for t_usergroups
-- ----------------------------
DROP TABLE IF EXISTS `t_usergroups`;
CREATE TABLE `t_usergroups`  (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '用户组ID',
  `title` varchar(255) NOT NULL COMMENT '用户组名',
  `parent_id` int UNSIGNED NOT NULL DEFAULT 0 COMMENT '父级ID',
  `note` longtext NULL COMMENT '备注信息',
  `enabled` tinyint UNSIGNED NOT NULL DEFAULT 1 COMMENT '是否锁定',
  `deleted` tinyint(1) NOT NULL DEFAULT 0 COMMENT '是否删除',
  `group_setting` longtext NULL COMMENT '栏目权限',
  `other_setting` longtext NULL COMMENT '栏目按钮权限',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `create_by` varchar(255) NULL DEFAULT NULL,
  `create_date` datetime NULL DEFAULT NULL,
  `is_deleted` int NULL DEFAULT 0,
  `status` int NULL DEFAULT 0,
  `language_id` int NULL DEFAULT 0,
  `last_modified_by` varchar(255) NULL DEFAULT NULL,
  `last_modified_date` datetime NULL DEFAULT NULL,
  `sort` int NULL DEFAULT 0,
  `version` int NULL DEFAULT 0,
  `site_id` bigint NULL DEFAULT NULL COMMENT '所属站点(cms_ai_site_project.id)',
  `menu_setting` text NULL COMMENT '菜单权限配置（JSON 格式，存储选中的菜单 ID 列表\r\n        ）',
  `platform_setting` longtext NULL COMMENT '数据中台类别权限（逗号分隔键 类型:类别id，如 product:12,news:3）',
  `platform_other_setting` longtext NULL COMMENT '数据中台操作权限（分号分段，与 platform_setting 逐位对齐；段内逗号分隔操作id：1新增/2编辑/3删除/4查看/5存为草稿/6存为发布/7提交审核/8审批通过/9置顶/10推荐/11一键分发）',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_usergroups_site_id`(`site_id` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of t_usergroups
-- ----------------------------







-- ----------------------------
-- Table structure for t_users
-- ----------------------------
DROP TABLE IF EXISTS `t_users`;
CREATE TABLE `t_users`  (
  `id` int UNSIGNED NOT NULL AUTO_INCREMENT,
  `username` varchar(32) NOT NULL COMMENT '登录名',
  `password` varchar(255) NOT NULL COMMENT '登录密码',
  `email` varchar(200) NULL DEFAULT NULL COMMENT '邮箱',
  `home_url` varchar(200) NULL DEFAULT NULL,
  `screen_name` varchar(32) NULL DEFAULT NULL COMMENT '真实姓名',
  `created` int UNSIGNED NULL DEFAULT 0 COMMENT '创建时间',
  `activated` int UNSIGNED NULL DEFAULT 0 COMMENT '是否锁定',
  `logged` int UNSIGNED NULL DEFAULT 0 COMMENT '是否登录',
  `group_name` varchar(16) NULL DEFAULT 'visitor' COMMENT '管理员组名',
  `last_login_time` datetime NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '最后登录时间',
  `login_times` int UNSIGNED NOT NULL DEFAULT 0 COMMENT '累计登录次数',
  `group_id` int UNSIGNED NOT NULL DEFAULT 0 COMMENT '管理员组ID',
  `last_login_ip` varchar(255) NULL DEFAULT NULL COMMENT '最后登录IP地址',
  `create_by` varchar(255) NULL DEFAULT NULL,
  `create_date` datetime NULL DEFAULT NULL,
  `is_deleted` int NULL DEFAULT 0,
  `status` int NULL DEFAULT 0,
  `language_id` int NULL DEFAULT 0,
  `last_modified_by` varchar(255) NULL DEFAULT NULL,
  `last_modified_date` datetime NULL DEFAULT NULL,
  `sort` int NULL DEFAULT 0,
  `version` int NULL DEFAULT 0,
  `deleted` int NULL DEFAULT 0,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_t_users_username` (`username`)
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of t_users
-- ----------------------------






-- ----------------------------
-- Table structure for t_visit_browser
-- ----------------------------
DROP TABLE IF EXISTS `t_visit_browser`;
CREATE TABLE `t_visit_browser`  (
  `id` int NOT NULL AUTO_INCREMENT,
  `browser` varchar(255) NULL DEFAULT NULL COMMENT '浏览器名称',
  `site_id` int NULL DEFAULT NULL COMMENT '站点Id',
  `total` int NULL DEFAULT NULL COMMENT '总访问次数',
  `create_date` date NULL DEFAULT NULL COMMENT '日期 如2016-03-29',
  `create_time` datetime NULL DEFAULT NULL COMMENT '首次访问创建时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '访问浏览器统计表' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of t_visit_browser
-- ----------------------------



-- ----------------------------
-- Table structure for t_visit_content
-- ----------------------------
DROP TABLE IF EXISTS `t_visit_content`;
CREATE TABLE `t_visit_content`  (
  `id` int NOT NULL AUTO_INCREMENT,
  `content_id` int NULL DEFAULT NULL COMMENT '内容Id',
  `content_title` varchar(500) NULL DEFAULT NULL COMMENT '内容标题',
  `class_id` int NULL DEFAULT NULL COMMENT '栏目Id',
  `class_name` varchar(255) NULL DEFAULT NULL COMMENT '栏目名称',
  `site_id` int NULL DEFAULT NULL COMMENT '站点Id',
  `total` int NULL DEFAULT NULL COMMENT '总访问次数',
  `create_date` date NULL DEFAULT NULL COMMENT '日期 如2016-03-29',
  `create_time` datetime NULL DEFAULT NULL COMMENT '首次访问创建时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '内容访问统计表' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of t_visit_content
-- ----------------------------

-- ----------------------------
-- Table structure for t_visit_count
-- ----------------------------
DROP TABLE IF EXISTS `t_visit_count`;
CREATE TABLE `t_visit_count`  (
  `id` int NOT NULL AUTO_INCREMENT,
  `total_pv` int NULL DEFAULT NULL COMMENT '累计访问PV',
  `total_uv` int NULL DEFAULT NULL COMMENT '累计访问UV',
  `total_ip` int NULL DEFAULT NULL COMMENT '累计访问IP',
  `max_total_pv` int NULL DEFAULT NULL COMMENT '最高日访问总PV数',
  `max_total_uv` int NULL DEFAULT NULL COMMENT '最高日访问总UV数',
  `max_total_ip` int NULL DEFAULT NULL COMMENT '最高日访问总IP数',
  `max_total_pv_date` date NULL DEFAULT NULL COMMENT '最高日访问PV日期',
  `max_total_uv_date` date NULL DEFAULT NULL COMMENT '最高日访问UV日期',
  `max_total_ip_date` date NULL DEFAULT NULL COMMENT '最高日访问IP日期',
  `site_id` int NULL DEFAULT NULL COMMENT '站点Id',
  `total` int NULL DEFAULT NULL COMMENT '总访问次数',
  `create_date` date NULL DEFAULT NULL COMMENT '日期 如2016-03-29',
  `create_time` datetime NULL DEFAULT NULL COMMENT '首次访问创建时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '访问汇总统计表' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of t_visit_count
-- ----------------------------


-- ----------------------------
-- Table structure for t_visit_day
-- ----------------------------
DROP TABLE IF EXISTS `t_visit_day`;
CREATE TABLE `t_visit_day`  (
  `id` int NOT NULL AUTO_INCREMENT,
  `total_pv` int NULL DEFAULT NULL COMMENT '访问PV',
  `total_uv` int NULL DEFAULT NULL COMMENT '访问UV',
  `total_ip` int NULL DEFAULT NULL COMMENT '访问IP',
  `site_id` int NULL DEFAULT NULL COMMENT '站点Id',
  `create_date` date NULL DEFAULT NULL COMMENT '日期 如2016-03-29',
  `create_time` datetime NULL DEFAULT NULL COMMENT '首次访问创建时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '每日访问统计表' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of t_visit_day
-- ----------------------------


-- ----------------------------
-- Table structure for t_visit_details
-- ----------------------------
DROP TABLE IF EXISTS `t_visit_details`;
CREATE TABLE `t_visit_details`  (
  `id` int NOT NULL AUTO_INCREMENT,
  `url` varchar(255) NULL DEFAULT NULL COMMENT '访问url',
  `referer_url` varchar(500) NULL DEFAULT NULL COMMENT '来源url',
  `isp` varchar(100) NULL DEFAULT NULL COMMENT '网络接入商',
  `language` varchar(100) NULL DEFAULT NULL COMMENT '访问语言',
  `device` varchar(100) NULL DEFAULT NULL COMMENT '访问设备',
  `os` varchar(100) NULL DEFAULT NULL COMMENT '访问系统',
  `pixel` varchar(100) NULL DEFAULT NULL COMMENT '访问分辨率',
  `browser` varchar(100) NULL DEFAULT NULL COMMENT '访问浏览器',
  `region` varchar(100) NULL DEFAULT NULL COMMENT '访问区域',
  `ip` varchar(255) NULL DEFAULT NULL COMMENT '访问ip',
  `user_agent` varchar(500) NULL DEFAULT NULL COMMENT '访问ua',
  `site_id` int NULL DEFAULT NULL COMMENT '访问站点id',
  `site_name` varchar(100) NULL DEFAULT NULL COMMENT '访问站点名称',
  `class_id` int NULL DEFAULT NULL COMMENT '访问栏目id',
  `class_name` varchar(100) NULL DEFAULT NULL COMMENT '访问栏目名称',
  `parent_id` int NULL DEFAULT NULL COMMENT '访问栏目父id',
  `parent_name` varchar(100) NULL DEFAULT NULL COMMENT '访问栏目父名称',
  `content_id` int NULL DEFAULT NULL COMMENT '访问数据id',
  `content_title` varchar(500) NULL DEFAULT NULL COMMENT '访问数据标题',
  `screen_width` int NULL DEFAULT NULL COMMENT '访问屏幕宽度',
  `screen_height` int NULL DEFAULT NULL COMMENT '访问屏幕高度',
  `member_id` int NULL DEFAULT NULL COMMENT '访问会员id',
  `member_name` varchar(100) NULL DEFAULT NULL COMMENT '访问会员名称',
  `visit_time` datetime NULL DEFAULT NULL COMMENT '访问时间',
  `visit_date` date NULL DEFAULT NULL COMMENT '访问日期',
  `spider` varchar(100) NULL DEFAULT NULL COMMENT '爬虫名称',
  `province` char(100) NULL DEFAULT NULL COMMENT '省份',
  `session_id` varchar(100) NULL DEFAULT NULL COMMENT '会话Id',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_visit_details_date_time_id`(`visit_date` ASC, `visit_time` DESC, `id` DESC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '访问明细表' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of t_visit_details
-- ----------------------------

-- ----------------------------
-- Table structure for t_visit_device
-- ----------------------------
DROP TABLE IF EXISTS `t_visit_device`;
CREATE TABLE `t_visit_device`  (
  `id` int NOT NULL AUTO_INCREMENT,
  `device` varchar(255) NULL DEFAULT NULL COMMENT '设备类型',
  `site_id` int NULL DEFAULT NULL COMMENT '站点Id',
  `total` int NULL DEFAULT NULL COMMENT '总访问次数',
  `create_date` date NULL DEFAULT NULL COMMENT '日期 如2016-03-29',
  `create_time` datetime NULL DEFAULT NULL COMMENT '首次访问创建时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '设备访问统计表' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of t_visit_device
-- ----------------------------

-- ----------------------------
-- Table structure for t_visit_download
-- ----------------------------
DROP TABLE IF EXISTS `t_visit_download`;
CREATE TABLE `t_visit_download`  (
  `id` int NOT NULL AUTO_INCREMENT,
  `download_id` int NULL DEFAULT NULL COMMENT '下载Id',
  `download_title` varchar(500) NULL DEFAULT NULL COMMENT '下载标题',
  `class_id` int NULL DEFAULT NULL COMMENT '栏目Id',
  `class_name` varchar(255) NULL DEFAULT NULL COMMENT '栏目名称',
  `site_id` int NULL DEFAULT NULL COMMENT '站点Id',
  `total` int NULL DEFAULT NULL COMMENT '总访问次数',
  `file_path` varchar(500) NULL DEFAULT NULL COMMENT '文件路径',
  `visit_type` varchar(255) NULL DEFAULT NULL COMMENT '访问类型',
  `create_date` date NULL DEFAULT NULL COMMENT '日期 如2016-03-29',
  `create_time` datetime NULL DEFAULT NULL COMMENT '首次访问创建时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '下载访问统计表' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of t_visit_download
-- ----------------------------

-- ----------------------------
-- Table structure for t_visit_hours
-- ----------------------------
DROP TABLE IF EXISTS `t_visit_hours`;
CREATE TABLE `t_visit_hours`  (
  `id` int NOT NULL AUTO_INCREMENT,
  `time_line` int NULL DEFAULT NULL COMMENT '时间点 0-23',
  `site_id` int NULL DEFAULT NULL COMMENT '站点Id',
  `total` int NULL DEFAULT NULL COMMENT '总访问次数',
  `create_date` date NULL DEFAULT NULL COMMENT '日期 如2016-03-29',
  `create_time` datetime NULL DEFAULT NULL COMMENT '首次访问创建时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '小时访问统计表' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of t_visit_hours
-- ----------------------------





-- ----------------------------
-- Table structure for t_visit_ip
-- ----------------------------
DROP TABLE IF EXISTS `t_visit_ip`;
CREATE TABLE `t_visit_ip`  (
  `id` int NOT NULL AUTO_INCREMENT,
  `ip` varchar(255) NULL DEFAULT NULL COMMENT 'IP',
  `site_id` int NULL DEFAULT NULL COMMENT '站点Id',
  `total` int NULL DEFAULT NULL COMMENT '总访问次数',
  `create_date` date NULL DEFAULT NULL COMMENT '日期 如2016-03-29',
  `create_time` datetime NULL DEFAULT NULL COMMENT '首次访问创建时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'IP访问统计表' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of t_visit_ip
-- ----------------------------



-- ----------------------------
-- Table structure for t_visit_isp
-- ----------------------------
DROP TABLE IF EXISTS `t_visit_isp`;
CREATE TABLE `t_visit_isp`  (
  `id` int NOT NULL AUTO_INCREMENT,
  `isp` varchar(255) NULL DEFAULT NULL COMMENT '运营商名称',
  `site_id` int NULL DEFAULT NULL COMMENT '站点Id',
  `total` int NULL DEFAULT NULL COMMENT '总访问次数',
  `create_date` date NULL DEFAULT NULL COMMENT '日期 如2016-03-29',
  `create_time` datetime NULL DEFAULT NULL COMMENT '首次访问创建时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '运营商访问统计表' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of t_visit_isp
-- ----------------------------


-- ----------------------------
-- Table structure for t_visit_menu
-- ----------------------------
DROP TABLE IF EXISTS `t_visit_menu`;
CREATE TABLE `t_visit_menu`  (
  `id` int NOT NULL AUTO_INCREMENT,
  `class_id` int NULL DEFAULT NULL COMMENT '栏目Id',
  `class_name` varchar(255) NULL DEFAULT NULL COMMENT '栏目名称',
  `site_id` int NULL DEFAULT NULL COMMENT '站点Id',
  `total` int NULL DEFAULT NULL COMMENT '总访问次数',
  `create_date` date NULL DEFAULT NULL COMMENT '日期 如2016-03-29',
  `create_time` datetime NULL DEFAULT NULL COMMENT '首次访问创建时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '栏目访问统计表' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of t_visit_menu
-- ----------------------------

-- ----------------------------
-- Table structure for t_visit_os
-- ----------------------------
DROP TABLE IF EXISTS `t_visit_os`;
CREATE TABLE `t_visit_os`  (
  `id` int NOT NULL AUTO_INCREMENT,
  `os` varchar(255) NULL DEFAULT NULL COMMENT '操作系统名称',
  `site_id` int NULL DEFAULT NULL COMMENT '站点Id',
  `total` int NULL DEFAULT NULL COMMENT '总访问次数',
  `create_date` date NULL DEFAULT NULL COMMENT '日期 如2016-03-29',
  `create_time` datetime NULL DEFAULT NULL COMMENT '首次访问创建时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '操作系统访问统计表' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of t_visit_os
-- ----------------------------


-- ----------------------------
-- Table structure for t_visit_pixel
-- ----------------------------
DROP TABLE IF EXISTS `t_visit_pixel`;
CREATE TABLE `t_visit_pixel`  (
  `id` int NOT NULL AUTO_INCREMENT,
  `pixel` varchar(255) NULL DEFAULT NULL COMMENT '分辨率大小',
  `site_id` int NULL DEFAULT NULL COMMENT '站点Id',
  `total` int NULL DEFAULT NULL COMMENT '总访问次数',
  `create_date` date NULL DEFAULT NULL COMMENT '日期 如2016-03-29',
  `create_time` datetime NULL DEFAULT NULL COMMENT '首次访问创建时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '分辨率访问统计表' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of t_visit_pixel
-- ----------------------------


-- ----------------------------
-- Table structure for t_visit_region
-- ----------------------------
DROP TABLE IF EXISTS `t_visit_region`;
CREATE TABLE `t_visit_region`  (
  `id` int NOT NULL AUTO_INCREMENT,
  `region` varchar(255) NULL DEFAULT NULL COMMENT '地区名称',
  `province` varchar(100) NULL DEFAULT NULL COMMENT '省份',
  `site_id` int NULL DEFAULT NULL COMMENT '站点Id',
  `total` int NULL DEFAULT NULL COMMENT '总访问次数',
  `create_date` date NULL DEFAULT NULL COMMENT '日期 如2016-03-29',
  `create_time` datetime NULL DEFAULT NULL COMMENT '首次访问创建时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '地区访问统计表' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of t_visit_region
-- ----------------------------


-- ----------------------------
-- Table structure for t_visit_source
-- ----------------------------
DROP TABLE IF EXISTS `t_visit_source`;
CREATE TABLE `t_visit_source`  (
  `id` int NOT NULL AUTO_INCREMENT,
  `source` varchar(500) NULL DEFAULT NULL COMMENT '来源地址',
  `site_id` int NULL DEFAULT NULL COMMENT '站点Id',
  `total` int NULL DEFAULT NULL COMMENT '总访问次数',
  `create_date` date NULL DEFAULT NULL COMMENT '日期 如2016-03-29',
  `create_time` datetime NULL DEFAULT NULL COMMENT '首次访问创建时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '来源访问统计表' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of t_visit_source
-- ----------------------------

-- ----------------------------
-- Table structure for t_visit_spider
-- ----------------------------
DROP TABLE IF EXISTS `t_visit_spider`;
CREATE TABLE `t_visit_spider`  (
  `id` int NOT NULL AUTO_INCREMENT,
  `spider` varchar(255) NULL DEFAULT NULL COMMENT '爬虫名称',
  `site_id` int NULL DEFAULT NULL COMMENT '站点Id',
  `total` int NULL DEFAULT NULL COMMENT '总访问次数',
  `create_date` date NULL DEFAULT NULL COMMENT '日期 如2016-03-29',
  `create_time` datetime NULL DEFAULT NULL COMMENT '首次访问创建时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '爬虫访问统计表' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of t_visit_spider
-- ----------------------------


-- ----------------------------
-- Table structure for t_visit_target
-- ----------------------------
DROP TABLE IF EXISTS `t_visit_target`;
CREATE TABLE `t_visit_target`  (
  `id` int NOT NULL AUTO_INCREMENT,
  `target` varchar(255) NULL DEFAULT NULL COMMENT '目标地址',
  `site_id` int NULL DEFAULT NULL COMMENT '站点Id',
  `total` int NULL DEFAULT NULL COMMENT '总访问次数',
  `create_date` date NULL DEFAULT NULL COMMENT '日期 如2016-03-29',
  `create_time` datetime NULL DEFAULT NULL COMMENT '首次访问创建时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '目标访问统计表' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of t_visit_target
-- ----------------------------
CREATE TABLE IF NOT EXISTS `cms_platform_push_record` (
                                                          `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
                                                          `source_content_id` int NOT NULL COMMENT '中台数据ID（源内容ID）',
                                                          `content_type` varchar(32) NOT NULL COMMENT '数据类型 product/news/form/solution',
    `site_id` bigint NULL COMMENT '目标站点ID',
    `column_id` int NULL COMMENT '目标站点栏目ID',
    `target_content_id` int NULL COMMENT '推送生成的目标内容ID',
    `field_mapping` text NULL COMMENT '字段映射关系JSON [{productField,siteField}]',
    `field_content` longtext NULL COMMENT '推送的源字段内容JSON',
    `push_config_id` bigint NULL COMMENT '推送配置ID',
    `push_status` varchar(20) NOT NULL DEFAULT 'SUCCESS' COMMENT '推送状态 SUCCESS/FAILED',
    `push_type` varchar(20) NOT NULL DEFAULT 'INSERT' COMMENT '推送类型 INSERT新增 UPDATE更新',
    `target_status` int NULL COMMENT '推送目标状态 0草稿 1待审 2已审核 3已发布',
    `push_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '推送时间',
    `push_user_id` bigint NULL COMMENT '推送人ID',
    `push_user` varchar(100) NULL COMMENT '推送人',
    `error_message` varchar(1000) NULL COMMENT '失败原因',
    `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否 1是',
    PRIMARY KEY (`id`) USING BTREE,
    INDEX `idx_push_record_source` (`content_type`, `source_content_id`) USING BTREE,
    INDEX `idx_push_record_config` (`push_config_id`) USING BTREE,
    INDEX `idx_push_record_site` (`site_id`, `column_id`) USING BTREE
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '内容中台推送记录';


CREATE TABLE IF NOT EXISTS `cms_ai_ui_package_upload_record` (
                                                                 `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
                                                                 `site_id` bigint NOT NULL COMMENT '站点ID(cms_ai_site_project.id)',
                                                                 `project_code` varchar(128) NOT NULL COMMENT '站点编码',
    `version` varchar(128) NOT NULL COMMENT 'UIPackage 版本标识',
    `package_name` varchar(255) NULL COMMENT '上传的ZIP文件名',
    `file_size` bigint NULL COMMENT 'ZIP文件字节数',
    `upload_type` varchar(20) NOT NULL DEFAULT 'UPLOAD' COMMENT '上传类型 UPLOAD首次上传 UPGRADE升级覆盖',
    `upload_user_id` bigint NULL COMMENT '上传人ID',
    `upload_user` varchar(100) NULL COMMENT '上传人',
    `upload_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
    `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否 1是',
    PRIMARY KEY (`id`) USING BTREE,
    INDEX `idx_ui_pkg_upload_site` (`site_id`, `upload_time`) USING BTREE
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = 'UIPackage 上传记录';










DELETE FROM `t_menu` WHERE `menu_key` IN ('cms-platform-solution-config', 'cms-platform-solution-center');

INSERT INTO `t_menu`
(`id`, `parent_id`, `menu_key`, `title`, `path`, `icon`, `category`, `sort_order`,
 `visible`, `enabled`, `requires_system_admin`, `component_path`, `create_time`, `update_time`, `deleted`)
VALUES
    (907, 9, 'cms-platform-solution-config', '解决方案配置', '/admin/v2/cms-platform/solution-config', 'Management', '内容中台', 903, 1, 1, 0, NULL, NOW(), NOW(), 0),
    (908, 9, 'cms-platform-solution-center', '解决方案', '/admin/v2/cms-platform/solution-center', 'Tickets', '内容中台', 908, 1, 1, 0, NULL, NOW(), NOW(), 0);




-- 内容中台新增：案例配置 / 案例中心 菜单（挂载于父菜单 id=9 cms-platform）
DELETE FROM `t_menu` WHERE `menu_key` IN ('cms-platform-case-config', 'cms-platform-case-center');

INSERT INTO `t_menu`
(`id`, `parent_id`, `menu_key`, `title`, `path`, `icon`, `category`, `sort_order`,
 `visible`, `enabled`, `requires_system_admin`, `component_path`, `create_time`, `update_time`, `deleted`)
VALUES
    (909, 9, 'cms-platform-case-config', '案例配置', '/admin/v2/cms-platform/case-config', 'Collection', '内容中台',
     904, 1, 1, 0, NULL, NOW(), NOW(), 0),
    (910, 9, 'cms-platform-case-center', '案例中心', '/admin/v2/cms-platform/case-center', 'Files', '内容中台', 910, 1,
     1, 0, NULL, NOW(), NOW(), 0);

SET FOREIGN_KEY_CHECKS = 1;

-- 表单服务配置：新库保持空表，按站点显式启用。
DROP TABLE IF EXISTS cms_form_service_key;
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
