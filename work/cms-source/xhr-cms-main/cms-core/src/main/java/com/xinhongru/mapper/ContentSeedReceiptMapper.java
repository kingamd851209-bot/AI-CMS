package com.xinhongru.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ContentSeedReceiptMapper {
    record Receipt(String seedKey, String sourceSignature, Long activeContentId) {}

    record SourceReceipt(Long menuId, String seedKey, String sourceSignature) {}

    @Select(
            """
            SELECT r.seed_key AS seedKey, r.source_signature AS sourceSignature, c.id AS activeContentId
            FROM t_content_seed_receipts r LEFT JOIN t_contents c
              ON c.id=r.content_id AND c.site_project_id=r.site_id AND c.class_id=r.menu_id AND c.deleted=0
            WHERE r.site_id=#{siteId} AND r.menu_id=#{menuId} AND r.seed_key LIKE #{pagePattern}
            """)
    List<Receipt> findPage(
            @Param("siteId") Long siteId,
            @Param("menuId") Long menuId,
            @Param("pagePattern") String pagePattern);

    @Select(
            """
            SELECT r.menu_id AS menuId, r.seed_key AS seedKey, r.source_signature AS sourceSignature
            FROM t_content_seed_receipts r
            WHERE r.site_id=#{siteId} AND r.seed_key LIKE #{pagePattern}
            """)
    List<SourceReceipt> findSourcePage(
            @Param("siteId") Long siteId, @Param("pagePattern") String pagePattern);

    @Select(
            """
            SELECT r.seed_key AS seedKey, r.source_signature AS sourceSignature, c.id AS activeContentId
            FROM t_content_seed_receipts r LEFT JOIN t_contents c
              ON c.id=r.content_id AND c.site_project_id=r.site_id AND c.class_id=r.menu_id
                 AND c.deleted=0 AND c.xgid=#{ownerRecordId}
            WHERE r.site_id=#{siteId} AND r.menu_id=#{menuId} AND r.seed_key LIKE #{pagePattern}
            """)
    List<Receipt> findOwnedPage(
            @Param("siteId") Long siteId,
            @Param("menuId") Long menuId,
            @Param("pagePattern") String pagePattern,
            @Param("ownerRecordId") Long ownerRecordId);

    @Insert(
            "INSERT INTO t_content_seed_receipts(site_id, menu_id, seed_key, source_signature,"
                    + " content_id) VALUES(#{siteId}, #{menuId}, #{seedKey}, #{sourceSignature},"
                    + " #{contentId})")
    int insert(
            @Param("siteId") Long siteId,
            @Param("menuId") Long menuId,
            @Param("seedKey") String seedKey,
            @Param("sourceSignature") String sourceSignature,
            @Param("contentId") Long contentId);
}
