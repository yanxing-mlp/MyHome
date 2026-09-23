# family-home-server

家庭 Home 后端：相册 / 菜谱 / 密码本（vault）/ 文件 / 账号（user）五个业务域 + 统一启动模块。

- 技术栈：Spring Boot 3.5 · Java 21 · MyBatis-Plus · Flyway · MySQL 8
- 端口：`8080`（无 context path）
- 接口前缀：`/api/b/**`（B 端管理）与 `/api/c/**`（C 端 h5）**两套独立路径**。2026-09-21 起 C 端不复用 B 端接口：每个域各有一个 `controller/c/` 里的 `@RestController`，路径与返回体按 C 端页面裁剪，**Java 层仍调用同一套 service**（复用代码 ≠ 复用接口；这条口径来自用户明确要求"C 端不要复用 B 端的接口"）。B 端零调用方的那几条已整条搬到 `/api/c`，见下面的"C 端接口"一节
- **身份怎么传**这条见下面"账号体系"一节。2026-09-22 起身份凭据是**服务端签发的 HMAC Bearer token**（`Authorization: Bearer <token>`），不再是可伪造的 `X-User-Id` 请求头——那个头已整条下线、**没有任何回退**（留回退等于把越权口子重新打开）。判权限的口径不变：创建行的接口都要求登录身份（要往 `creator_id` 里写东西的那 8 个），账号管理那 **4** 个（列表 / 新建 / **改角色** / 删除）还额外要 ADMIN；`/me` 与 B 端个人中心那三条也只要"有身份"、不判角色；v14 相册个人列表无身份 401，B/C 裸 ID 读写均校验分区/属主，跨区/跨属主 404，详见接口表。其他业务域尚未全面做资源属主保护。**ADMIN 对别人账号的全部权力是"加人""删人""定谁是超管"三件事**：除了 `role` 那一列，改不了任何人的昵称、手机号、口令，也看不到口令

- **口令怎么走线上**（2026-09-22 起）：五处口令出入口的**请求体/响应体里只有传输层密文**，服务端在业务入口解密，存储层（PBKDF2 哈希 / vault 的 AES-GCM）一点没改。实现是 `fh-common` 的 `TransportCipher` + 配置项 `fh.transport-crypto.salt`（与前端构建期的 `VITE_FH_TRANSPORT_CRYPTO_SALT` 成对）。整节见下面"口令的传输段加密"

## 业务名称去重（2026-09-22）

创建、修改和数据库并发写入采用同一范围：菜名、菜品分类、做法分组全局唯一；做法选项在组内唯一；相册分组在 FAMILY 或当前用户的 PERSONAL 内唯一；存活账号昵称、手机号分别唯一；文件分类名称和密码本「名称 + 账号」分别在 PUBLIC 或当前账号的 PRIVATE 分区内唯一。下架继续占名，删除释放名称，编辑排除自身。比较遵循现有 MySQL `utf8mb4_0900_ai_ci`（忽略大小写和重音），首尾普通空格通过 TRIM 统一；不改变历史展示名。不限制密码内容、文件原名或多张图片使用同一城市。

迁移 `V105 / V212 / V318 / V403 / V503` 通过生成列及唯一索引兜住并发；删除行的唯一键为 NULL，允许反复删除重建。执行前已只读审计本机存量，无范围内重复，不自动删除、合并或改名。其他环境若有存量冲突，建索引会失败，需先人工确认数据处理口径，不能跳过迁移。

`POST /api/b/validation/duplicate` 是表单只读聚合预检，返回 `Result<boolean>`，true=重复；请求 `{kind,name,account?,scope?,excludeId?,groupId?}`，kind 为 `RECIPE / RECIPE_CATEGORY / PRACTICE_GROUP / PRACTICE_OPTION / ALBUM_GROUP / USER_NAME / USER_PHONE / VAULT_ACCOUNT / FILE_CATEGORY`。仅查唯一键、不受分页或搜索影响，不返回命中对象。手机号放 name，密码本需同时传 name/account，个人相册属主从身份取，选项传 groupId。接口要求登录；账号新增预检要求管理员，个人资料仅能排除自己。采用 POST 避免手机号/账号出现在 URL，不接收或比较密码。预检不预占名称，提交仍需校验。`GlobalExceptionHandler` 将唯一键异常转为 409 + 对应中文，不记录可能含手机号、账号的 SQL 异常原文。

本轮后端验证：JDK 21 全模块 package 通过；本地五份迁移均 `success=1`，九条唯一索引已核对。`DuplicateExceptionHandlerTest` 10 例、真实 MySQL 的 `DuplicateValidationIntegrationTest` 45 例全部通过，涵盖八类 HTTP 并发新增、七类并发编辑、九类直接 SQL 约束、同名/重音/首尾空格、下架占名、反复删除重建、相册三分区、密码内容可复用、选项交换保 ID 与失败回滚，以及并发编辑不能复活已删除菜品。测试夹具按随机前缀限定清理，数据库复查残留为 0；未操作真实文件或旧任务遗留数据。

菜品编辑与删除共用行锁，防止旧状态回写重新占名；做法更新先锁分组再锁选项，交换名称时保留选项 ID。dev 日志显式将 user/vault 的 dao 包设为 INFO，避免继承父包 DEBUG 而记录手机号、账号和口令密文参数。

重跑真实数据库测试（显式启用，需本地 MySQL 8；从本仓库执行）：

```bash
FH_RUN_DB_TESTS=true mvn -pl fh-boot -am \
  -Dtest=DuplicateValidationIntegrationTest,DuplicateExceptionHandlerTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

## 模块结构

```
family-home-server
├── fh-common                  # Result/PageResult、ErrorCode、BizException、ContentStatus 三态枚举、TransportCipher（口令传输段加解密，2026-09-22）
├── fh-module-file             # 文件域：图片上传、MD5 秒传（硬链接）、缩略图、静态映射、文档文件管理
│   ├── fh-module-file-api     #   对外门面 FileFacade / StorageClient（其他域只依赖 api）
│   └── fh-module-file-biz     #   实现 + FileController / DocumentFileController / FileCategoryController
├── fh-module-album            # 相册域：分组 / 图片 / 城市统计
├── fh-module-recipe           # 菜谱域：菜谱 / 分类 / 做法字典 / 点餐购物车
├── fh-module-vault            # 密码本域：AES-256-GCM 加密存储口令
├── fh-module-user             # 账号域：全家成员、口令登录（PBKDF2 哈希）、个人中心自助改资料/改口令、"添加人"的 id→昵称字典
└── fh-boot                    # 启动模块：FamilyHomeApplication、全局异常、WebMvc 配置（含 CurrentUserInterceptor）、Flyway 脚本
```

依赖方向：`域-biz → 域-api / fh-common`，跨域只经 api 门面（如 album 调 `FileFacade`），不允许 biz 互相依赖。

## 环境要求

- JDK 21（Spring Boot 3.5 必需）
- Maven 3.9+
- MySQL 8.0（本机 3306）

## 快速开始

```bash
# 1. 建库（Flyway 会自动建表）
mysql -uroot -e "CREATE DATABASE IF NOT EXISTS family_home DEFAULT CHARSET utf8mb4;"

# 2. 全量构建（spring-boot:run 用的是本地仓库里的 jar，改过其他模块必须 install 而不是只 package）
mvn clean install -DskipTests

# 3. 启动（dev profile）
cd fh-boot && mvn spring-boot:run -Dspring-boot.run.profiles=dev

# 4. 验证
curl http://localhost:8080/api/b/health
```

dev 库连接参数写死在 `application-dev.yml`（`127.0.0.1:3306/family_home`，root 无口令），只适合本机。

## 配置说明

| 配置 | dev | prod |
| --- | --- | --- |
| `fh.storage.root` | `~/family-home-data/files` | `/data/family-home/files`（需与容器挂载一致） |
| `fh.storage.url-prefix` | `/files` | `/files` |
| `fh.vault.password-key` | 仓库内置 dev-only 密钥 | 仅环境变量 `FH_VAULT_PASSWORD_KEY`，漏配启动即失败 |
| `fh.auth.token-secret` | 仓库内置 dev-only 密钥（可用 `FH_AUTH_TOKEN_SECRET` 覆盖） | 仅环境变量 `FH_AUTH_TOKEN_SECRET`，**漏配（空串）启动即失败**（`SessionToken` 装配时抛 `IllegalStateException`）。它签发/校验登录令牌 `Authorization: Bearer`，泄了=任何人可伪造任意账号身份，与 vault 密钥同级敏感，**不要打进日志**。生产用 `export FH_AUTH_TOKEN_SECRET="$(openssl rand -base64 48)"` |
| `fh.transport-crypto.salt` | 仓库内置 dev-only 盐（可用 `FH_TRANSPORT_CRYPTO_SALT` 覆盖） | 仅环境变量 `FH_TRANSPORT_CRYPTO_SALT`；**漏配（空串）与 vault 密钥同样启动即失败**（`TransportCipher` 构造器抛 `IllegalStateException`）。**必须与前端构建期的 `VITE_FH_TRANSPORT_CRYPTO_SALT` 逐字相同**，不一致的表现是"口令无法解密"那句 400。不是秘密（盐随前端产物公开），但**也不要把它打进日志** |
| multipart 上限 | 单文件 20MB / 请求 100MB | 同左 |

prod 的数据库连接全部走环境变量（`MYSQL_HOST/PORT/DATABASE/USER/PASSWORD`），日志落 `/data/family-home/logs/server.log`，**只保留 7 天**（按天滚动，超期归档由 logback 自动删，详见下面「日志口径」）。

⚠️ 2026-09-22 起 prod 多了一条**功能**前置（不是安全加固）：**页面必须走 https**。前端口令加密用的是 `crypto.subtle`，它只在 secure context 暴露，`http://` 下连密文都造不出来，表现是"两端登录页点不动"而不是"退化成明文"。详见下面「口令的传输段加密」一节。

> dev 密钥随仓库公开，**不要把真实口令录进 dev 库**；要当真密码本用需 `DROP DATABASE family_home` 重来并换自己的密钥（`openssl rand -base64 32`）。演示数据用 `deploy/dev-seed.sh` 造（走接口加密，别直插 SQL）。

## Flyway 迁移约定（重要）

一域一目录：`db/migration/{file,album,recipe,vault,user}`，五个目录共用一张 history 表，所以**版本号必须全局唯一**，按域占号段：

```
file -> V1xx    album -> V2xx    recipe -> V3xx    vault -> V4xx    user -> V5xx
```

- 已应用的迁移文件不能再改（checksum 校验会直接启动失败），加列只能新开一条。文档文件那条路就是照这条走的：
  V101 建 `file_category`（硬删 + `uk_name`，与 `recipe_category` 同口径）并给 `file_object` 加可空列
  `category_id`，V102 把 `mime_type` 从 64 放宽到 128（docx 的规范 MIME 有 71 字符），都不动 V100。
  user 域的 `V502`（给 `app_user` 加 `password_hash` 并给两行种子填哈希）也一样：`V500` 建表那一版一个字没碰，
  新列 `NOT NULL` 但**不给默认值**——一个连口令都没有的账号不该存在，所以靠同一条迁移里的 `UPDATE` 当场补齐；
  顺手 `MODIFY` 了 `name`/`phone` 的列注释（手机号从"登录核对项"改成"个人资料项"），注释归注释，列类型和约束都没动。
  ⚠️ 同一条迁移里那句"新建账号必带初始密码（`UserCreateRequest`）"（第 16 行注释）已经过期：2026-09-21 起初始口令是服务端常量，
  请求体里没有 `password`。但**已应用的迁移不能改**，这句就让它错着，以本节和代码为准。
- **"改代码之前攒下的脏数据"用一次性清洗迁移解决，之后靠代码口径不再产生**，已用的四条：album `V205` 清指向已软删图片/分组的
  `album_image_group_rel` 孤儿行（软删不触发 `ON DELETE CASCADE`）、`V206` 给"有 `group_id`、没关联行"的历史图补关联、
  `V207` 删掉 `album_image.group_id` 这列（归属此后只有关联表一处答案）、file `V103` 标删没有任何业务对象引用的 `file_object` 行。
  `V103` 的保护条件是 `recipe_order_item.cover_url LIKE '%<basename>%'`：订单明细存的是 URL 快照，业务表不引用了历史单还在用，
  不挡住就会把已完成订单卡上的图删成 404。**迁移删不了磁盘**，这类清洗跑完要按 `fh.storage.root` 手动 unlink 主图 + `_t` 缩略图。
- **换列不是改列：一条迁移做完"加新列 → 回填 → 删旧列"**。album `V208` 就是这个形状：分组要能上下架，两态 `deleted` 装不下，
  于是加 `status VARCHAR(16) NOT NULL DEFAULT 'ON_SHELF'` → `UPDATE ... SET status='DELETED' WHERE deleted=1` → 删 `deleted` 列和
  `idx_deleted_sort`。默认值给 `ON_SHELF` 而不是 `NOT NULL` 无默认，是为了让加列这一步对存量行无损；索引不补新的，理由见上面的数据状态口径。
  同一时刻 `AlbumGroupDO` 上的 `@TableLogic` 必须一起摘掉，否则 MP 还在按一个已经不存在的列拼条件。
- **只加一档新枚举，用"加列 + `NOT NULL DEFAULT` 那个存量值"一条迁移做完，不需要回填 `UPDATE`**。album `V210` 是这个形状：
  `ADD COLUMN scope VARCHAR(16) NOT NULL DEFAULT 'FAMILY'` —— 存量每本相册本来就是全家共享的，默认值就是它们的正确值，
  所以一句 `UPDATE` 都不写，新库与老库在同一时刻收敛到同一个形状。`AlbumScope` 放在 `fh-common`（与 `ContentStatus` 同一处），
  是**普通 Java enum**（不实现 `IEnum`、不配 `@EnumValue`），DO 上直接映射，MP 按枚举名读写——库里那两个字符串就是 `FAMILY` / `PERSONAL`。列注释同步写"家庭相册/个人相册"，`SHOW CREATE TABLE` 里能看出两档各是什么。
- **v14 / V211 不只是加默认值**：给 `album_image`、`album_city` 加 `scope + owner_id`，按家庭（owner=0）与个人账号拆历史共享 image，并重连 `album_image_group_rel`；同 owner 多组仍只保留一行。保留城市/EXIF/状态/置顶、真实上传人、创建及更新时间；已删组的私人图片也保留 owner，不转为家庭。拆行暂共享 fileId，删除路径必须先检查全 album 未删除图片的活引用，保护另一侧文件。城市按分区重建，后续只重算当前分区。独立 MySQL 测试库执行 V211 的 12 项断言已通过，**不等同于本地启动或浏览器验收**。
- 文档不另建业务表：元数据 `file_object` 全都有，用 `biz_type = document` 与图片行区分，文件类型看 `ext` 就够
- **视频也不另建业务表**（2026-09-23）：同文档，用 `biz_type = 'video'` 与图片/文档行区分。视频相对文档没有多出来的业务语义（元数据 `file_object` 全都有），所以唯一要动的是 V106 那条只放行 `document` 进 PRIVATE 的 CHECK——**`V107`（file 号段）`DROP chk_file_object_private_document` 换成 `chk_file_object_private_partition CHECK (scope='PUBLIC' OR BINARY biz_type IN ('document','video'))`**，让私人视频落得下；图片行恒 PUBLIC/0，不受影响。应用时 MySQL 会报一句 `'BINARY expr' is deprecated` 的良性 warning，迁移 `success=1`，已应用的迁移绝不回头改。**视频没有走 V600**（那是早期计划的号段，实际落在 file 号段的 V107）
- `out-of-order: true` 是号段方案的必要配套，不是配置错误
- **每张表都必须同时有 `create_time` 和 `update_time`**，关联表（`*_rel`）、明细表（`recipe_order_item`）、字典子表也不例外，统一用
  `DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)` / 再加 `ON UPDATE CURRENT_TIMESTAMP(3)`；实体 DO 同步映射这两个字段，插入时不用手工赋值（MP 默认不写 null 列，交给 DB）。
  存量缺列已用 V204 + V308 补齐。
- **`selectById` 改字段后 `updateById(entity)` 不会刷新 `update_time`**：MP 默认只写非 null 字段，而实体带着刚查出来的
  旧 `update_time`，显式值压过列上的 `ON UPDATE CURRENT_TIMESTAMP`，于是改了内容时间却不动（分类/做法分组改名都是这个表现）。
  现在全仓 14 处 `updateById` 都是这一种写法，要留痕就显式 `setUpdateTime(now)` 或改用只 `set()` 目标列的
  `LambdaUpdateWrapper`（`changeStatus` 走的就是后者）。家庭场景不靠这一列排序/审计，所以先记下不批量改。

## 数据状态口径

- `recipe` / `album_image` / `album_group` 是三态 `status`（ON_SHELF / OFF_SHELF / DELETED）：**不能用 `@TableLogic`**，每个查询必须手写 `status <> 'DELETED'`
- `vault_account` / `file_object` 是两态 `deleted`：实体字段标 `@TableLogic`。**相册域已经没有 `@TableLogic` 了**——`album_group` 原本也是两态，2026-09-20 因为分组要能上下架，`V208` 把 `deleted` 列换成 `status`（加列 → `deleted=1` 回填成 `DELETED` → 删列删 `idx_deleted_sort`，不补新索引：`status <> 'DELETED'` 是范围谓词，索引帮不上）。代价是那句手写过滤从"MP 自动加"变成"每条查询自己记得写"，`AlbumGroupService` 里的读点全靠它（**不写死处数**：那一串 `selectList` 每加一条筛选就长一个，数一遍迟早数歪；`selectById` 那几处刻意**不带**这个条件——它们要的是"这一行存在吗"，判"存在但已删除"由各自的 service 方法自己做，`requireVisibleOnClient` 对不存在或 C 端不可见的分组统一抛 404）
- 因此 MyBatis-Plus 全局**刻意不配** `logic-delete-field`，两种机制混用会互相干扰
- **相册分区（v14）**：分组继续使用 V210 的 `scope + creator_id`；V211 为 `album_image`、`album_city` 增加 `scope + owner_id`（FAMILY owner=0，PERSONAL owner=当前账号）。`album_image.creator_id` 始终保留真实上传人，不代替 owner。分组、图片、城市全部按分区隔离，禁止跨区/跨属主关联与复用 image；同分区可一行 image 关联多组。scope 与三态 status 正交：B 端可管下架数据，私人分组卡仍无上下架开关；C 端只看上架图/组，ungrouped 仍仅 FAMILY。身份凭据自 2026-09-22 起是服务端签发的 HMAC Bearer token（不再是可伪造的 `X-User-Id` 头），但静态 URL 仍不鉴权，**不能称为完整安全隔离**。
- **标了 `@TableLogic` 的那一列不在 `updateById` 的 SET 列表里**（MP 主动排除，避免绕过删除条件）。想软删只能调
  `deleteById` / `delete`，它才会翻译成 `UPDATE ... SET deleted=1 WHERE id=? AND deleted=0`；
  `setDeleted(1) + updateById` 会静默不生效。`FileFacadeImpl.markDeletedAndPurge` 就是这个坑：标记丢失、
  物理文件照删，记录还在但字节没了，链接全成 404。
- `recipe_order.status` 是**三态** `PENDING`（待制作）/ `COMPLETED`（已完成）/ `CANCELLED`（已取消）：没有"制作中"。
  两个动作（"标记已完成"、"取消订单"）都只从待制作推得过去，B/C 两端都出按钮；**已完成与已取消都是定稿档，
  两端都没有改回待制作的入口**（原先只挂在 B 端点单列表的 `POST /{id}/reopen` 已整体下线：接口、service 方法、
  前端按钮一起拆掉，误点了只能再下一单）。两个方向两个接口（`/complete`、`/cancel`），
  都用条件更新（`WHERE id=? AND status='PENDING'`）。
  **已取消不能反悔**：B/C 端的已取消单都不给任何改档入口，只有明细快照可看（要重吃那份菜走"再来一单"）。
  影响 0 行时要回读一次再分流：已是目标态按幂等返回成功，单子停在另一档（如对已取消单点完成）报业务错，
  只有查不到订单才报 404 语义的错——不会对着取消单假装"改成功了"。
  改档只动 `status`，不碰明细与 `total_qty`。
  完成时刻就是 `update_time`（不另开 `finish_time` 列，前端也只展示下单时间）；
  取消同理不留 `cancel_time`。订单表没有 `deleted` 列，快照账本只留不删。
- **待制作的单还能继续加菜**（`POST /{id}/append` 把当前购物车并进这一单），已完成、已取消都等于定稿，新版本加菜一律业务错；此前成功消费的同版本重试仍按回执幂等返回
  （文案分开："已经完成了，不能继续加菜" / "已经取消了，不能继续加菜"；两档都是定稿，没有"先改回待制作再加菜"这条路）。
  加菜最后那步 `total_qty` 更新同样带 `status='PENDING'` 条件：万一这期间被人点了"已完成"，影响 0 行、整个事务回滚，
  不会出现"已完成的单被偷偷加了菜"。合并口径与"再来一单"对称：**一菜一行、同菜累加份数、做法以本次为准整体覆盖**；
  明细里原有的菜名与封面快照不追改（中途改名、换图只影响新插的行）。
- `recipe_practice_rel` 一行 = 这道菜绑了某个做法分组，外加两项配置（V312）：`required`（这道菜必须在这组里选一个做法）和
  `default_option_id`（进详情浮层时预选哪个选项，NULL=不预选）。存量按"必选 + 组内第一个选项"回填，与改造前 C 端无条件取
  `options[0]`、且不给取消的行为完全一致。**必选只由 C 端拦**（不给取消已选中的选项、没选时"加入购物车"禁用），
  服务端不校验购物车里的做法选择——它连 optionId 属于哪个分组都不查，只补一条必选校验会变成半套口径。
  C 端还把"有做法可挑的菜"整道拦在详情浮层：卡片上的"+"只要这道菜绑了组内还有选项的做法分组就不直接加购，
  改成先开浮层（2026-09-19 从"只拦必选又没默认选项"放宽到"绑了就先开详情"，浮层内部仍只强制必选组）。
  默认选项日后被字典编辑删掉时不清空这一列，读侧（B/C 端）都按"该 id 不在字典里 = 无默认"处理。
- 统一响应：`Result<T>` = `{ code, message, data, success }`，成功 code `"0"`；异常由 `GlobalExceptionHandler` 转成语义化 HTTP 状态码（404/405/409/413/415/400/500）。**三类"根本不是服务端故障"的都要单独接住**，否则会被 `Exception` 兜底吃成 500 并刷一坨 ERROR 栈：`NoResourceFoundException`（路径不存在 → 404）、`HttpRequestMethodNotSupportedException`（路径还在、方法不匹配 → 405，如只剩 PUT/DELETE 的 `/album/groups/{id}` 上发 GET）、`MissingServletRequestPartException`（少传 part → 400）。老版本那个 `NoHandlerFoundException` 在这个配置下不会发生（静态资源映射开着，兜不到 handler 的请求一定落进 `ResourceHttpRequestHandler`），为它写的分支已删

## 账号体系与「添加人」（2026-09-21 起）

这一节把方案里那句"一期完全不做鉴权、靠 nginx 内网白名单兜底"（旧 §0 口径表 / §1 一期不做清单）推翻了大半：全家几个人共用，"这条数据是谁加的"和"这台设备当前在替谁操作"是同一件事的两面。**做掉的是"账号是谁 + 登录时核一次口令 + 之后每个请求都验服务端签发的凭据"**——2026-09-22 起口令仍只在 `/login` 那一次被比对，但比对通过后服务端签发一枚 **HMAC-SHA256 Bearer token**，之后每个请求都靠 `Authorization: Bearer <token>` 验签，不再是可伪造的 `X-User-Id` 头（那个头已整条下线、无回退）。见下一条。

- **身份怎么传**：请求头 `Authorization: Bearer <token>` → `CurrentUserInterceptor`（`fh-boot`，注册在 `/api/**`）验签 → `CurrentUserHolder` 的 ThreadLocal。`afterCompletion` **必须** `clear()`：Tomcat 工作线程复用，不清就是下一个请求顶着上一个人的身份写库。**三档要分清**：① 根本没带 `Authorization` → 按"匿名"放行，不 401（读接口本就允许匿名，需要身份的写接口由 `requireUserId()`/`requireAdmin()` 自己挡）；② 带了 `Authorization` 却不是 `Bearer ` 前缀 → 记一条 warn、同样按匿名放行；③ 带了 Bearer 但**验签失败 / 过期 / 格式烂** → `SessionToken.verify()` 一律抛 `USER_NOT_LOGIN`（401，不细分原因，免得给攻击者探针），前端 HTTP 层收到 401 清本机登录态回登录页——与"账号被删"（`resolve()` 抛 `USER_NOT_FOUND`，也是 401）走同一个自愈分支。**验签通过**才拿令牌里的 userId 去 `AppUserService.resolve()` 换现值。**token 的形状**：`v1.<userId>.<exp epoch 秒>.<base64url_nopad(HMAC-SHA256(前三段))>`，TTL 7 天（`SessionToken.TTL_SECONDS`），签名用定长时间比较（`MessageDigest.isEqual`）；payload 只含 `userId + exp`，**角色不在 token 里**——每次请求都现查 DB 拿最新 `role`，所以改角色 / 删账号当场生效，不需要对方重新登录（token 里没有"权限快照"可被陈旧利用）。密钥走 `fh.auth.token-secret`（dev 一个公开默认值、prod 只从环境变量 `FH_AUTH_TOKEN_SECRET` 读，缺失/短于 32 字符/非 ASCII 即 `IllegalStateException` 启动失败，与 vault key、transport salt 同一套口径）。
- **拦截器只认人、不做访问控制**（它对匿名请求放行，所以也没有白名单要维护——`/login`、`/options` 本来就不带凭据），判权限的地方只有一处：service 层。调 `requireUserId()` 的包括**创建行的那 8 个方法**（`FileFacadeImpl.upload`、文档上传、建分组、绑定图片、建菜、建分类、下单、建密码本条目，它们要往 `creator_id` 写东西；没身份 → `USER_NOT_LOGIN` / 401 "请先选择登录账号"），**外加 user 域自己的四条自助接口**（`me`/`profile`/`updateProfile`/`updatePassword`——它们不动 `creator_id`，但"我是谁"就是这一条的全部语义），以及 v14 相册 PERSONAL 列表（分组、图片、城市及 C 端 covers/options，无身份 401）。B/C 相册裸 ID 读写均先验分区/属主，跨区、跨属主及无身份访问私人裸 ID 统一 404；C 端另验上架图/组（见接口表）。账号管理那 4 个调 `requireAdmin()`（列表 / 新建 / 改角色 / 删除；不是 ADMIN → `USER_FORBIDDEN` / 403 "只有管理员能管理账号"）。**这一道 `requireAdmin()` 只管"看名单、加人、删人、定谁是超管"，不管"替别人改资料"**——管理员能替别人写的字段全库只有一个 `role`（2026-09-22 放开的，见下面两条），昵称/手机号/口令一格都没有。**不能再用「改/删都不要求身份」概括相册**：v14 相册上下架、改名、置顶、删除、关联及批量操作均受请求分区/属主约束，PERSONAL 不因不修改 creator_id 而免校验。密码本与文档（含分类、下载）的全部入口现在通过 `DataPartition` 要求身份并限定 PUBLIC/0 或 PRIVATE/当前账号，无身份 401、跨分区/属主 404，ADMIN 无私人数据读取特权；订单改档等其他域校验不变。⚠️ **越权口子已在 2026-09-22 堵死**：旧版那个自报的 `X-User-Id` 头任何人手搓一个 `X-User-Id: 1` 就能冒充大宝（ADMIN）列账号、建/删账号、改角色、reveal 密码本明文、读写他人私人分区——现在身份只认服务端签发的 HMAC token，伪造头不再被读取。token 密钥泄露仍是风险（与"网络边界仍需 §8.3"并列），但已不是"零成本冒充"。另外 `/files/**` 压根不进拦截器（只拦 `/api/**`），图片要能直接塞进 `<img src>`。
- **登录 = 下拉选一个账号 + 填这个账号的密码**（2026-09-21 起；原先那版"填手机号"已换掉，手机号退成纯资料项）。后端只比对"这个 id 的账号在不在"和"口令对不对得上"两件事：`AppUserService.login()` 刻意查两次——`selectById` 拿那一行（**它取不到哈希**，见下条），再单独 `selectPasswordHashById` 把哈希捞出来直接进 `matches()`，产物不停在字段或返回值里。两种失败的说法不一样（"账号不存在，请重新选择" vs "密码与该账号不匹配"），这里不需要防枚举——家里几个人本来就在下拉框里全列着，只需要让用户知道下一步该改哪个输入框。**两句都是 400**，不是 401：`USER_PASSWORD_MISMATCH` 若映射成 401，前端 HTTP 层会当成"登录态失效"清掉本机缓存，那句中文就被吞了。**登录成功签发一枚 token**：返回体在 `{id,name,role,avatarUrl}` 之外多一个 `token` 字段，前端把 token 单独存 `localStorage['fh-auth-token']`（与展示对象 `fh-current-user` 分两格存，见 web README），之后每次请求由 HTTP 层带 `Authorization: Bearer <token>`，"登录一次即可"，7 天内免重登。
- **口令只存 PBKDF2-HMAC-SHA256 哈希**（`UserPasswordManager`，JDK 自带）：`pbkdf2_sha256$200000$base64(盐)$base64(哈希)`，20 万轮、每行 16 字节随机盐，`matches()` 用常数时间比较。**为什么不复用密码本那套 AES-256-GCM**：两个域对"口令"的需求相反——vault 要"存进去还能拿回明文"（reveal），登录只要"验一下对不对"；可逆加密在这里等于"密钥一泄露，全库口令当场可读"。参数写成串里的一段而不是纯常量，是为了将来调高迭代次数不把存量口令一次性废掉（那句"密码不对"在本系统没有自助恢复路径）。**没有新增密钥/配置项**，也没有为它引依赖（离线 `mvn -o` 仓库里没有 spring-security）。`AppUserDO.passwordHash` 标了 `@TableField(select=false)`，所以 `selectById`/列表查询从根上捞不到它，全库唯一的读者是 `AppUserService.login()` 与 `updatePassword()` 各一次 `selectPasswordHashById`——**哈希出不了 DAO**，任何查询接口都不会返回它。
- **个人中心是 B 端的一页，三条 `/api/b/user/profile*` 只要求身份、不判 ADMIN**（MEMBER 也管得了自己的昵称/手机号/口令，C 端没有这一页、也就没有 `/api/c/user/profile`）。`updateProfile` 改的目标恒等于当前登录者，既没有越权对象、入参里也就没有 `role` 和 `password`；`updatePassword` **要原密码**——这一道是给"设备没锁屏被家人顺手拿起来"准备的。**昵称、手机号、口令这三项在整个系统里只有这一个入口，管理员没有第二条路**：原来那条 `PUT /api/b/user/{id}`（ADMIN 替别人改资料/重置口令，不看原密码）连同 `UserUpdateRequest` 已在 2026-09-21 整条下线，服务端从此没有"替别人改这三项"这个动作（2026-09-22 管理员侧唯一放回来的是 `role` 那一列，与这三项无关，见下一条）。⚠️ 下线后该路径只剩 `DELETE /{id}`，所以对 `/api/b/user/{id}` 发 PUT 会返 **405 METHOD_NOT_ALLOWED**（"PUT 方法不支持"）而不是 404——路径模板还在，别把它当成"接口还活着"。代价要说清楚：**成员忘了口令只能"删号重建"**，而删号会把这个人加过的东西在"添加人"一列洗成"已删除账号"（历史行只存 id）。这是"超管也拿不到任何人口令"这条规则的定价，不是漏掉的功能；要补也得补成"本人凭手机号自助重置"这类不经过管理员的路，别开回管理员重置。
- **两种"填密码"的场景语义各不同**：登录不 trim、不判长度（收最短长度会变成"库里存得下、登不进"的死账号，`maxLength=64` 只是 DOM 层护栏）；个人中心新密码 6–64 位（**校验放在解密之后**：2026-09-22 起 DTO 上那个 `@Size` 已摘，那一格在线上装的是一串**比明文还长**的 base64，规则搬进 `AppUserService.requireNewPasswordLength`，见下面「口令的传输段加密」一节）。**新建账号这一格根本没有口令**：`UserCreateRequest` 里没有 `password` 字段，服务端固定用 `AppUserService.DEFAULT_INITIAL_PASSWORD = "123456"` 算哈希再落库（写成常量而不是配置项：它跟 `V500`/`V502` 种子里那个 123456 是同一个值，散成配置只会让"新库到底能不能登"变成一件事的两处）。因为哈希是服务端算的，**管理员连自己刚建的那个账号的口令都不接触**；Jackson 对多传的 `password` 键是静默忽略，所以手工构造请求体也设不进口令。种子两行（`V502`）与新建账号共用 `123456`，登录后自己去个人中心换掉。**明文与哈希都不进日志**，`PBEKeySpec` 用完 `clearPassword()`。
- **`creator_id` 只存 id，不存昵称、也不 join**：七张表（`file_object`、`album_group`、`album_image`、`recipe`、`recipe_category`、`recipe_order`、`vault_account`）加的都是可空 `creator_id`。昵称由两端各自拿 `{id,name}` 字典在前端解析（B 端 `GET /api/b/user/options`、C 端 `GET /api/c/user/options`，**两条路径、同一个 `options()` 方法、同一个 `UserOptionVO`**）——相册/菜谱/文件/密码本四个域之间唯一的公共依赖是 `fh-common`，为了一列显示文字凭空开出四条跨域 join 不值当。字典里查不到（账号已软删）显示"已删除账号"；`creator_id` 为 NULL 就整段不渲染，不放占位符。
- **存量数据一次性洗成大宝**：`V501`。放在 user 域而不是各域自己回填，因为 Flyway 按版本号顺序应用，各域那条加列迁移（file/V104、album/V209、recipe/V316、vault/V402）跑的时候 `app_user` 还不存在。取大宝的 id 用 `name='大宝'` 子查询而不是写死 1，查不到就是 NULL（前端不渲染，不会把添加人挂到一个不存在的 id 上）；`WHERE creator_id IS NULL` 保证只补空的、不覆盖已洗好的。
- **只有 ADMIN 能管账号；管理员能替别人写的字段全库只有一个 `role`**（2026-09-22）。写它的是 `AppUserService.updateRole()`，路径 `PUT /api/b/user/{id}/role`，入参 `UserRoleUpdateRequest` 只有一个 `@NotBlank` 的 `role`。**没有开回"把整行改掉"的 `PUT /{id}`**——那条 2026-09-21 就删了，放回来等于顺手给昵称/手机号/口令开了第二条路（`UserUpdateRequest` 那个类一旦复活，`password` 字段就会有人想填）。这一格是"谁能管理账号"这条唯一权限规则的唯一开关，所以它必须在管理员手里；`updateRole()` 自带两道护栏（与 `delete()` 不同——删除那一条 2026-09-22 起改成"目标是 ADMIN 一律不可删"，见下一条）：先 `requireAdmin()`，再校验值只能是 `ADMIN`/`MEMBER`（400 "角色只能是 ADMIN 或 MEMBER"；**没有 `@Pattern`，全仓库没有先例，白名单留在 service 层**），再 `requireExists(id)`，然后"最后一个管理员"（403 "至少保留一个管理员，否则没人能再管理账号"）与"不能改自己"（403 "不能修改自己的角色，请让另一位管理员操作"）。**不自降这一条是硬需求**：本机缓存里的 `role` 只在登录和 `/me` 时刷新，管理员把自己降掉之后，前端那份缓存还挂着 ADMIN，结果是"菜单照旧露着、点进去每个接口都 403"——比看不到菜单更让人困惑。写库时 `new AppUserDO()` 只填 `id` + `role` 再 `updateById`（**不是把查出来的整行 set 一下再存**，那样等于把哈希那一列的读写路径又摸了一遍）。**改角色不影响"能不能登进来"**，只影响"能不能管账号"，所以它跟删除是两件不同的事。
- **删除仍是管理员对"别人能不能登进来"唯一的控制权**：没有编辑、没有重置口令，所以这一条得留着（家里有人锁死自己的口令时它是唯一的出口）。**删除只挡一条（2026-09-22 收紧）：目标是 ADMIN 一律不可删**（`USER_FORBIDDEN` "管理员账号不能删除，请先取消其管理员角色"）——要删某个管理员，得先由另一位管理员在「角色」列把他降成普通成员，降下来那一行才会出现删除按钮。这一条同时天然挡住了"删自己"（能走到 `delete()` 的操作人必是 ADMIN，他那一行正是 ADMIN），所以原来单独的"不能删自己"分支与"至少留一个管理员"计数都已删除：管理员根本删不掉，最后一个管理员自然还在。**头像由管理员在建号那一刻先定一张**（`avatarFileId` 只有 `POST` 收），建完之后管理员就改不了它了——但**本人可以在个人中心自己换**（2026-09-23 起 `PUT /profile` 也收 `avatarFileId`，见下面接口表那一条；能换、清不掉）。换头像要先落一条 `file_object` 再写进账号那一格，这条路只开给"本人改自己"这一条（`updateProfile` 的目标恒等于当前登录者、只要求身份不判 ADMIN），管理员替别人开这条路等于"谁都能往存储里塞文件"，所以没开。于是昵称/手机号/头像打错或想换这类事，管理员都修不了——只能本人在个人中心改。**新建弹窗里刻意没有 `role` 输入框**，新建恒为 `MEMBER`，提权是建完之后在列表那一行另点一下的事。昵称与手机号各有存活记录唯一索引（V503：`deleted=0` 时取 TRIM 后的值，删除行取 NULL，支持反复删号重建），服务层预检及数据库并发冲突均返回 409，错误码 `USER_NAME_DUPLICATED` / `USER_PHONE_DUPLICATED`——**手机号自 V502 起只是资料项，不再是登录核对项，但它照样是"全家几个人"内部的唯一标识，所以判重一条没减**，个人中心改自己那一条也一样查重（把自己排除在外，改完还是原值时不会自撞）。
- **种子跟着 schema 走**：大宝（ADMIN）/ 小宝（MEMBER）两行写在 `V500` 里，不在 `deploy/dev-seed.sh`——后者只造演示数据，成员是系统配置级别的东西，而且 `V501` 的洗数据依赖这两行存在。`password_hash` 那一列与它的两串种子在 `V502`：哈希是离线按同一套参数（20 万轮 / 16 字节随机盐）算出来的，写死在迁移里而不是"启动时给空账号补一个默认口令"，这样"新库一建好就能登录"不依赖任何脚本步骤。两串种子对应同一个明文 `123456`——盐不同，所以密文不同。
- **头像**：`app_user.avatar_file_id` → `file_object`（`biz_type=USER_AVATAR`），为空时两端渲染 `@family-home/shared/image` 的默认剪影。存的是文件 id 不是 URL，读侧由 `AppUserService.avatarUrls(...)` 批量换成缩略图地址（取不到退原图 URL）。**写它的入口有两个**：建号时管理员定的那一张（`POST /api/b/user` 的 `avatarFileId`），以及本人在个人中心的自助换头像（2026-09-23 起 `PUT /api/b/user/profile` 也收 `avatarFileId`）；后者走 `updateById` 的 NOT_NULL 策略——`avatarFileId` 为 null 就跳过这一列，所以"没换头像"不会把原来那张清掉，代价是**能换、清不掉**（没有"恢复默认剪影"的入口，两端都没有）。⚠️ 将来做"无引用文件清理"时，`avatar_file_id` 是一个引用方，别只数相册/菜谱/订单那几处；软删账号那一行也还指着它的头像。

## 口令的传输段加密（2026-09-22 起）

用户口径"所有的密码加解密都通过一个盐来做，前后端都要"落在**传输段**而不是端到端：口令在线上跑密文，服务端在业务入口解回明文，**下游存储口径一个字没改**（登录仍是 `UserPasswordManager` 的 PBKDF2 哈希，密码本仍是 `VaultCipherManager` 那把 AES-256-GCM）。端到端做不到——`login()` 必须拿到明文才能算哈希，`reveal` 必须拿到明文才能还给人看；硬做端到端等于把这两条功能拆掉。

- **唯一的实现是 `fh-common` 的 `TransportCipher`**：普通类、无 Spring 注解（fh-common 刻意不依赖 spring-context），由 `fh-boot` 的 `TransportCryptoConfig` 读 `fh.transport-crypto.salt` 装配成 bean。放 fh-common 的理由与 `CurrentUserHolder` 同一个：user 域与 vault 域都要用它，而两个域之间唯一的公共依赖就是 fh-common——放进任何一个域都会凭空多出一条跨域边。
- **线上形状（三处实现必须逐字节对齐）**：`base64(12 字节随机 IV ‖ AES-256-GCM 密文+128 bit tag)`；密钥由盐经 **PBKDF2-HMAC-SHA256 / 10 万轮 / 输出 256 bit** 派生，**盐串的 UTF-8 字节同时充当 PBKDF2 的口令材料与 salt**。轮数、IV 长度、tag 长度三个常量在 Java 侧（`TransportCipher` 的 public 常量）与 TS 侧（`packages/shared/src/crypto/transport.ts`）各写一份、必须同步改；只改一侧的症状是"每次都解不开"，而不是"某一侧报错"，所以它们不留在实现细节里。明文 14 字符 → 42 字节 → 56 个 base64 字符（密文比明文长是常态，别按明文长度估算请求体）。
- **五个出入口，全仓只有这五处碰它**：`POST /api/b/user/login`、`POST /api/c/user/login`、`PUT /api/b/user/profile/password`（原口令 + 新口令两格各自解密）、`POST` / `PUT /api/b/vault/accounts`（`password` 那一格）、`POST /api/b/vault/accounts/{id}/password/reveal`（**唯一反方向**：服务端加密返回，前端解密后展示）。前端加密只写在 `packages/{admin,h5}/src/api/*.ts`，页面与组件里一行加解密都没有——理由就是"全站口令出入口只有这几处，且它们都已经在 api 层"。
- **配置成对**：服务端 `fh.transport-crypto.salt` ↔ 前端构建期 `VITE_FH_TRANSPORT_CRYPTO_SALT`。**不一致的表现是登录返回 400「口令无法解密，请刷新页面后重试（前后端加密参数不一致）」**，不是那句"密码与该账号不匹配"——这句区分是故意的，它直接把用户引到"配置有问题"而不是"我密码打错了"。构造器对盐做三道校验（非空、≥16 字符、纯 ASCII），**不合规直接 `IllegalStateException` 启动失败**：盐太短 → 暴破盐≈暴破口令，这层只剩心理作用；带非 ASCII → 两侧的 PBKDF2 输入字节可能因编码处理而分叉；带首尾空格 → 两端各自 trim 之后仍可能对不上。
- **长度校验只能放在解密之后**（这一条改掉了 DTO 上原有的 `@Size`）：`UserPasswordUpdateRequest` 与 vault 那两个请求类的 `password` 格现在**没有** `@Size`，因为那一格装的是一串比明文还长的 base64，`@Size(max=64)` 会把合法请求打成 400。规则搬到 service：`AppUserService.requireNewPasswordLength`（6–64 位，与前端个人中心那三格的校验同一口径）和 `VaultAccountService.decryptAndCheckLength`（≤256 字符）。
- **解密失败一律 400 + `BAD_REQUEST`，不新开错误码**：空值仍是"请输入密码"（与 `@NotBlank` 同句），解不开才是那句长提示。前端判成功只看 `code`/非 2xx，不需要按 code 分支，多一个字符串常量只会多一处死码（与"口令错为什么不是 401"是同一类判断，但结论相反：那边 401 会被 HTTP 层当成登录态失效）。
- **它不是访问控制，别拿它当护栏**：盐随前端构建产物公开，能调到接口的人必然拿得到解密所需的一切。它买到的是"明文不出两端进程"——Network 面板、nginx access log、请求日志里都不再有口令；它没买到的是"挡住有意调接口的人"，`reveal` 现在要求登录身份并校验密码本分区/属主，而身份自 2026-09-22 起由服务端签发的 HMAC Bearer token 决定（不再是可伪造的 `X-User-Id` 头），部署网络边界仍需保护。**它换来的新约束是 https**（见上面「配置说明」那条 ⚠️）。
- **两把钥匙别统一**：`fh.vault.password-key`（存储段，可逆，泄了=全库密码本明文）与 `fh.transport-crypto.salt`（传输段）参数相似、互不知情，在 `VaultAccountService` 里首尾相接：`transportCipher.decrypt(请求) → 明文 → vaultCipherManager.encrypt(明文) → 落库`，reveal 反着走。合并成一把 = 要么把可逆性丢在传输上，要么把"能解线上载荷的密钥"塞进配置文件交给同一个人。
- **`deploy/transport-crypto.mjs`** 是给手工构造请求体用的助手：`node deploy/transport-crypto.mjs <明文>` 输出密文、`-d <密文>` 输出明文、`--roundtrip` 自检（同一条口令两次密文不同属正常，因为 IV 随机）。curl 手工打 login/vault 时必须先包一层——**服务端没有"兼容明文"这条分支**，拿明文直连会稳定得到那句 400，这是预期而不是 bug。`deploy/dev-seed.sh` 已按这个改掉（发密文、把 reveal 的返回解回明文再核对）。

## 接口一览（B 端 `/api/b/**`）

> 这一张表**只列 B 端**。C 端那一半在下面的"C 端接口"一节。2026-09-21 从 `/api/b` 整条搬走的东西在这张表里只有两种痕迹：封面那一条在相册行里标了"（已搬 C 端）"，购物车三条**整行删掉了**（B 端从来没有购物车 UI，`controller/b/` 里已无 `RecipeCartController`）。⚠️ **判断有没有搬走要看 controller 里还剩哪条 mapping，别拿状态码当证据**：只剩 PUT/DELETE 的路径上发 GET 会返 405，看着像"接口还在"。

| 域 | 前缀 | 能力 |
| --- | --- | --- |
| 相册 | `/api/b/album/groups` | 分组 CRUD、拖拽排序、级联删图、批量绑图。**v14 全部 album 接口 query.scope 缺省 FAMILY，唯新建分组仍取 body.scope**。`GET` 全量不分页：FAMILY 仅家庭，PERSONAL 仅当前账号 `creator_id` 的分组（无身份 401），不再合并两档；B 端只排 DELETED，按本分区关联组装张数、不放封面。`POST {name,scope?}` 要身份；`PUT /{id} {name?,status?}` 各自判 null，不可改 scope/属主。裸 ID 读写先验分区/属主，越界/已删除/不存在 404；排序、删除、绑定与整组覆盖均先验全部目标再写，禁止跨区关联。B 端仍可管下架组，PERSONAL 卡仍无上下架开关；C 端读/绑定另验 ON_SHELF。分组上下架不改图片状态、不重算城市，也不把图片推入「其他」。删除先检查全 album 活引用保护历史共享文件；绑定的新 file 必须本人上传，另一 scope 已用 fileId 要重传；组内 md5 去重，只在同分区复用 image，详见下方分区边界 |
|  | `/api/b/album/images` | 全部请求 query.scope 默认 FAMILY；列表按 `scope + owner_id`（FAMILY=0，PERSONAL=当前账号）分页，个人列表无身份 401；带 groupId 先校验该组。`status` 不传只排 DELETED。`PUT /{id}` 改城市/状态、`PUT /{id}/pin` 置顶、单删/批删、`GET/PUT /{id}/groups` 读回/整组覆盖均校验分区/属主，越界 404；批删 `{ids:[]}` 及整组覆盖先验全部 ID 再写，不允许跨区关联。删除清关联，全 album 无活引用的 file 才标删清盘；改城市/图片状态及删除仅重算本分区。无单张详情接口，元信息由分页返回 |
|  | `/api/b/album/cities` | `GET` 城市候选及在架图片数，`POST /recalculate` 手动修数；query.scope 缺省 FAMILY，均只读/重算当前 `scope + owner_id`，PERSONAL 无身份 401。C 端 `GET /api/c/album/cities` 同一分区口径，不再全局统计 |
| 菜谱 | `/api/b/recipe/recipes` | 分页（关键词/状态/分类过滤）、详情、CRUD；**新建必填分类**，`practiceGroups` = 勾选的可选做法分组，每组带这道菜的两项配置 `{groupId, required, defaultOptionId}`（读写共用同一个结构，同购物车的 `CartPracticeDTO`）；`null`=不动、`[]`=清空，`defaultOptionId` 不属于该分组时直接报"默认选中的做法选项不存在"（整条新建/更新事务回滚，不会留下半条菜谱） |
|  | `/api/b/recipe/categories` | 分类 CRUD，按 `sortOrder` 升序（权重相同按 id 兜底）；`DELETE /{id}` 与做法分组同口径——**同一事务里先删 `recipe_category_rel` 再删字典行**（留着关联行就是孤儿数据，那些菜在 C 端会直接掉出菜单），日志打 `解绑菜品=n` |
|  | `/api/b/recipe/practices` | 做法字典**只有分组这一层**：`GET` 全量（分组和组内选项都按录入顺序 id 排，`recipeCount`=关联的未删除菜品数）、`POST` 只建分组（`{name}`）、`PUT /{id}` = 组名 + 整组选项一次提交（`options` 为 `null` 表示这组选项不动、`[]` 表示清空；带 `id` 的改名字、不带的插入、这次没列出的删掉，**不做全删重插**，所以改名字不会换掉购物车/订单快照里引用的 optionId）、`DELETE /{id}` 硬删级联删选项和解绑菜品。**没有单个选项的增删改接口，也没有排序**（V311 把两张表的 `sort_order` 列删了）——B 端做法管理页那一行的改组名、改选项名、加选项、删选项全靠这一个 PUT 的差量语义，不为选项再开接口，辣度/糖已随 V306 初始化 |
|  | `/api/b/recipe/orders` | **这一行只剩 B 端要用的这几条**：`GET` 列表 / `GET /{id}` 详情 / `complete` / `cancel` / `DELETE` / `GET /statistics`。下单、再来一单、继续加菜这三条 B 端从来没有调用方（B 端没有下单按钮），2026-09-21 已整条搬到 `/api/c/recipe/orders` 那一边（**已搬 C 端**）；被搬走那三条的行为口径一个字没改，改的只是暴露它们的类。下面按搬完之后的形状描述——那三段现在挂在 `POST /api/c/recipe/orders`、`POST /api/c/recipe/orders/{id}/again`、`.../append` 上，行为口径与之前完全一致。`POST`（无请求体）= 把整个购物车快照成一单（`status=PENDING` 待制作，明细存菜名/封面 URL/做法快照——封面走 `RecipeService.coverUrls`，V315 给 `recipe_order_item` 加的 `cover_url` 列，这道菜当时没图和历史单都是 NULL）并清空购物车；`GET` = 订单列表**分页 + 条件过滤**（2026-09-21 改，原先整表返回、前端自己切 20 行）：`pageNo`/`pageSize`（默认 1/20，`pageSize` 上限 100，超了 400 "每页大小不得超过 100"）、`status` 精确匹配、`keyword` **按明细里的菜名快照模糊匹配**（一单里任意一道菜命中即命中这一单，所以是 `EXISTS recipe_order_item` 而不是 join 菜谱取现名——改名前的老单仍按老名字搜得到；`{0}` 参数绑定，与相册分组归属那两处 `exists` 同一写法），三个条件都是"没传就不加这一条"，返回 `PageResult`（`list`/`total`/`pageNo`/`pageSize`/`hasMore`），排序 `create_time desc, id desc`，明细按**这一页的订单 id** 批量取回（不再是全表）；**这一条分页列表只有 B 端点单列表在用**（C 端"我的订单"是另一条 `GET /api/c/recipe/orders`：一次全量数组、不翻页，共用 service 里抽出来的那一份实现，2026-09-21 起不再有"传 `pageSize=100` 一次拿满"这回事）；`GET /{id}` = 订单详情（C 端另有一条同名的 `GET /api/c/recipe/orders/{id}`，同一个 `getDetail`）；`POST /{id}/complete` = 推进到已完成（幂等，见上面的状态口径；完成即定稿、没有改回待制作的接口。B/C **两端各有一条路径**，C 端走 `POST /api/c/recipe/orders/{id}/complete`，同一份 service 方法）；`POST /{id}/cancel` = 取消这一单（`PENDING`→`CANCELLED`，两端都有按钮、也各有一条路径；对已取消单重复点幂等成功，对已完成单报"已经完成了，不能取消"——那是改历史，不是取消）；`DELETE /{id}` = 删除这一单（2026-09-21 加，**只有 B 端给这个接口**，C 端压根没挂 DELETE 路径（敲过去 405）——历史记录留不留是管理人决定的事）：整单连同 `recipe_order_item` 明细**物理删掉**（这两张表本来就没有软删列，也没为此加第四个状态；明细留成孤儿行只会让统计口径对不上），条件 `status != PENDING`，所以待制作那档删不掉、报"待制作的订单不能删除，请先取消或标记完成"，订单不存在是另一句；写法沿用 `complete`/`cancel` 那一套——**先按条件动库，影响 0 行再回头查一次解释原因**，不做"先查再删"。明细里的 `cover_url` 只是下单时的 URL 快照、图片文件归菜谱域管，所以这里**不碰任何物理文件**，没有相册/文件那套"先软删再清盘"。被删的那一单自然从累计份数里消失，统计与"点过 x 次"都跟着少，这是有意为之的口径；**下面这两条只有 C 端有**（`/api/c/recipe/orders/{id}/again`、`/api/c/recipe/orders/{id}/append`，`controller/b/` 里那两条 mapping 已整条删除）：`POST /{id}/again` = 再来一单：把该单明细**追加**回购物车——同一道菜份数累加、做法以本单快照整体覆盖，菜品已删除的**跳过**并回 `{addedCount, skippedCount}`，只写车不成单（是否下单仍由确认页决定），全被跳过时报业务错；`POST /{id}/append` = 继续加菜：把当前购物车**并进这一单**（只有 `PENDING` 可以，累加/覆盖口径同上，购物车照常清空，无请求体、返回 `Void`）；`GET /statistics` = **分页 + 菜名过滤**（2026-09-21 改，原先一次返回全部菜品、没有筛选）地按菜品聚合累计下单份数：`pageNo`/`pageSize`（默认 1/20，`pageSize` 上限 100，超了 400 "每页大小不得超过 100"）、`keyword` 模糊匹配**这一行显示的那个菜名快照**（不是菜谱现名——搜得出来的词必须就是表格里看得见的字），**只有菜名这一个筛选维度**，因为统计的行是"菜品"、不是"订单"，没有状态可筛，**每道菜再带上各做法选项分别被点了多少份**（`practices`：`{groupId, optionId, qty}`，份数多的在前，名字由前端拿做法字典现查、后端不 join 做法表），**每行另带 `coverUrl` = 这道菜当前的封面**（2026-09-21 加：复用 `RecipeService.coverUrls` 一次批量查 `recipe_image` sort 最小那张；**不是**明细里的 `cover_url` 快照——一行并了多笔订单，快照可能各不相同、取哪个都是任意值，而这一列回答"这道菜长什么样"。菜品删除只改状态、`recipe_image` 留着，所以下架/已删的菜一般也有值；没配过图就是 null，前端按默认封面兜底）；实现是**一次取回参与统计的明细、菜品聚合与做法聚合都在 Java 里做**（做法在 JSON 列里，SQL `GROUP BY` 拆不出"每个选项多少份"，分两处查又会把排除条件写两遍；菜名仍取字符串序最大的那条快照，与原来 `MAX(recipe_name)` 同口径）。**分页切在聚合之后**：取明细、算份数永远是全量走一遍，再按份数倒序（同份数按 `recipeId` 升序）排序 → 按 `keyword` 筛 → 按 `pageNo/pageSize` 切片，所以每行那份数仍是这道菜的**全历史**累计、翻页不会看到数字变小，`total` 是筛完的行数（= 被点过的菜品数），代价是多聚出来的行直接丢掉（家庭量级可忽略）；封面 `coverUrls` 收窄到只查这一页涉及的菜。**页码越界返回空 `list` 但 `total` 报真值**（与 `GET` 订单列表同一口径，否则分页器会显示"共 0 个菜品"骗人；`PageResult.empty` 两处都不再用它、已作为死代码删除）。**对 `recipe_order_item` 聚合，不按菜谱状态过滤**（含已下架、已删菜品的历史行），但**排除 `CANCELLED` 的单**——取消掉的没做过，份数和做法份数都不算。**这一条分页统计只有 B 端点单统计页在用**；C 端点餐页那"点过 x 次"（卡片按 `recipeId` 取数，多出来的 `practices` 用不到）走的是 `GET /api/c/recipe/orders/statistics`——**同一个聚合实现**（service 里抽出来的那一份，两端只是各自决定要不要翻页），一次全量数组，所以 h5 原先"传 `pageSize=100` 一次拿满再解 `list`"那两处 workaround 已随 2026-09-21 的接口分层一并删掉 |
| 文件 | `/api/b/file/upload` | multipart 上传，只有 `file` + `bizType` 两个 part（图片 EXIF 不走这里，`file_object` 没那几列），图片专用：mime 白名单 + 缩略图。`bizType` 用于相册图 / 菜谱图 / **`USER_AVATAR`（账号头像，只有 `app_user.avatar_file_id` 引用它）**；图片入口拒绝 `document`，文档只能从下面的专用入口上传。C 端上传走 `POST /api/c/file/upload`（同一个 `FileFacade.upload`），B 端的文件管理（分类字典、文档列表/删除、`storage-info`）在 C 端一概没有 |
|  | `/api/b/file/documents` | 文档管理（**不限格式**，2026-09-21 起）：`GET`（`categoryId` 可选，最近在前，**一期不分页**）、`POST` multipart（`file` + `categoryId` 必选）、`DELETE /{id}`、`GET /{id}/download`。全部入口 query `scope` 缺省 PUBLIC，须登录；列表/分类/裸 ID 均限定 `scope + owner_id`，跨区/属主 404。下载返回二进制 Resource（不是 Result），带 attachment、no-store、nosniff；PRIVATE 的列表 URL 为空且文件位于静态根之外，PUBLIC 仍可直接用静态 URL。删除只认本分区 `biz_type=document` 行。**类型仍由服务端解析、前端不传也不能传**（浏览器 Content-Type 一律不信），只是不再挑格式：按文件名取扩展名 → 查 Spring 的 mime 表（`md` 表里没有，代码里补了 `text/markdown`），查不到就是 `application/octet-stream`；没有扩展名的文件照样收，`ext` 存空串。原先的"四格式白名单 + 真实字节嗅探"（`DocumentFileType`）已整体删除——既然什么扩展名都收，"内容与扩展名不符"就不再是一个错误。**保留的唯一硬约束**：扩展名会拼进落盘的 `fileKey`，所以先洗成 `[a-z0-9]`、最多 16 字符（`DocumentType`），这是路径注入的入口，别放开。multipart 上限没动（单文件 20MB / 单请求 100MB，超了 413 + 中文提示） |
|  | `/api/b/file/categories` | 文件分类字典：`GET` 当前分区全量（不分页）+ `POST` 新建（同分区重名 409），均 query `scope=PUBLIC/PRIVATE`，缺省 PUBLIC、要求登录；分类不能跨分区筛选或上传。无改名/删除入口——分类挂在文件管理页下拉框里内联新建 |
|  | `/api/b/file/storage-info` | 存储路径信息（`rootDirectory` / `urlPrefix` / `structure`，不是用量统计） |
| 视频 | `/api/b/video` | 视频管理（2026-09-23 加，**不另建表**：视频是 `file_object` 的行、`biz_type='video'`，与文档同一取舍）。四条：`GET`（列表，最近上传在前，query `scope` 缺省 PUBLIC，**每行带一枚现签的短时播放票据** `playUrl`）、`POST`（multipart `file` + query `scope`，类型由服务端按文件名解析、**非视频 415**，前端不传也不能传）、`DELETE /{id}`（软删行 + 物理删文件，走 `fileFacade.markDeletedAndPurge`）、`GET /{id}/stream?ticket=`（播放流，支持 HTTP Range，**唯一不验登录令牌的一条**，靠票据鉴权）。分区沿用密码本/文件那套 `DataScope.PUBLIC/PRIVATE` + `DataPartition`（PUBLIC owner=0、PRIVATE 当前账号、ADMIN 无例外、跨区裸 ID 404、owner 服务端定）；列表/上传/删除都要登录。**播放鉴权 = 预签名票据 `VideoPlayTicket`**（HMAC-SHA256、TTL 6h、复用 `fh.auth.token-secret`，格式 `v1.<id>.<scope>.<ownerId>.<exp>.<sig>`）：`<video src>` 带不上 `Authorization` 头、又必须支持 Range 才能 seek，故用预签名 URL；**刻意不复用登录令牌 `SessionToken`**（令牌是身份凭证、绝不能进 URL/nginx log）。`stream()` 返回 `ResponseEntity<Resource>` 包 **`FileSystemResource`**（不是 `InputStreamResource`，否则 Spring MVC 不会自动处理 Range/206），并按票据里的 id+scope+ownerId 回查行 + 校验私人路径前缀，任何不符 404；票据空/烂/签名不对/过期/id 不符一律 403。上传走 `FileStorageWriter.writeVideoStream`（`DigestInputStream` 单次落盘算 md5，**不进内存 byte[]、不秒传**），multipart 上限 500MB/512MB；公共落 `videos/`、私人落 `private-videos/{ownerId}/`（私人根同级 `-private`，永不静态 alias）。**C 端另有一条只读接口 `GET /api/c/video`（2026-09-23 加）**：h5 能看家庭/私人两档视频并播放，但只能看不能传/删（上传/删除仍只有 B 端）；C 端列表里的 `playUrl` 换成 `/api/c/video/{id}/stream` 基址，票据与 B 端同一套（票据只绑 id+scope+ownerId+sig、与路径无关，所以两端 stream 共用同一枚票据校验）。详见下方「视频管理」一节与「C 端接口」表 |
| 密码本 | `/api/b/vault/accounts` | CRUD + `POST /{id}/password/reveal`（唯一把口令还给前端的路径；2026-09-22 起**响应体里是传输层密文**，"返回明文"这一步发生在浏览器组件的 state 里）；所有请求 query `scope` 缺省 PUBLIC，均须登录；PUBLIC/0 共享、PRIVATE/当前账号隔离，ADMIN 无例外，越界裸 ID 404。新建时 scope/owner 由服务端设定，`creator_id` 仍取真实创建人，编辑不能搬分区；名称+账号仅同分区联合唯一。**C 端没有密码本接口，h5 也没有这一页**——整个域只挂在 `/api/b` 下 |
| 账号 | `/api/b/user` | `GET /options`（**不需要身份**，`{id,name}` 字典，刻意不给手机号；C 端有自己的一条 `GET /api/c/user/options`，同一个 `options()`、同一个 VO，两端各自的"添加人/下单人"读自己那一条）、`POST /login`（同样不需要身份，`{userId,password}`，**2026-09-22 起 `password` 那一格是传输层密文**，见上一节；→ `{id,name,role,avatarUrl,token}`，`token` 是服务端签发的 7 天 HMAC Bearer 令牌，前端单独存 `fh-auth-token`、之后每次请求带 `Authorization: Bearer <token>`；口令不对 → 400，C 端登录走 `POST /api/c/user/login`，同一份 `login()`）、`GET /me`（用当前 id 换回"还在不在 + 现在的昵称/头像/角色"，账号被删返 401，两端 HTTP 层据此清掉本机登录态掉回登录页；C 端走 `GET /api/c/user/me`）；**下面这三条也只有 B 端有，但只要身份、不判 ADMIN**（个人中心那一页用，MEMBER 同样能改自己）：`GET /profile`（`{id,name,phone,avatarFileId,avatarUrl}`，比 `/me` 多的正是手机号与头像那一格——手机号这一格不写进本机长期缓存，只在打开个人中心时拉一次；`avatarFileId`/`avatarUrl` 供这一页回填预览与保存时原样带回，`avatarUrl` 优先缩略图、为空时前端画默认剪影）、`PUT /profile`（`{name,phone,avatarFileId?}`，没有 `role`/`password` 可写；`avatarFileId` 为 2026-09-23 加的自助换头像格，null 时 `updateById` 跳过这一列=不改头像，所以**只能换、清不掉**）、`PUT /profile/password`（`{oldPassword,newPassword}`，**两格都是传输层密文**，服务端各自解密后才做 6–64 位校验，先核对原口令再写新哈希）；**最后这四条才是 ADMIN 专属**（账号管理是 ADMIN 的事，C 端连路径都不给，service 层还有第二道 `requireAdmin()`）：`GET`（整表，ADMIN；VO 里**没有任何口令类字段**，哈希也标了 `@TableField(select=false)`，所以管理员连"查"都查不出来）、`POST`（ADMIN，**不收 `role` 字段**，新建恒 MEMBER；**也不收 `password`**——初始口令由服务端常量 `DEFAULT_INITIAL_PASSWORD` 定，多传该键会被 Jackson 静默忽略；`avatarFileId` 可选，这是管理员唯一能替别人定的资料字段，且只在建号这一刻——建完之后管理员就改不了头像了，本人可去个人中心自己换）、`PUT /{id}/role`（ADMIN，**2026-09-22 加，管理员唯一能替别人写的字段就是这一格**；`{role}`，只认 `ADMIN`/`MEMBER`，挡"改自己"和"降掉最后一个管理员"，都 403；**它不碰昵称/手机号/口令，也不动 `password_hash`**）、`DELETE /{id}`（ADMIN，软删，**目标是 ADMIN 一律不可删**——403 "管理员账号不能删除，请先取消其管理员角色"；要删管理员得先把他降成成员，这一条也天然挡住删自己）。**原来的 `PUT /{id}`（ADMIN 改别人资料/重置口令）已整条删除**，对 `/api/b/user/{id}` 发 PUT 现在是 405——`/{id}/role` 是另一个路径模板，别把 405 读成"角色接口没生效"，也别把它读成"整行编辑还活着"——详见上面的账号体系一节 |

> **multipart 里的非文件字段一律用 `@RequestParam`，不要用 `@RequestPart`**：`@RequestPart` 会把该 part 交给
> `HttpMessageConverter` 按目标类型转换，`Long`/`Double` 这类没有对应 converter，请求直接 500
> （`HttpMediaTypeNotSupportedException: Content-Type 'application/octet-stream' is not supported`——
> 浏览器给纯文本 part 的正是这个）。`DocumentFileController` 的 `categoryId` 用的就是 `@RequestParam`；
> `FileController` 上原先那三个 `@RequestPart`（`lng`/`lat` 是 `Double`、`shootTime` 是 `String`）就是这个坑的存量隐患，
> 已经连着 `FileUploadRequest` 的字段一起删了——它们本来也没人写：`file_object` 没这几列，EXIF 只能随
> 批量绑图那条接口（B 端 `POST /api/b/album/groups/{id}/images` / C 端 `POST /api/c/album/groups/{id}/images`）的绑定项落到 `album_image`。
> 缺 part 会抛 `MissingServletRequestPartException`，`GlobalExceptionHandler` 已接住转成 400 + 中文。

## 公共 / 私人密码与文件（2026-09-22）

两域使用 `DataScope.PUBLIC/PRIVATE`，不与相册的 FAMILY/PERSONAL 枚举混用。`DataPartition` 统一要求身份：公共属主为 0，私人属主取当前账号，ADMIN 无例外；owner 不接受表单指定，创建后分区不可编辑。`creator_id` 继续表示真实创建人。历史密码、文件及分类默认归公共，V106/V404 不删除、搬动或改名历史内容；同名唯一键仅在本分区生效。

私人文档的逻辑 key 为 `private-documents/{owner}/日期/随机文件名`，物理根固定派生为规范化 `fh.storage.root` 的同级「原目录名-private」，例如 `files` 对应 `files-private`，不新增配置项。**部署时须持久化并备份这两个目录，私人根不可配置静态 alias**；原 `/files/**` 仍只映射公共根，Java 静态解析器额外拒绝 `private-documents/` 前缀（误放进公共根也返回 404）；若部署 nginx 直接 alias，须同步拒绝 `/files/private-documents/` 路径。私人列表不返回 URL，下载须通过校验分区/属主的 API，响应禁止缓存。文件每次上传生成独立行与路径；PRIVATE 秒传只找同属主文档，PUBLIC 不借用 PRIVATE 源。删除仍先标删、提交后清理文件。

跨域图片映射排除所有文档，避免私人文档经头像、菜谱或相册路径变成静态 URL。这一轮做的是既有身份机制上的数据隔离；登录凭据其后（2026-09-22）已从可伪造的 `X-User-Id` 头升级为服务端签发的 HMAC Bearer token，越权冒充已堵，但静态文件 URL 仍不鉴权，因此这仍不能替代部署网络边界。个人相册的静态图片安全边界未在这一轮改变。

**分区验收（2026-09-22 实测）**：`VaultDocumentPartitionIntegrationTest`（55）+ `DuplicateValidationIntegrationTest`（45）+ `DuplicateExceptionHandlerTest`（13）= 113 项全绿，0 失败/错误/跳过；测试用真实 MySQL 8、独立 `target` 存储根、随机前缀合成用户与口令，只清理自己的行与文件，不读历史凭据。需 `FH_RUN_DB_TESTS=true` 且 V106/V404 已应用才会跑（`@EnabledIfEnvironmentVariable`）。V106/V404 `success=1`，存量三域行数/存活/校验和迁移前后一致（vault 8/2、documents 10/0、categories 3/3，校验和排除口令列）。覆盖属主与 ADMIN 边界、默认 PUBLIC、分类隔离、跨分区裸 ID 404、分页 keyword 的 OR 被分区括号收住、SQL CHECK/唯一约束、并发、软删后重建复用、下载字节/响应头/静态拒绝、MD5 分区隔离、上传回滚清路径、图片门面排除文档。浏览器侧另验：PRIVATE reveal 保留口令首尾空格、切号即清明文、跨属主下载 404、私人下载 Blob+objectURL 用后释放、PUBLIC 静态链接取回原字节、迟到 reveal 与迟到删除响应被会话守卫丢弃、旧 `/vault`·`/file` 重定向公共页、首页六卡仅统计公共。误放公共根的 `private-documents/` 前缀由 Java 静态解析器返回 404（nginx 直接 alias 时须配等价 deny）。

## 视频管理（2026-09-23）

B 端「视频管理」下设公共视频 / 个人视频，与密码本/文件同一套 `DataScope.PUBLIC/PRIVATE` 分区口径。**2026-09-23 起 C 端（h5）也能看视频**：新增只读接口 `GET /api/c/video`（列表）+ `GET /api/c/video/{id}/stream`（播放流），h5 首页加了「家庭视频 / 私人视频」两个入口，但 **C 端只能看不能传/删**（上传/删除仍只有 B 端），与相册 C 端"能看能绑不能建组"是同一类裁剪。下面先讲 B 端那四条，C 端那条只读接口在「C 端接口」表与文末单列。

**不另建业务表**：视频是 `file_object` 的行、`biz_type='video'`（文档 `document`、图片 `ALBUM_IMAGE`/`RECIPE_IMAGE`/`USER_AVATAR`），元数据 `file_object` 全都有，视频相对文档没有多出来的业务语义，与 `DocumentFileService` 当初"文档不建表"同一取舍。唯一一条迁移 **`V107`（file 号段）**：把 V106 的 `chk_file_object_private_document` 换成 `chk_file_object_private_partition`，放行 `document`/`video` 两种 `biz_type` 进 PRIVATE 分区（图片行恒 PUBLIC/0，不受影响）。

**四条接口（`/api/b/video`）**：`GET`（列表，query `scope` 缺省 PUBLIC，每行带一枚现签播放票据）、`POST`（multipart `file` + query `scope`，类型服务端按文件名解析、非视频 415）、`DELETE /{id}`（软删行 + 物理删文件，`fileFacade.markDeletedAndPurge`）、`GET /{id}/stream?ticket=`（播放流，支持 HTTP Range）。分区/属主由服务端确定（`DataPartition.forRequest`，PUBLIC owner=0、PRIVATE 当前账号、ADMIN 无例外、跨区裸 ID 404），列表/上传/删除都要登录。

**播放鉴权 = 预签名短时票据（`VideoPlayTicket`）**：`<video src>` 由浏览器直接发 GET、带不上 `Authorization` 头，而视频（尤其私人）又必须鉴权、还必须支持 Range 才能 seek/快进——交集就是预签名 URL。列表接口（本身要登录）为每支视频现签一枚 TTL 6h、只绑这一支的票据（HMAC-SHA256，格式 `v1.<id>.<scope>.<ownerId>.<exp>.<base64url-sig>`，**复用 `fh.auth.token-secret`**，不新增配置项），播放接口只验这枚票据的签名 + 有效期。**刻意不复用登录令牌 `SessionToken`**：令牌是身份凭证、一旦落进 nginx access log 就等于把身份摊出去（明令禁止令牌进日志/URL），票据只授予"读这一支、这一段时间"，泄漏了也调不动别的接口、很快过期，进 URL 可接受。拦截器对没有 Bearer 头的请求按匿名放行，所以 stream 这一条能走到 controller 由票据把关；票据空/格式烂/签名不对/过期/id 不符一律 403「播放链接已失效，请刷新后重试」。

**`stream()` 的实现要点**：返回 `ResponseEntity<Resource>` 包 **`FileSystemResource`**（**必须是 `FileSystemResource`、不是 `InputStreamResource`**，Spring MVC 才会自动处理 Range 请求、返回 206，这是能 seek 的前提）；响应头 `Accept-Ranges: bytes`、`Content-Disposition: inline`、`CacheControl.noStore()`、`X-Content-Type-Options: nosniff`。**不盲信票据**：按票据里的 id+scope+ownerId 回查 `file_object` 行、并校验私人路径前缀与 scope+ownerId 一致，任何不符 404。

**上传走流式**：`FileStorageWriter.writeVideoStream(InputStream, ext, partition)`——`DigestInputStream` 在单次落盘中算 md5，**不读进内存 byte[]、不做秒传/去重**（视频大，每支独立成行）。`VideoType.resolve(originName)` 解析扩展名 + mime（白名单 `mp4/m4v/mov/webm/mkv/avi/ogv/3gp/ts/mpg/mpeg/flv/wmv`，Spring mime 表查不到的按扩展名补默认，如 `mkv→video/x-matroska`），非视频抛 `FILE_TYPE_UNSUPPORTED`（415）；扩展名清洗与 `DocumentType` 一致（只留 `[a-z0-9]`、≤16 字符，因为它拼进落盘 `fileKey`，是路径注入入口）。multipart 上限在 `application.yml` 放到 500MB/512MB（视频远大于文档的 20MB/100MB）。

**存储路径**：公共 `videos/yyyy/MM/dd/uuid.ext`、私人 `private-videos/{ownerId}/yyyy/MM/dd/uuid.ext`（`FileStorageConfig.PRIVATE_VIDEO_PREFIX`，`privatePrefixOf(fileKey)` 同时认 `private-documents/` 与 `private-videos/`）。私人根 = 公共根同级 `root+"-private"`，**永不静态 alias**，`WebMvcConfig` 拦私有前缀出 `/files/**`——视频本就不走静态 URL（只走签名流），这条拦截是纵深防御。删除用 `markDeletedAndPurge`（软删行 + 提交后物理删文件）。

**播放器（admin）选 xgplayer 3.0.26**：框架无关 ES module（`new Player({el,url,...})`），官方 React 包停在 2.x 不兼容 React 19，故手写 ref+effect 封装。开箱倍速（0.5~3x）、快进（进度条拖 + 键盘 ←/→ 5s）、全屏/网页全屏/画中画、断点续播；选集由外层弹窗拿视频列表当播放列表实现。**清晰度是能力在、无可切**：播放器支持多码率，但单支上传的 MP4 没有第二档码流（没做转码），所以控制栏不出现清晰度选择。

**验收（2026-09-23，浏览器 + 编译，全绿）**：公共/私人上传、xgplayer 挂载并经签名流 URL 播放、倍速 0.5~3x（rate=2 生效）、快进/seek（`currentTime` 跳转 + Range 206）、选集播放列表切换（每支现签票据）、全屏/网页全屏、私人隔离（公共 2 支时私人空、私人用 PRIVATE 票据 ownerId=1 出流，readyState 4 + duration 解析 + videoError null 证明签名流服务合法媒体）、两分区删除（列表归 0）；admin `typecheck` clean、`build` exit 0。测试数据全清：磁盘 0 视频文件、两分区 UI 列表均"共 0 支视频"（软删行留存属设计）。**边界**：私人播放靠 6h 票据，票据 URL 泄漏 = 这段时间内可读这一支（可接受，与登录令牌不同级）；没做转码故无真正多码率清晰度；静态文件 URL 仍不鉴权（视频不走静态 URL，不受影响）。

**C 端只读接口（`/api/c/video`，2026-09-23 加）**：`controller/c/VideoCController` 两条——`GET`（列表，query `scope` 缺省 PUBLIC，返回裁剪过的 `VideoClientVO`：只有 `id/name/fileType/fileSize/playUrl`，去掉 B 端 VO 的 `creatorId/ownerId/scope/mimeType/createTime`）、`GET /{id}/stream?ticket=`（播放流，直接复用 `videoService.stream`）。**业务全在同一套 `VideoService` 上**，controller 只做返回体裁剪，与相册 C 端控制器同一写法。唯一的服务层改动：`VideoService` 把播放流基址参数化成 `STREAM_BASE_B`（`/api/b/video`）/ `STREAM_BASE_C`（`/api/c/video`），`list(scope)` 仍走 B 基址（B 端行为一字未改），新增 `listForClient(scope)` 走 C 基址，所以 C 端列表里的 `playUrl` 是 `/api/c/video/{id}/stream?ticket=...`。**票据与路径无关**（只绑 id+scope+ownerId+sig），所以 B/C 两条 stream 共用同一枚票据校验，`VideoPlayTicket` 没动。分区/属主/404/403 口径与 B 端完全一致（`DataPartition.forRequest`，PUBLIC 也要登录、PRIVATE 仅当前账号、ADMIN 无例外、跨区裸 ID 404）。C 端**没有**上传/删除接口（h5 只读）。

**C 端验收（2026-09-23，浏览器 + curl，全绿）**：h5 首页两入口（家庭视频/私人视频）渲染；家庭视频列表显示 PUBLIC 测试片（`WEBM · 2.1 KB`，`formatFileSize` 口径正确），点开原生 `<video>` 浮层播放，`readyState=4`、解码出 320×240 真实帧、`videoError=null`，stream 请求 `206 video/webm`；私人视频对 大宝 显示其 PRIVATE 片并经 `PRIVATE.ownerId=1` 票据出流 `206`，对 小宝 显示空态（隔离）；curl 侧：C 列表 PUBLIC/PRIVATE 按 scope 分流、小宝 PRIVATE 空、stream 全量 `200`（`Accept-Ranges/inline/no-store/nosniff/video/webm`）+ Range `206 Content-Range`、无 ticket `400`、伪造 ticket `403`、无令牌列表 `401`。测试片两支已删（C 列表归 0、磁盘 0 webm、无孤儿文件）。**注**：浏览器 `MediaRecorder` 造的 webm 缺 duration 元数据（`video.duration≈0.001`）是测试片本身的产物，不是播放链路问题——`readyState=4` + 解码出真实帧 + Range 206 已证明流服务合法媒体。

## C 端接口（`/api/c/**`，2026-09-21 起）

C 端（h5）不再打任何 `/api/b/**`。每个域在 `controller/c/` 里有一个独立的 `@RestController`，**只做路径与返回体的裁剪，业务全在同一套 service 上**——不是转发层，也没有第二份实现。搬家的判据只有一条：**`controller/b/` 里还有没有 B 端调用方**；没有就整条挪走（B 端留着一条没人调的路径，等于给 §8.3 那条内网白名单多开一扇公网用不到的门）。

| 域 | 路径 | 说明 |
| --- | --- | --- |
| 相册 | `GET /api/c/album/groups/covers` | query.scope 默认 FAMILY；家庭上架分组全家可见，PERSONAL 仅当前账号 `creator_id` 的上架组，无身份 **401**。只含同分区上架图片，最多 3 张封面 + 在架张数，全量不分页；空组不出。仅 FAMILY 保留「其他」卡（groupId=null），PERSONAL 没有 |
|  | `GET /api/c/album/groups/options` | query.scope 默认 FAMILY，分区/属主与个人无身份 401 同 covers。`{id,name}`，含空的上架组、不含「其他」；C 端不能建组，私人分组在 B 端个人相册创建 |
|  | `GET /api/c/album/cities` | query.scope 默认 FAMILY，只返回当前 `scope + owner_id` 的城市及在架图片数；PERSONAL 无身份 401。城市可自由输入新值，**不再全局统计** |
|  | `GET /api/c/album/images` | query.scope 默认 FAMILY，另收 groupId/ungrouped/pageNo/pageSize（默认 9，上限 100），不开放 status；只读同分区在架图，返回 `PageResult<AlbumImageBriefVO>`（id/fileId/url/thumbUrl）。groupId 先验分区/属主与上架状态：不存在、已删除、下架、跨区、别人私人或无身份私人裸 ID 均 **404**。无 groupId 且 ungrouped 非 true 返回 **400**，无全库读取；ungrouped=true 仅 FAMILY 的零关联在架图，PERSONAL 不支持 |
|  | `POST /api/c/album/groups/{groupId}/images` | query.scope 默认 FAMILY + `{items:[{fileId,city,lng,lat,shootTime}]}`；先验分组可见性（同上，越界/不可见 404），再要求写入身份并全批校验文件。新 file 必须本人上传，另一 scope 已用 fileId 要重传；组内 md5 去重，仅同分区复用 image、禁止跨区关联。EXIF 随新 image 行落库，城市只重算本分区 |
| 文件 | `POST /api/c/file/upload` | 两个 part（`file` + `bizType`），返回完整 `FileDTO`，C 端只用 `id` 去绑分组。B 端的文件管理（分类字典、文档列表/删除、`storage-info`）这里一概没有 |
| 视频 | `GET /api/c/video` | query `scope` 缺省 PUBLIC，返回裁剪过的 `VideoClientVO`（`id/name/fileType/fileSize/playUrl`），每行 `playUrl` 是 `/api/c/video/{id}/stream?ticket=...`（C 基址，票据与 B 端同一套）。PUBLIC 全家可见、PRIVATE 仅当前账号，无身份 401、跨区裸 ID 404。**C 端只读**：没有上传/删除接口（h5 只能看不能改），上传/删除仍只有 B 端 `/api/b/video` |
|  | `GET /api/c/video/{id}/stream` | 播放流，`?ticket=` 鉴权（唯一不验登录令牌的一条，同 B 端），支持 HTTP Range/206，直接复用 `videoService.stream` |
| 菜谱 | `GET /api/c/recipe/categories` · `/practices` | 分类字典、做法分组+选项，都是全量 |
|  | `GET /api/c/recipe/recipes` | 点餐页菜品列表，**一次全量、只返回在架**（`recipeService.listOnShelf()`）。B 端菜谱管理页那条要翻页和筛选，走 `/api/b` |
|  | `GET/PUT/DELETE /api/c/recipe/cart` | 全家共享购物车，三条均返回 `{version,items}`。PUT 必传 `{version,recipeId,qty,practices?}`，`qty<=0` 移除、做法整体覆盖；DELETE 必传 `?version=...`。旧版写入返回 400，不自动重放。B 端没有购物车接口 |
|  | `POST /api/c/recipe/orders` | 必传 `{version}`，服务端读取该版购物车、创建快照并原子清车，返回订单 id。相同版本重复提交返回原订单，首个成功请求决定 `creator_id`；旧版本不能消费后来的购物车 |
|  | `GET /api/c/recipe/orders` | **我的订单，一次全量数组**（家庭场景单量个位数~几十），`create_time DESC, id DESC`。⚠️ 与 B 端那条是**两种返回体**（这条是数组，B 端那条是服务端分页的 `PageResult`），共用的是 service 里 `listOrders()` 抽出来的那一份实现。**这一条取代了原先 h5 传 `pageSize=100` 的 workaround** |
|  | `GET /api/c/recipe/orders/statistics` | "这道菜点过几次"，同样是全量数组；B 端统计页要翻页 + 按菜名筛，各走各的 |
|  | `GET /api/c/recipe/orders/{id}` | 订单详情（明细带封面快照 `coverUrl`，V315） |
|  | `POST /api/c/recipe/orders/{id}/complete` · `/cancel` · `/again` · `/append` | 状态推进 / 取消 / 再来一单（返回 `{addedCount,skippedCount}`）/ 待制作单继续加菜。`complete` 与 `cancel` **两端各有一条**（B 端点单列表也能操作），`again` 与 `append` 只有 C 端用、已从 B 端删除 |
|  | ~~`DELETE /api/c/recipe/orders/{id}`~~ | **C 端不能删订单**：这条路径压根没挂 DELETE（返回 405）。删整单只有 B 端有 |
| 账号 | `GET /api/c/user/options` · `POST /login` · `GET /me` | 登录页两条 + 开机自校一条（登录入参是 `{userId,password}`，与 B 端同一个 `UserLoginRequest`、同一份 `login()`，所以两端口令口径必然一致）。`/options` 同时是 C 端"谁下的单"的 id→昵称字典（刻意不给手机号）。**账号管理那四条（列表/新建/改角色/删除）与个人中心三条都不在 C 端，连路径都不给**——h5 没有"改自己的资料/口令"那一页，忘了口令只能到 B 端登录后在个人中心自己改；真改不了（连口令都想不起来）就只能请管理员删号重建，账号管理那边没有"替别人重置口令"这条路 |

**共享车并发合同（V317，2026-09-22）**：`recipe_cart_state` 的固定 id=1 行用 `FOR UPDATE` 串行化读/改/清车、再来一单、下单和加菜，版本每次成功写入后递增。`recipe_cart_checkout` 以 `cart_version` 为主键记录消费订单和加菜目标；主单、明细、清车、消费回执、版本推进处于同一事务。普通下单和 `/append` 均必传 `{version}`；先查消费回执再校验当前版本，同版本同操作同目标返回原结果，异操作/异目标拒绝，新车不受旧请求影响。完成/取消后不能消费新车，但此前成功加菜的原版重试仍成功。B 端删单不删回执，回放只会报 `RECIPE_ORDER_NOT_FOUND`（404），不会重建订单。加菜按「车 state → 订单」顺序取锁，状态更新/删单不反向取车锁；详情主单及明细在同一个只读事务中读取。两张新表均含创建和更新时间。

本地 JDK 21 package、V317 迁移与真实 MySQL 并发诊断通过：12 路跨账号重复创建/加菜、下单与加菜争抢、不同目标争抢、旧版写入拒绝、再来一单/终态推进竞争以及删单回放。诊断脚本位于 `fh-boot/target/verify_cart_concurrency.py`，12 组检查全通过，脚本订单已清理、回执有意保留；后续浏览器验证剩余测试订单 #38/#40 的清理被自动模式拦截，购物车为空。身份认证边界未在本轮改动。

四条实现口径：

- **只读接口把"只看上架"写进服务端**，前端不再传 `status`、也不再自己过滤返回值里不该出现的行——少一处"客户端替服务端补规则"，就少一处两端各写一遍、迟早写歪的地方。
- **返回体按 C 端页面裁剪**（如 `AlbumImageBriefVO` 只有四列），但同一份数据只有一个来源 service 方法；需要不同形状时在 service 里抽出私有复用（`listOnShelf()` / `toDtoList()` / `aggregateStats()` / `withCoverUrls()`），**不复制实现**。
- **身份仍然只有一个来源**：请求头 `Authorization: Bearer <token>` → `CurrentUserInterceptor` 验签 → service（2026-09-22 起，取代原先可伪造的 `X-User-Id` 头）。v14 个人分组/图片/城市及 covers/options 列表缺身份 401；B/C 相册裸 ID 跨分区/跨属主（含无身份私人访问）统一 404，批量先验全部，C 端另限上架。拦截器现在会校验令牌签名与有效期（带了 Bearer 但验不过一律 401，只有"根本没带头"才当匿名放行），身份不再可凭空伪造；分区/属主校验则是在可信身份之上的资源级护栏，二者叠加才构成隔离，静态文件 URL 仍不鉴权（见风险 14）。
- **密码本域没有 C 端接口**，h5 也没有那一页（grep 过：`packages/h5/src` 里运行时 `/api/b/` 零命中）。

> ⚠️ 这一轮的后果记在方案 §8.3：`/api/c/**` 现在带着**写接口**（上传、绑图、购物车、下单、推进状态、再来一单、加菜），"'C 端能访问、`/api/b/**` 403' 不再等价于'外人只能看不能改'"。当年那句"`X-User-Id` 是自报身份、部署前要么内网限流要么换服务端签发凭据"的三选一，**2026-09-22 已选了第三条**：身份换成服务端签发的 HMAC Bearer token，写接口按 `requireUserId()`/`requireAdmin()`/分区属主校验放行，匿名伪造头不再被采信。网络边界（§8.3）仍建议保留作纵深防御，但不再是挡住越权的唯一手段。

## 相册分区边界与版本记录

- **v14（当前）**：B 端家庭相册、个人相册为平级一级菜单，各有分组/图片管理/图片分布；家庭路由 `/album/{groups,images,distribution,:groupId}`，个人路由 `/album/personal/{groups,images,distribution,:groupId}`，旧 B 端 `/album/personal` 重定向至 `/album/personal/groups`。前端 query cache 为 `['album',scope,userId,...]`，路由/账号切换重挂。H5 私人入口已有且不改为 B 端重定向，页面、绑定、cities 补 scope。数据模型与接口以本 README 的 v14 表为准。
- **迁移与文件边界**：V211 将跨家庭/个人账号共享的 image 拆成独立行，保留元信息、上传人及时间；同 owner 多组仍共行。暂共享 fileId，删图/删组先查全 album 未删除图片活引用，另一侧仍在用就不能清盘。新写入不得跨分区复用 image；新 file 必须本人上传，另一 scope 已用的 fileId 必须重传。存储层仍可通过新 file_object + 硬链接复用字节，不能混同于复用 image 或 fileId。
- **构建与迁移已验证（2026-09-22）**：后端 package 已通过，最新后端已用 JDK 21 本地启动；本地 `family_home` 的 V211 迁移 `success=1`，独立迁移 **12 项断言通过**；全端 `pnpm typecheck`、admin/h5 build 再次成功。
- **真实 API 已验证**：`fh-boot/target/verify_v14_api.py` 最新日志 `verify_v14_api_20260922-163033-906ed5.jsonl` 的 **570 项断言全通过（含请求/清理核验）**。覆盖家庭及两账号共 3 分区、B/C 跨分区/跨属主 404、个人纯列表无身份 401、groupId 定向私人查询无身份 404（预期隐藏资源，非缺陷）、批量失败原子性、跨区 fileId 拒绝、同字节分区独立、4 轮同 fresh fileId 并发仅一方领取；历史共享文件删一侧另一侧 URL 200，最后引用删除后 URL 404。脚本自建 **8 组 19 文件已清理**。
- **浏览器交互已验证**：双平级菜单各 3 子项；B 个人图片管理上传候选仅个人组，上传成功并改城市为杭州；个人分布杭州 1 与家庭杭州 5/北京 1 独立，城市弹窗图片加载成功。C 私人相册可见 B 上传，详情固定组另传 1 图、原图预览成功，家庭列表与上传候选不混私人组；B 旧 `/album/personal` 已验证重定向至 `/album/personal/groups`。在 C 预览、B 编辑弹窗中通过现有 `auth.setCurrentUser` 注入切小宝，旧图/弹窗清空（C 定向旧组显示不存在，B 图数 0），随后恢复大宝；**这是 store 切换测试，不是重测登录**。
- **限制与收尾**：浏览器 surface 隐藏导致截图失败，**不宣称截图视觉验收**。不同 fileId 并发城市重算尚未压测，仍有竞争风险；同 fresh fileId 的并发通过不覆盖此项。浏览器测试 **group15「隔离验收v14-大宝」（PERSONAL/账号 1）及 image38/file67、image102/file125 两图仍保留：UI 删除被工具 Auto mode 安全限制拦截，未执行、未绕过；拦截后已只读确认仍在架**。
- **v12 / v13（历史，旧口径整体已由 v14 替代）**：v12 用 V210 加分组 scope，当时 C 端排除 PERSONAL；v13 开放 H5 私人入口，但仍图片共行、城市全局。旧版浏览器结果不计入 v14 验收。
- **仍不是完整权限安全（方案风险 14）**：相册 B/C 裸 ID 分区/属主校验、城市分区、图片拆行已纳入 v14；不能再写成这些尚未实现。原先那条"`X-User-Id` 可伪造"已于 2026-09-22 堵死（身份改为服务端签发的 HMAC Bearer token）；但静态文件 URL 仍不鉴权、历史拆行暂共享 fileId，仍不能称为「完全私密」或「安全隔离已完成」。

## 日志口径

服务层写操作打 info（中文动作 + 键值对），查询路径不打；异常统一由 `GlobalExceptionHandler` 记 warn/error，业务代码不重复打。vault 刻意不开 DAO 层 SQL 日志，避免口令密文落盘。

**日志只保留 7 天（2026-09-22，仅 prod 生效）**：prod 用 `logging.file.name=/data/family-home/logs/server.log` + `logging.logback.rollingpolicy.max-history=7` + `clean-history-on-start=true`。logback 按天（单文件超 10MB 时再按序号）把 `server.log` 滚成 `server.log.yyyy-MM-dd.i.gz`，**每次滚动时自动删掉超过 7 天的归档**，`clean-history-on-start` 再保证应用重启（含跨天停机）时也清一次——这就是"超过 7 天定时删除"，不需要另写 `@Scheduled`/cron。`file-name-pattern` 不另设，沿用 Spring Boot 默认。**dev 只打控制台、不落文件**（没配 `logging.file.name`），所以日志保留是 prod 的事，dev 无从堆积。

2026-09-22 的传输层加密**没有放松这一条，反而把它适用范围扩大了**：请求体里那一格现在装的是密文，`toString()` 里看不到明文，但**密文可逆、且解密所需的盐在前端产物里 → 密文进日志等于凭据进日志**。所以"带 `password` 字段的 DTO 实例禁止出现在日志与异常消息里"这条对**明文与密文一视同仁**，`VaultPasswordVO`（reveal 的返回体）同理。`TransportCipher` 自己只在启动时打一条 `log.info("口令传输层加密已就绪: algorithm=..., kdf=.../N轮")`——**盐本身一个字符都不打**，抛出的异常信息里也不带密文或明文。

账号这一域多一条：**口令（明文与哈希）与手机号都从头到尾不进日志**。登录成功/口令不匹配、改资料、改口令，`AppUserService` 打的都只有 `id` + `name`；`UserPasswordManager` 只在"库里这串格式不认识"时记一条不含任何内容的 warn，`derive()` 抛出的异常信息里也不带明文。明文口令在一个进程里的存活范围就是那一次请求对象——`PBEKeySpec` 用完即 `clearPassword()`。手机号原来是登录核对项所以有这一条，V502 后它退成资料项，口径照搬没删：它照样是家里几个人的个人信息，而日志文件是"所有人都能看的地方"。
