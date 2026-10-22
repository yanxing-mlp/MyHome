-- system 域（版本号段 V6xx）：给操作审计补 traceId，把「一次操作」与「它产生的日志」串起来（方案 v46）。
--
-- trace_id 由 TraceIdFilter 每请求生成（16 位十六进制），同一份 id 落在三处：
--   ① MDC → 日志行（application.yml 的 logging.pattern.level 注入 %X{traceId}）；
--   ② 本列 operation_log.trace_id → 操作记录页展开可见、可复制、可一键跳到系统日志页按它过滤；
--   ③ 响应头 X-Trace-Id。
-- 于是从一条审计行拿到 trace_id，就能在系统日志里定位这次请求期间的全部日志（含异常堆栈、SQL），实现「日志回溯」。
--
-- 可空：V600 之前落库的历史审计行没有 trace_id（那时还没这套机制），保持 NULL 即可，不回填。
-- 不建索引：trace_id 的用途是「从审计行读出来 → 拿去日志文本里过滤」，日志过滤发生在系统日志页对 tail 内容做客户端匹配，
-- 并不反查 operation_log；审计列表本身仍按时间倒序分页，没有按 trace_id 检索的场景，故不加索引（与最小实现口径一致）。
ALTER TABLE `operation_log`
  ADD COLUMN `trace_id` VARCHAR(64) DEFAULT NULL COMMENT '请求链路 id，与日志行/响应头 X-Trace-Id 同值，用于日志回溯' AFTER `client_ip`;
