package com.familyhome.common.enums;

/**
 * 相册分区，分组、图片、城市统计均按此隔离，接口缺省为 FAMILY。
 *
 * <p>家庭图片/城市 owner_id 恒为 0；个人图片/城市 owner_id 为所属账号。
 * 分组仍以 creator_id 作为个人属主，图片 creator_id 则始终保留真实上传人。
 * 个人档 B/C 端读写均只允许当前账号，C 端另要求分组和图片上架。
 * 这里约束业务接口，不替代登录认证或静态文件访问控制。
 */
public enum AlbumScope {

    /** 全家共享的独立数据分区 */
    FAMILY,

    /** 当前账号独享的数据分区 */
    PERSONAL;

    public boolean isPersonal() {
        return this == PERSONAL;
    }
}
