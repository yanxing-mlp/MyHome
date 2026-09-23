-- user 域（版本号段 V5xx）：全家成员。
--
-- 【为什么单独一个域】"谁加的"这件事要能被相册/菜谱/文件/密码本四个域同时引用，
-- 而它们之间唯一的公共依赖是 fh-common（方案 §3 的依赖方向）。账号既不属于相册也不属于菜谱，
-- 塞进任何一个已有域都会凭空多出一条跨域边，所以开第五个域、占 V5xx 号段。
--
-- 【两态 deleted，不是三态 status】账号没有"下架"语义（对 C 端隐藏一个成员不是需求），
-- 与 vault_account / file_object 同构、挂 @TableLogic。判断口径抄密码本那条：
-- 只有"下架"这一档中间状态才值得上三态，两态就别叠两套删除机制。
--
-- 【name / phone 都不建唯一索引】这张表是软删的，唯一索引会让"删掉一个成员再用同名重建"直接撞库，
-- 而应用层判重只看不删的行——与 file_object.md5 那条"普通索引 + 应用层查 deleted=0"的理由一字不差。
-- 前端也挡一道（与分类/做法管理同一口径），后端这一道是为了绕开 UI 直接敲接口时仍然不脏。
--
-- 【phone 是登录凭据吗】不是。家庭场景下它只是"选错账号了"的一道确认，明文存、不进日志、
-- 也不参与任何加密（真正的口令在密码本域，那个域刻意不依赖本域）。
CREATE TABLE `app_user` (
  `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `name`            VARCHAR(32)     NOT NULL                COMMENT '昵称，如 大宝。两端展示、"添加人"都显示它',
  `phone`           VARCHAR(32)     NOT NULL                COMMENT '登录时校验的手机号，明文（不是凭据，见文件头注释）',
  `avatar_file_id`  BIGINT UNSIGNED DEFAULT NULL            COMMENT '头像，指向 file_object；为空时两端给默认头像',
  `role`            VARCHAR(16)     NOT NULL DEFAULT 'MEMBER' COMMENT 'ADMIN=可管理账号 / MEMBER=普通成员',
  `deleted`         TINYINT         NOT NULL DEFAULT 0,
  `create_time`     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time`     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_deleted_id` (`deleted`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='全家成员（两态删除）';

-- 种子：全家两个人。放在建表迁移里而不是 deploy/dev-seed.sh 里，因为下面 V501 的洗数据
-- 要按昵称取大宝的 id 回填七张表——库里没有这两行，洗数据就洗不出东西。
-- dev-seed.sh 只造演示数据（相册/菜谱/订单），成员是"系统配置"级别的东西，跟着 schema 走。
INSERT INTO `app_user` (`name`, `phone`, `role`) VALUES
  ('大宝', '15170212308', 'ADMIN'),
  ('小宝', '15079077917', 'MEMBER');
