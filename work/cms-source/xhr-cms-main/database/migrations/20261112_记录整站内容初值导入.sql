-- 导入身份与内容、历史同事务提交，内容删除后保留收据。
CREATE TABLE IF NOT EXISTS `t_content_seed_receipts` (
  `site_id` bigint NOT NULL,
  `menu_id` int NOT NULL,
  `seed_key` char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `source_signature` char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `content_id` int UNSIGNED NOT NULL,
  PRIMARY KEY (`site_id`, `menu_id`, `seed_key`)
) ENGINE = InnoDB;
