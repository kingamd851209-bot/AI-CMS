-- 既有环境增量升级；执行前备份 cms_ai_site_project。请勿执行初始化 SQL。
-- 新增设置默认关闭，请在管理端按站点开启；不从环境文件导入明文密码。
ALTER TABLE `cms_ai_site_project`
    MODIFY COLUMN `site_service_ip` varchar(255) NULL DEFAULT NULL COMMENT 'UI 静态资源域名/IP',
    ADD COLUMN `preview_access_enabled` tinyint(1) NOT NULL DEFAULT 0 COMMENT 'HTML页面是否需要访问密码',
    ADD COLUMN `preview_access_password` varchar(255) NULL DEFAULT NULL COMMENT '访问认证密码';
