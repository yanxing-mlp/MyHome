-- 订单状态加"已完成"：C 端点"已完成"把 PENDING 推到 COMPLETED，单向、不做撤销。
-- 只是把列注释改成实况——status 是 VARCHAR(16)，没有枚举/CHECK 约束，存得下新值，不动类型和默认值。

ALTER TABLE `recipe_order`
  MODIFY COLUMN `status` VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT '订单状态：PENDING=待制作 / COMPLETED=已完成';
