package com.familyhome.user.biz.model.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 新增账号：昵称 + 手机号 + 可选头像，三项。
 *
 * <p><b>没有 role 字段</b>：不是"管理员定不了角色"，而是角色只有一个写入口——
 * {@code PUT /api/b/user/{id}/role}（见 {@link UserRoleUpdateRequest}）。新建恒为 MEMBER，
 * 要提权的人建完在账号管理表格里当场点一下即可。这里再放一格就是同一件事两条路，
 * 而且弹窗那一格会变成"加人的顺手就发一个管理员"，比表格里单独一次点击更容易失手。
 *
 * <p><b>也没有 password 字段</b>：管理员从头到尾不接触任何人的口令——既看不到，也不代设。
 * 初始口令由服务端给一个全家统一的固定值（见
 * {@link com.familyhome.user.biz.service.AppUserService#DEFAULT_INITIAL_PASSWORD}，与 V500/V502
 * 两行种子的初始口令是同一个），所以那一列 {@code NOT NULL} 依然成立，而新建出来的账号第一次能登进来。
 * 当事人第一次登录后去个人中心自己换掉；换不了也没关系——本域没有"管理员重置"这条路，
 * 忘了口令的兜底是删号重建（{@code AppUserService#delete}）。
 */
@Data
public class UserCreateRequest {

    @NotBlank(message = "请输入昵称")
    @Size(max = 32, message = "昵称最多 32 个字符")
    private String name;

    /** 手机号，个人资料项（V502 起不再参与登录核对）。必填是既有口径，没有因为这轮改动而放松。 */
    @NotBlank(message = "请输入手机号")
    @Size(max = 32, message = "手机号最多 32 个字符")
    private String phone;

    /** 头像，来自 {@code POST /api/b/file/upload}（bizType = USER_AVATAR）返回的 id；不传就是默认头像 */
    private Long avatarFileId;
}
