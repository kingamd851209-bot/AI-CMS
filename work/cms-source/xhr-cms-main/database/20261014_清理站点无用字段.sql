-- 既有环境增量精简；仅适用于已核对过字段/索引与当前初始化结构一致的数据库。
-- 执行前完整备份 cms_ai_site_project，并确认没有仓库外系统依赖这些历史字段。
-- 此脚本删除列及其数据，必须在应用停止且部署本次代码时经审核执行。
ALTER TABLE `cms_ai_site_project`
    DROP INDEX `idx_ai_site_project_confirmed_model_version`,
    DROP INDEX `idx_cms_site_active_release`,
    DROP COLUMN `template_root`,
    DROP COLUMN `preview_base_path`,
    DROP COLUMN `confirmed_content_model_version_id`,
    DROP COLUMN `content_model_confirmed_at`,
    DROP COLUMN `last_recognition_time`,
    DROP COLUMN `active_release_id`,
    DROP COLUMN `active_release_version`,
    DROP COLUMN `legacy_public_resource_mode`,
    DROP COLUMN `traditional_auto_publish_enabled`,
    DROP COLUMN `traditional_template_entry_path`,
    DROP COLUMN `active_publish_directory`;
