-- 订单状态加"已取消"：待制作的单两端都能取消，取消 = 这一顿不做了。
-- 只是把列注释改成实况——status 是 VARCHAR(16)，没有枚举/CHECK 约束，存得下新值，不动类型和默认值。
-- 已取消是终态（两端都不给改回的入口），且点单统计/"点过 x 次"都不再算它的明细。

ALTER TABLE `recipe_order`
  MODIFY COLUMN `status` VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT '订单状态：PENDING=待制作 / COMPLETED=已完成 / CANCELLED=已取消';
