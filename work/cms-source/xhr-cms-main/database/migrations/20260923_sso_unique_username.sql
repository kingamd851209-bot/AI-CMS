-- 通用管理员登录名唯一约束；先人工解决重复用户名。
-- 不删除或合并账号；发现重复记录时 ALTER 会失败。
SELECT username, COUNT(*) AS duplicate_count
FROM t_users GROUP BY username HAVING COUNT(*) > 1;

ALTER TABLE t_users ADD CONSTRAINT uk_t_users_username UNIQUE (username);
