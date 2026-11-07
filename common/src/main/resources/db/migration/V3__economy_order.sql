-- ============================================================
-- V3__economy_order.sql —— 称号币经济与购买订单
-- 建表：
--   1. "${tablePrefix}player_coin"    称号币余额表（余额 >= 0，由条件 UPDATE 保证）
--   2. "${tablePrefix}coin_log"       称号币流水表（每次变动一行，与变动同事务写入）
--   3. "${tablePrefix}purchase_order" 购买订单表（状态机 RESERVED/PAYING/FULFILLED/FAILED/REFUNDED/MANUAL；
--      order_uuid 为幂等键；价格列为下单时快照）
-- 约定：时间列 BIGINT（UTC epoch 毫秒）；UUID 列 BINARY(16)；金额 DECIMAL(18,2)；不用外键。
-- ============================================================

CREATE TABLE "${tablePrefix}player_coin" (
    "player_uuid" BINARY(16)  NOT NULL PRIMARY KEY,
    "player_name" VARCHAR(16) NOT NULL,
    "balance"     BIGINT      NOT NULL DEFAULT 0,
    "updated_at"  BIGINT      NOT NULL
);

CREATE TABLE "${tablePrefix}coin_log" (
    "id"            BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    "player_uuid"   BINARY(16)  NOT NULL,
    "delta"         BIGINT      NOT NULL,
    "balance_after" BIGINT      NOT NULL,
    "reason"        VARCHAR(32) NOT NULL,
    "actor"         VARCHAR(64) NOT NULL,
    "ref_id"        BIGINT      NULL,
    "created_at"    BIGINT      NOT NULL
);

CREATE INDEX "${tablePrefix}idx_coin_log_player_created" ON "${tablePrefix}coin_log" ("player_uuid", "created_at");

CREATE TABLE "${tablePrefix}purchase_order" (
    "id"              BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    "order_uuid"      BINARY(16)    NOT NULL,
    "player_uuid"     BINARY(16)    NOT NULL,
    "player_name"     VARCHAR(16)   NOT NULL,
    "title_id"        INT           NULL,
    "kind"            VARCHAR(8)    NOT NULL DEFAULT 'SHOP',
    "chosen_cond"     VARCHAR(16)   NULL,
    "vault_amount"    DECIMAL(18,2) NULL,
    "coin_amount"     BIGINT        NULL,
    "item_data"       TEXT          NULL,
    "item_amount"     INT           NULL,
    "permission_node" VARCHAR(255)  NULL,
    "custom_name"     VARCHAR(2048) NULL,
    "state"           VARCHAR(16)   NOT NULL,
    "fail_reason"     VARCHAR(64)   NULL,
    "created_at"      BIGINT        NOT NULL,
    "updated_at"      BIGINT        NOT NULL,
    UNIQUE ("order_uuid")
);

CREATE INDEX "${tablePrefix}idx_purchase_order_state_upd" ON "${tablePrefix}purchase_order" ("state", "updated_at");
CREATE INDEX "${tablePrefix}idx_purchase_order_player_created" ON "${tablePrefix}purchase_order" ("player_uuid", "created_at");
CREATE INDEX "${tablePrefix}idx_purchase_order_title_state" ON "${tablePrefix}purchase_order" ("title_id", "state");
