-- 做法不参与排序：分组和选项都按录入顺序（id）展示，C 端默认选中组内第一个也是这个顺序。
-- 排序权重是当初照分类/标签一起加的，实际没人拖，删掉列让"没有排序"这件事在库里也成立。
-- 只删 sort_order，名字/关联关系一律不动。

ALTER TABLE `recipe_practice_group`
  DROP COLUMN `sort_order`;

ALTER TABLE `recipe_practice_option`
  DROP COLUMN `sort_order`;
