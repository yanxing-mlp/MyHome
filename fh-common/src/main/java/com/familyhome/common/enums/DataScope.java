package com.familyhome.common.enums;

/** 密码本与文档分区；不传 scope 时沿用公共数据，分区不允许事后修改。 */
public enum DataScope {
    PUBLIC,
    PRIVATE;

    public static DataScope orPublic(DataScope scope) {
        return scope == null ? PUBLIC : scope;
    }
}
