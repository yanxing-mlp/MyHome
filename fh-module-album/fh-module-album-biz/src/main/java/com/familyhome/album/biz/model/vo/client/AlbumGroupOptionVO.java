package com.familyhome.album.biz.model.vo.client;

import lombok.Getter;
import lombok.Setter;

/**
 * C 端上传浮层里的一个分组候选。
 *
 * <p>只有 {@code id} 和 {@code name}：C 端不能新建/改名/上下架分组，排序值、张数、添加人那些
 * B 端要看的列都不下发。这一份的"只含上架分组"是服务端保证的（{@code AlbumCController#listGroupOptions}），
 * 前端不再自己滤 {@code status}——下架的分组对 C 端整本相册都不存在，本就不该选得到。
 */
@Getter
@Setter
public class AlbumGroupOptionVO {

    private Long id;

    /** 分组名 */
    private String name;
}
