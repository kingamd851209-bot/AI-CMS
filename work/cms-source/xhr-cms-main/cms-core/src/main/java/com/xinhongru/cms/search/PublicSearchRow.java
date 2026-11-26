package com.xinhongru.cms.search;

import lombok.Data;

@Data
public class PublicSearchRow {
    private String kind;
    private Integer recordId;
    private Integer menuId;
    private String title;
    private String searchText;
    private java.time.LocalDateTime createTime;
}
