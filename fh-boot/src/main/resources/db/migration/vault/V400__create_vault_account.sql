-- vault 域（版本号段 V4xx）—— v5 需求：跨平台账号密码保管

-- 刻意只有用户列的 6 个语义字段（名称 / 账号 / 密码 / 创建时间 / 更新时间 / 状态），
-- 不加备注、URL、验证码密钥、分组、收藏。需要时另开一条 V4xx 迁移。
--
-- 状态用**两态** deleted TINYINT，和 album_group 完全同构（可以挂 MyBatis-Plus @TableLogic），
-- 不用 recipe / album_image 的三态 status —— 账号没有"下架"语义，硬套三态只会多一个
-- 永远用不到的枚举值和一处手写过滤的坑（方案 §10 风险 5）。
--
-- 【为什么这个域必须有自己的表前缀和 Flyway 目录】
-- 它是全库唯一存明文凭据的地方，将来按域拆库时 vault 要单独一套库 + 单独的访问控制，
-- 表混在 album_* / recipe_* 里就拆不干净（方案 §3.2 约束 3）。
CREATE TABLE `vault_account` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `name`        VARCHAR(64)     NOT NULL                COMMENT '平台名，如 微信 / steam / QQ',
  -- name **不建唯一约束**：同平台多账号是真实场景（工作微信 + 生活微信），靠 account 区分。
  `account`     VARCHAR(128)    NOT NULL                COMMENT '账号 / 邮箱 / 手机号，明文（本身不是机密，且要能搜索）',
  -- AES-256-GCM 密文，格式 base64(iv[12] || ciphertext || tag[16])，见 VaultCipherManager。
  -- 入参上限 256 个字符；最坏情况是 emoji 口令（1 个字符 = 2 个 UTF-16 单元 = 4 字节 UTF-8），
  -- 256 字符 → 512 字节 → 12 + 512 + 16 = 540 字节 → base64 后 720 字符，所以列开到 1024。
  `password_enc` VARCHAR(1024)  NOT NULL                COMMENT '密码密文，任何查询接口都不返回它（实体上标了 select=false）',
  `deleted`     TINYINT         NOT NULL DEFAULT 0,
  `create_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '添加时间',
  `update_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '修改时间',
  PRIMARY KEY (`id`),
  -- 默认列表顺序是 update_time DESC，这个索引同时兜住 deleted=0 的等值过滤。
  -- 没给 name 建索引：没有任何查询按 name 等值过滤（关键词搜索是 LIKE '%kw%'，走不了索引）。
  KEY `idx_deleted_update` (`deleted`, `update_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='账号本（两态删除，密码加密存储）';
