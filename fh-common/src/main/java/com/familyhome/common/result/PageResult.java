package com.familyhome.common.result;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import lombok.Getter;

/**
 * 统一分页响应。
 *
 * <p>{@code hasMore} 是给 H5 无限滚动用的，省掉前端自己算 {@code pageNo * pageSize < total}。
 *
 * @param <T> 列表元素类型
 */
@Getter
public class PageResult<T> {

    @JsonProperty("list")
    private final List<T> list;
    
    @JsonProperty("total")
    private final long total;
    
    @JsonProperty("pageNo")
    private final long pageNo;
    
    @JsonProperty("pageSize")
    private final int pageSize;
    
    @JsonProperty("hasMore")
    private final boolean hasMore;

    private PageResult(List<T> list, long total, long pageNo, int pageSize) {
        this.list = list;
        this.total = total;
        this.pageNo = pageNo;
        this.pageSize = pageSize;
        this.hasMore = pageNo * pageSize < total;
    }

    public static <T> PageResult<T> of(List<T> list, long total, long pageNo, int pageSize) {
        return new PageResult<>(list, total, pageNo, pageSize);
    }
}
