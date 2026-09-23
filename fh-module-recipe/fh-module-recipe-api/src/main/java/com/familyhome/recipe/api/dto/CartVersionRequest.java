package com.familyhome.recipe.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 下单 / 继续加菜请求：只提交购物车版本，菜品明细由服务端读取。
 */
@Data
public class CartVersionRequest {

    @NotNull(message = "购物车版本不能为空")
    @Min(value = 0, message = "购物车版本不能小于 0")
    private Long version;
}
