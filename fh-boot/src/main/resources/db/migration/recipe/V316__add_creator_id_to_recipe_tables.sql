-- recipe 域（V3xx 号段续）：菜品、菜品分类、订单各加"添加人"。
--
-- 口径与 file/V104 一致（可空、不建索引、只存 id 不存昵称，昵称由前端查账号字典）。
--
-- 【recipe_order 这一列就是"C 端下单人"】
-- 需求里"订单的添加人"和"C 端下单人"是同一件事：谁点的这一单。所以不再另立 order_no 那种
-- 业务单号，也不建第二列——B 端点单列表那一列显示成"下单人"，C 端订单列表/详情同样显示"下单人"，
-- 只有这一张表的措辞跟别的表不一样，因为"添加人"在那两页读起来是错的。
-- 【继续加菜不改它】append 只往明细里并菜、累加 total_qty，下单人仍是当初发起这一单的人。
--
-- 【recipe_category 是硬删除 + 级联解绑的字典表】creator_id 记录"谁建了这个分类"，
-- 改名（表内内联）与拖拽排序都不动它。
ALTER TABLE `recipe`
  ADD COLUMN `creator_id` BIGINT UNSIGNED DEFAULT NULL COMMENT '添加人 app_user.id（谁加的这道菜；改菜/上下架不动它）' AFTER `status`;

ALTER TABLE `recipe_category`
  ADD COLUMN `creator_id` BIGINT UNSIGNED DEFAULT NULL COMMENT '添加人 app_user.id（谁建了这个分类；改名/排序不动它）' AFTER `sort_order`;

ALTER TABLE `recipe_order`
  ADD COLUMN `creator_id` BIGINT UNSIGNED DEFAULT NULL COMMENT '下单人 app_user.id（C 端发起这一单的人；继续加菜不改它）' AFTER `total_qty`;
