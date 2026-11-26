package com.xinhongru.cms.template;

import com.xinhongru.entity.OneshotEntity;
import java.util.List;
import java.util.Optional;

/** 根容器能否沿用已有公开首页关系发布原生模板，以及该关系确认的别名。 */
public interface NativeHomePublication {
    Optional<List<String>> nativeAliases(Long siteId, String code, OneshotEntity route);

    record Proof(Integer sourceMenuId, List<String> aliases) {}

    default Optional<Proof> nativeProof(Long siteId, String code, OneshotEntity route) {
        return Optional.empty();
    }
}
