-- user 域（V5xx 号段续）：v48 三档角色 + 可编辑权限矩阵。
--
-- 【这一版把"是不是管理员"一档拆成三档角色】
--   SUPER_ADMIN 超级管理员：全部权限，且是唯一能设置他人角色、编辑权限矩阵的人（硬门禁，不进矩阵）。
--   ADMIN       普通管理员：默认能管业务（菜谱/相册/文件/视频/点单/密码本），看不到系统后台。
--   MEMBER      普通成员：  默认业务只读（能看不能改），看不到系统后台。
-- 存量库里只有 ADMIN/MEMBER 两档（V500 种子把大宝写成 ADMIN）。这里把老的 ADMIN 一律升成
-- SUPER_ADMIN——大宝原本就能看到系统后台、改角色，拆档后能保住这份全权的只有超管这一档；
-- 老 MEMBER 保持 MEMBER（从"能进 B 端写业务"变成"只读"，正是需求要的行为变化）。
UPDATE `app_user` SET `role` = 'SUPER_ADMIN' WHERE `role` = 'ADMIN';

ALTER TABLE `app_user`
  MODIFY `role` VARCHAR(16) NOT NULL DEFAULT 'MEMBER'
    COMMENT 'SUPER_ADMIN=超级管理员 / ADMIN=普通管理员 / MEMBER=普通成员';

-- 【role_permission：可编辑的那一层——"某个角色勾选了哪些权限码"】
-- 角色固定三种、权限项也是代码里写死的一批（PermissionCatalog），这两样都不让超管在页面上增删；
-- 唯一可编辑的就是这张表：超管在「角色管理」页勾一勾，某个角色能看/能改什么当场变，下一次请求即生效。
--
-- 【为什么不存 SUPER_ADMIN 的行】超管拥有全部权限、由 CurrentUserHolder.hasPermission 短路，
-- 与这张表勾没勾无关（防自锁：谁都不能把超管的权限勾没了）。所以种子只写 ADMIN / MEMBER 两档，
-- SUPER_ADMIN 这一列在矩阵页恒为"全选且禁用"。
--
-- 【硬删、无 deleted 列】这是一张纯配置关联表（谁有哪些权限），没有"下架"语义，
-- 与 recipe_category_rel / album_image_group_rel 同构：改矩阵就是"按角色整批删了重插"，
-- 靠 UNIQUE(role, permission) 兜并发重复。create_time / update_time 照建表规范两列都留。
CREATE TABLE `role_permission` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `role`        VARCHAR(16)     NOT NULL                COMMENT '角色：ADMIN / MEMBER（SUPER_ADMIN 不落表，见文件头）',
  `permission`  VARCHAR(64)     NOT NULL                COMMENT '权限码，取值必须是 PermissionCatalog 里的合法项',
  `create_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_role_permission` (`role`, `permission`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='角色↔权限勾选矩阵（超管可编辑）';

-- 种子：默认权限。与 PermissionCatalog 的 BUSINESS_VIEW / BUSINESS_MANAGE 常量逐字对齐，改代码常量记得改这里。
--   ADMIN  = 六个业务模块的 view + manage（能看能改业务），无系统后台任何 access。
--   MEMBER = 六个业务模块的 view（只读浏览业务），无任何 manage、无系统后台。
INSERT INTO `role_permission` (`role`, `permission`) VALUES
  ('ADMIN', 'recipe.view'), ('ADMIN', 'recipe.manage'),
  ('ADMIN', 'order.view'),  ('ADMIN', 'order.manage'),
  ('ADMIN', 'album.view'),  ('ADMIN', 'album.manage'),
  ('ADMIN', 'file.view'),   ('ADMIN', 'file.manage'),
  ('ADMIN', 'video.view'),  ('ADMIN', 'video.manage'),
  ('ADMIN', 'vault.view'),  ('ADMIN', 'vault.manage'),
  ('MEMBER', 'recipe.view'),
  ('MEMBER', 'order.view'),
  ('MEMBER', 'album.view'),
  ('MEMBER', 'file.view'),
  ('MEMBER', 'video.view'),
  ('MEMBER', 'vault.view');
