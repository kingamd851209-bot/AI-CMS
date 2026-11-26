-- 在部署支持专用凭据的版本前应用。旧账号于下次成功登录时迁移。
CREATE TABLE IF NOT EXISTS `t_member_credentials` (
    `content_id` int UNSIGNED NOT NULL,
    `password_hash` varchar(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (`content_id`),
    CONSTRAINT `fk_member_credentials_content` FOREIGN KEY (`content_id`)
        REFERENCES `t_contents` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB;
