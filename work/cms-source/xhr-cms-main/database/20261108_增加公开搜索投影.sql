-- 公开搜索同表派生文本，不创建第二内容库。先备份并核对列不存在，再执行一次。
-- 不自动启用任何扩展字段；应用层在内容/模型写入同一事务构建文本。
ALTER TABLE t_contents ADD COLUMN public_search_text LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL COMMENT '公开搜索清洗文本投影';
ALTER TABLE t_oneshot ADD COLUMN public_search_text LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL COMMENT '单页公开搜索清洗文本投影';
-- 既有记录必须通过经授权的分批重建接缝回填后再验收搜索，禁止加载全部富文本到内存。
-- 本票先LIKE量测，不无条件加FULLTEXT或运行时动态DDL。
