-- t_contents：记录内容来源，供多语言增量同步按 (source_site_id, source_content_id) 判定是否已存在
ALTER TABLE `t_contents`
    ADD COLUMN `source_site_id`    bigint NULL DEFAULT NULL COMMENT '来源站点ID：多语言增量同步稳定关联，历史数据为空'
  AFTER `site_project_id`,
    ADD COLUMN `source_content_id` int    NULL DEFAULT NULL COMMENT '来源站点内容ID：与 source_site_id 共同构成来源关联'
   AFTER `source_site_id`;

-- t_oneshot：记录单页栏目的同步来源，供多语言增量同步按 (source_site_id, source_menu_id) 判定是否已存在
ALTER TABLE `t_oneshot`
    ADD COLUMN `source_site_id` bigint NULL DEFAULT NULL COMMENT '单页同步来源站点ID，历史数据为空' AFTER
  `site_project_id`,
    ADD COLUMN `source_menu_id` int    NULL DEFAULT NULL COMMENT '单页同步来源栏目ID，需与 source_site_id 共同匹配'
  AFTER `source_site_id`;

-- 可选索引（当前同步是在内存里按栏目取数比对的，非必需；若后续要直接按来源查询再加）：

ALTER TABLE `t_contents` ADD INDEX `idx_contents_source` (`source_site_id`, `source_content_id`);
ALTER TABLE `t_oneshot`  ADD INDEX `idx_oneshot_source` (`source_site_id`, `source_menu_id`);