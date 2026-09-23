-- user 域（版本号段 V5xx）：app_user 加登录口令，种子两行给初始密码。
--
-- 【为什么这一轮加密码】登录从"下拉选账号 + 填手机号"改成"下拉选账号 + 填密码"（个人中心那页
-- 可以改自己的昵称/手机号/密码）。手机号作为登录核对项到此退役，但这一列留着——它是个人资料，
-- 账号管理页还在展示。
--
-- 【为什么是哈希、不是密码本那套 AES-256-GCM】两个域对"口令"的需求正好相反：
-- 密码本要"存进去还能拿回明文给别人看"（reveal），所以必须可逆；登录只要"验一下对不对"，
-- 可逆加密在这里纯属多余——它意味着密钥一泄露，全库口令当场可读，而哈希没有"解不开"这回事。
-- 所以本域用的是 PBKDF2-HMAC-SHA256（JDK 自带，不引第三方依赖、也不需要新配一个密钥），
-- 每行一个随机盐。⚠️ 别"顺手统一"成密码本那个 VaultCipherManager。
--
-- 【格式】`pbkdf2_sha256$迭代次数$base64(盐)$base64(哈希)`，90 个字符，留 VARCHAR(128)。
-- 迭代次数写在串里而不是常量里，将来调高它不会让存量口令当场验不过（见 UserPasswordManager）。
--
-- 【NOT NULL，没有"还没设密码"这一档】新建账号必带初始密码（UserCreateRequest），所以一个连密码
-- 都没有的账号根本不该存在。列上的 ''（有人绕过应用直插 SQL）会被 matches() 的格式检查判成不匹配，
-- 天然登不进来，不需要为它设计一条分支。
--
-- 【种子的初始口令】两个人都是 123456，登录后在 B 端「个人中心」自己换掉。
-- 写死在迁移里而不是运行时生成，是为了让"新库一建好就能登录"这件事不依赖任何脚本步骤；
-- 那两串哈希是用同一套参数（200000 轮 / 随机盐 16 字节）离线算出来的，不是抄的样例值。
ALTER TABLE `app_user`
  ADD COLUMN `password_hash` VARCHAR(128) NOT NULL COMMENT '登录口令，PBKDF2 哈希（格式见 V502 文件头），不存明文、不可逆，任何查询接口都不返回它（实体上标了 select=false）' AFTER `phone`,
  MODIFY COLUMN `phone` VARCHAR(32) NOT NULL COMMENT '手机号，个人资料项（V502 起不再是登录核对项）',
  MODIFY COLUMN `name` VARCHAR(32) NOT NULL COMMENT '昵称，如 大宝。两端展示、"添加人"都显示它，也是登录页下拉框的文案';

-- 两个人按昵称认，与 V501 回填 creator_id 那一条同一口径。
-- update_time 显式写成原值：这两行到目前为止从没被"改"过，加列不该让账号管理页上的
-- 「添加时间」和「更新时间」突然错开成迁移那一刻。
UPDATE `app_user` SET `password_hash` = 'pbkdf2_sha256$200000$O0LjAEh8TKwAVag+KSugsA==$D4SGxCcs1cVeh70GHvWmH96ePzv2c4qM2L9CwA/vSes=', `update_time` = `update_time` WHERE `name` = '大宝';
UPDATE `app_user` SET `password_hash` = 'pbkdf2_sha256$200000$2J0d8rAIc8MT0YR9XGkVJw==$gD9z36s2HkI7wyOGKHJffQ/26y0g9ur5SJbzW1HSSE4=', `update_time` = `update_time` WHERE `name` = '小宝';
