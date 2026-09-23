-- 订单明细加封面图快照（V3xx 号段续）。
-- C 端订单列表/详情要在每一行显示菜品图片，而明细是下单时的快照（菜名、做法都存死了），
-- 所以图片也走同一口径：下单/继续加菜那一刻把当时那道菜的封面 URL 抄一份进来。
-- 好处是历史订单不会跟着之后的换图/删菜变；代价是这列之前的存量行没有值，那几行就是不显示图。
-- 存 URL 而不是 file_id：读侧不用再调一次 FileFacade，和 recipe_name 快照一样是"当时长什么样就留什么"。

ALTER TABLE `recipe_order_item`
  ADD COLUMN `cover_url` VARCHAR(500) DEFAULT NULL COMMENT '封面图 URL 快照，NULL=下单时这道菜没图' AFTER `recipe_name`;
