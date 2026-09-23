package com.familyhome.user.biz.model.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 登录请求：下拉选中的账号 + 手填的口令。
 *
 * <p>刻意<b>不</b>接受"按手机号登录"或"按昵称登录"：昵称是唯一的人机识别入口，账号由下拉框
 * 定成 id，口令只用来证明"选的这个人在场"。所以两个字段都要，服务端按 {@code userId} 取记录、
 * 再拿 {@code password} 去比那一行的 PBKDF2 哈希。
 *
 * <p>{@code password} 这里<b>不收最短长度</b>：那是"设置口令"的规则（见 {@link UserCreateRequest}
 * 与 {@link UserPasswordUpdateRequest}），登录侧收长度会把一个合法的老口令挡在门外，
 * 而它并不能带来任何安全性——错的口令多一次比对而已。
 */
@Data
public class UserLoginRequest {

    @NotNull(message = "请选择账号")
    private Long userId;

    /**
     * 口令的<b>传输层密文</b>（v11 起），由 {@code TransportCipher} 在 {@code login()} 入口解回明文。
     *
     * <p>依然只有 {@code @NotBlank}、刻意不收最短长度与格式：登录侧任何"长度/字符"门槛都会造出
     * "库里存得下、却登不进"的死账号。这一格换成密文之后更不能判——密文长度由 IV 与 Base64 决定，
     * 跟用户口令长度无关。
     */
    @NotBlank(message = "请输入密码")
    private String password;
}
