-- 早餐/午餐/晚餐这三个标签是 C 端点餐页"餐段"那一排横滑标签的数据源：
-- 那边不建表，全靠标签名和 recipe.tags 字符串比对来筛菜。餐段概念整体删掉后
-- 它们不再有任何消费方，留在字典里只是让 B 端标签管理凭空挂着三个没人用的标签。
-- 标签字典这个功能本身保留（通用能力），删的只是这批内置数据。
-- 口径与页面删标签一致：硬删除 + 先解绑再删字典（见 V300），这里在同一迁移里按序做掉。
-- 顺带把 recipe_tag.name 的列注释里那三个名字去掉——注释里写死示例名，
-- 正是当初把"餐段"当成内置概念的原因。

DELETE FROM `recipe_tag_rel`
WHERE `tag_id` IN (
  SELECT `id` FROM `recipe_tag` WHERE `name` IN ('早餐', '午餐', '晚餐')
);

DELETE FROM `recipe_tag`
WHERE `name` IN ('早餐', '午餐', '晚餐');

ALTER TABLE `recipe_tag`
  MODIFY COLUMN `name` VARCHAR(32) NOT NULL COMMENT '标签名';
