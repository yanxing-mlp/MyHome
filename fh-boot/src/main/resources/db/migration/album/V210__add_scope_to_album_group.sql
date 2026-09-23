-- album_group 加 `scope`：家庭相册 / 个人相册（V2xx 号段续，V210）。
--
-- 【要解决的是什么】B 端「相册」下面多一个「个人相册」菜单：每个账号可以有自己的相册，
-- 里面的照片不进家庭相册、也不出现在 C 端给全家人刷到。
--
-- 【为什么是分组上一个列，而不是新建一张表】个人相册与家庭相册的差别只有一条——"给谁看"。
-- 建相册、改名、拖拽排序、往里传图（一张图一行 + N 条 album_image_group_rel）、级联删除、
-- 张数统计、封面顺序（置顶优先其次最新）这些口径两边完全一样。另起一张 personal_album 表
-- 等于把 AlbumGroupService 那套分桶/绑定/级联重写一遍，两边迟早写歪（相册域已经因为
-- "B 端按 group_id 数、C 端按关联表数"分叉过一次，见 V207 那段注释）。
--
-- 【属主 = creator_id，不另开 owner 列】个人相册只能由本人新建，V209 那条"改名/上下架不动 creator_id"
-- 的口径正好让它在个人相册这一档永远等于属主。多一列只会多一处"两个地方记同一个人、迟早记不一致"。
-- 所以 PERSONAL 行的 creator_id 不能为空：服务端按它筛"我的相册"，为空就永远筛不到（B 端登录后必带
-- X-User-Id，创建那一步 requireUserId() 已经挡在前面）。
--
-- 【DEFAULT 'FAMILY'，没有回填语句】存量那几本相册本来就是给全家看的，落到默认值就是正确答案。
--
-- 【索引不补】分组表在家庭场景就几十行，且个人相册那条查询的过滤是 `scope = ? AND creator_id = ?`
-- 加排序 sort/create_time——与 V208 那条"分组查询不再维护索引"的判断同一个口径，全表扫更便宜。
--
-- 注：本文件必须与代码同批上线——实体已带 scope 字段，只上代码不上迁移，
-- 分组查询与 INSERT 都会因缺列直接报错（Unknown column 'scope'）。
ALTER TABLE `album_group`
  ADD COLUMN `scope` VARCHAR(16) NOT NULL DEFAULT 'FAMILY'
    COMMENT 'FAMILY=家庭相册（C 端全家可见）/ PERSONAL=个人相册（只属主本人在 B 端可见，C 端一律不出）' AFTER `status`,
  COMMENT = '相册分组（三态状态 + 家庭/个人两档归属）';
