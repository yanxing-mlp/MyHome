package com.familyhome.album.biz.model.vo.admin;

import lombok.Data;
import java.util.List;

/**
 * 相册图片分页响应（非泛型，避免 Jackson 序列化问题）。
 */
@Data
public class AlbumImagePageVO {
    private List<AlbumImageVO> list;
    private long total;
    private long pageNo;
    private int pageSize;
    private boolean hasMore;
}
