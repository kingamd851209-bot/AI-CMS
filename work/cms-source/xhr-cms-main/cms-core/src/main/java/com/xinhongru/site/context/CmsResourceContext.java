package com.xinhongru.site.context;

import com.xinhongru.entity.ContentEntity;
import com.xinhongru.entity.OneshotEntity;

public record CmsResourceContext(
        Long siteId, OneshotEntity menu, ContentEntity content, Long modelVersionId) {}
