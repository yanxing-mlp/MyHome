-- 做法绑定从"只勾分组"升级为"分组 + 这道菜在该组上的两项配置"：
--   required          = 1 时 C 端必须在这组里选一个做法（可以不改默认值，但不能不选）；0 = 可以不选
--   default_option_id = 进入详情浮层时预选的选项，NULL = 不预选；必须是本分组的选项
-- 存量按"必选 + 默认选中组内第一个"回填：改造前 C 端就是无条件取 options[0] 且不给取消，
-- 回填后老菜谱的下单行为一个不变。
-- 选项日后被删掉时本列不清空：读侧（B/C 端）按"该 optionId 不在字典里 = 无默认"处理，与 practiceSummary 忽略未知选项同口径。

ALTER TABLE `recipe_practice_rel`
  ADD COLUMN `required` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '该菜在此分组下必须选一个做法：1=必选，0=可不选' AFTER `group_id`,
  ADD COLUMN `default_option_id` BIGINT UNSIGNED DEFAULT NULL COMMENT '默认选中的选项 ID，NULL=不预选' AFTER `required`;

UPDATE `recipe_practice_rel` r
SET r.`required` = 1,
    r.`default_option_id` = (
      SELECT MIN(o.`id`) FROM `recipe_practice_option` o WHERE o.`group_id` = r.`group_id`
    );
