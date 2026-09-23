-- 初始化做法字典（与需求口径一致）：辣度[不辣/微辣/好辣]、糖[不加糖/加糖]。
-- 显式 id 与 V301 初始化风格一致，AUTO_INCREMENT 会自然续到 3。

INSERT INTO `recipe_practice_group` (`id`, `name`, `sort_order`) VALUES
  (1, '辣度', 1),
  (2, '糖', 2);

INSERT INTO `recipe_practice_option` (`group_id`, `name`, `sort_order`) VALUES
  (1, '不辣', 1),
  (1, '微辣', 2),
  (1, '好辣', 3),
  (2, '不加糖', 1),
  (2, '加糖', 2);
