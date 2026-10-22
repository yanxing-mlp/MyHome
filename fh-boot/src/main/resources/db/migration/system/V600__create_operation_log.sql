-- system 域（版本号段 V6xx）：跨域的运维/审计能力，不属于相册/菜谱/文件/密码本/账号任何一个业务域。
-- 与 fh-boot 里的 LogService / DeployService / ChangeLogService 同一层——它们是"关于这套系统本身"的功能，
-- 而非某个业务域的数据，所以单开一个 system 号段、由 fh-boot 直接持有实体与 Mapper。
--
-- 【operation_log：用户操作审计】需求「查看用户的操作记录，区分 b 端和 c 端，每一步操作都要有：
-- 在哪个页面、点击了什么、出入参、时间点，只有超管能看」。读写全记（用户明确选择），量由保留天数兜。
--   - source：B=管理端(/api/b/**) / C=家庭端(/api/c/**)，由请求 uri 前缀判定。
--   - page：前端两端 http 层单点注入的 X-Op-Page 头（当前页中文名）——后端天然只知道"调了哪个接口"，
--     "在哪个页面"只有前端知道，故由前端带上来。
--   - action：后端"动作字典"把 METHOD+uri 翻成中文（如 DELETE /api/b/album/image → 删除相册图片）。
--   - request_params / response_summary：脱敏（password/token/secret/口令一律 ***）+ 截断后的文本。
--   - 写一次不改，但按建表规范仍带 update_time（与全库一致）。
--
-- 【operation_log_setting：单行配置】仿 recipe_cart_state 的固定 id=1 单行口径，只存保留天数。
--   - 保留清理靠 fh-boot 的 @Scheduled 每日按 retention_days 删过期行；默认 7 天，超管可在页面改（1..365）。
CREATE TABLE `operation_log` (
  `id`               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `source`           VARCHAR(8)      NOT NULL                COMMENT 'B=管理端 / C=家庭端',
  `user_id`          BIGINT UNSIGNED DEFAULT NULL            COMMENT '操作人 app_user.id；匿名（如登录前）为空',
  `user_name`        VARCHAR(32)     DEFAULT NULL            COMMENT '操作人昵称快照，账号被删后仍能读出当时是谁',
  `user_role`        VARCHAR(16)     DEFAULT NULL            COMMENT '操作人角色快照 ADMIN/MEMBER',
  `page`             VARCHAR(128)    DEFAULT NULL            COMMENT '前端 X-Op-Page 头：操作发生的页面中文名',
  `action`           VARCHAR(64)     NOT NULL                COMMENT '中文动作，动作字典按 METHOD+uri 翻译',
  `http_method`      VARCHAR(8)      NOT NULL                COMMENT 'GET/POST/PUT/DELETE',
  `uri`              VARCHAR(255)    NOT NULL                COMMENT '请求路径（不含 query）',
  `request_params`   TEXT            DEFAULT NULL            COMMENT '脱敏后的入参：query + body 的 JSON',
  `response_summary` TEXT            DEFAULT NULL            COMMENT '脱敏 + 截断后的响应摘要：code/message/data',
  `success`          TINYINT         NOT NULL DEFAULT 1      COMMENT '1=成功 0=失败（HTTP>=400 或 Result.code!=0）',
  `error_code`       VARCHAR(64)     DEFAULT NULL            COMMENT '失败时的业务错误码',
  `duration_ms`      INT             NOT NULL DEFAULT 0      COMMENT '服务端处理耗时（毫秒）',
  `client_ip`        VARCHAR(64)     DEFAULT NULL            COMMENT '来源 IP',
  `create_time`      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '操作发生时间',
  `update_time`      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_create_time` (`create_time`),
  KEY `idx_source` (`source`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_success` (`success`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户操作审计（读写全记，按保留天数清理）';

CREATE TABLE `operation_log_setting` (
  `id`             BIGINT UNSIGNED NOT NULL              COMMENT '固定 1，单行配置',
  `retention_days` INT             NOT NULL DEFAULT 7    COMMENT '操作记录保留天数，超期由定时任务清理',
  `create_time`    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time`    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='操作记录配置（单行，保留天数）';

INSERT INTO `operation_log_setting` (`id`, `retention_days`) VALUES (1, 7);
