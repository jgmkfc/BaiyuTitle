-- ============================================================
-- V4__trade.sql —— 称号交易
-- 建表：
--   1. "${tablePrefix}trade"      交易单表（状态机 PENDING/COMPLETED/REJECTED/EXPIRED/DESTROYED；
--      expires_at 来自 config trade.offer-ttl，默认 72 小时）
--   2. "${tablePrefix}trade_item" 交易标的表（每侧至多 12 项；引用具体所有权实例，剩余时间随之转移；
--      title_name_snapshot 为创建报价时的称号名快照）
-- 约定：时间列 BIGINT（UTC epoch 毫秒）；UUID 列 BINARY(16)；不用外键。
-- ============================================================

CREATE TABLE "${tablePrefix}trade" (
    "id"             BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    "initiator_uuid" BINARY(16)  NOT NULL,
    "initiator_name" VARCHAR(16) NOT NULL,
    "target_uuid"    BINARY(16)  NOT NULL,
    "target_name"    VARCHAR(16) NOT NULL,
    "status"         VARCHAR(12) NOT NULL DEFAULT 'PENDING',
    "created_at"     BIGINT      NOT NULL,
    "expires_at"     BIGINT      NOT NULL,
    "resolved_at"    BIGINT      NULL,
    "resolved_by"    VARCHAR(16) NULL,
    "fail_reason"    VARCHAR(64) NULL
);

CREATE INDEX "${tablePrefix}idx_trade_initiator_status" ON "${tablePrefix}trade" ("initiator_uuid", "status");
CREATE INDEX "${tablePrefix}idx_trade_target_status" ON "${tablePrefix}trade" ("target_uuid", "status");
CREATE INDEX "${tablePrefix}idx_trade_status_exp" ON "${tablePrefix}trade" ("status", "expires_at");
CREATE INDEX "${tablePrefix}idx_trade_initiator_name" ON "${tablePrefix}trade" ("initiator_name");
CREATE INDEX "${tablePrefix}idx_trade_target_name" ON "${tablePrefix}trade" ("target_name");

CREATE TABLE "${tablePrefix}trade_item" (
    "id"                  BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    "trade_id"            BIGINT        NOT NULL,
    "side"                VARCHAR(9)    NOT NULL,
    "ownership_id"        BIGINT        NOT NULL,
    "title_id"            INT           NOT NULL,
    "title_name_snapshot" VARCHAR(2048) NOT NULL
);

CREATE INDEX "${tablePrefix}idx_trade_item_trade" ON "${tablePrefix}trade_item" ("trade_id");
