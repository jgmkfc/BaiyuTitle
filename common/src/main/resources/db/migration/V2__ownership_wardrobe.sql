-- ============================================================
-- V2__ownership_wardrobe.sql —— 所有权与多栏位装扮
-- 建表：
--   1. "${tablePrefix}title_ownership" 所有权实例表（只追加不删除；每 (owner, title) 至多一条 ACTIVE，
--      由 active_marker（ACTIVE=TRUE 否则 NULL）+ 三列 UNIQUE 结构化保证）
--   2. "${tablePrefix}player_slot"      玩家装扮栏位表（FIXED/RANDOM；title_id NULL=默认称号）
--   3. "${tablePrefix}player_slot_pool" 随机栏位候选池表
-- 约定：时间列 BIGINT（UTC epoch 毫秒）；UUID 列 BINARY(16)；不用外键。
-- ============================================================

CREATE TABLE "${tablePrefix}title_ownership" (
    "id"            BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    "title_id"      INT         NOT NULL,
    "owner_uuid"    BINARY(16)  NOT NULL,
    "owner_name"    VARCHAR(16) NOT NULL,
    "source"        VARCHAR(16) NOT NULL,
    "source_ref"    BIGINT      NULL,
    "acquired_at"   BIGINT      NOT NULL,
    "expires_at"    BIGINT      NULL,
    "status"        VARCHAR(12) NOT NULL DEFAULT 'ACTIVE',
    "active_marker" BOOLEAN     NULL,
    "ended_at"      BIGINT      NULL,
    "ended_reason"  VARCHAR(32) NULL,
    UNIQUE ("owner_uuid", "title_id", "active_marker")
);

CREATE INDEX "${tablePrefix}idx_title_ownership_owner_status" ON "${tablePrefix}title_ownership" ("owner_uuid", "status");
CREATE INDEX "${tablePrefix}idx_title_ownership_title_status" ON "${tablePrefix}title_ownership" ("title_id", "status");
CREATE INDEX "${tablePrefix}idx_title_ownership_status_exp" ON "${tablePrefix}title_ownership" ("status", "expires_at");

CREATE TABLE "${tablePrefix}player_slot" (
    "player_uuid" BINARY(16) NOT NULL,
    "slot_index"  INT        NOT NULL,
    "enabled"     BOOLEAN    NOT NULL DEFAULT TRUE,
    "mode"        VARCHAR(8) NOT NULL DEFAULT 'FIXED',
    "title_id"    INT        NULL,
    "updated_at"  BIGINT     NOT NULL,
    PRIMARY KEY ("player_uuid", "slot_index")
);

CREATE INDEX "${tablePrefix}idx_player_slot_title" ON "${tablePrefix}player_slot" ("title_id");

CREATE TABLE "${tablePrefix}player_slot_pool" (
    "player_uuid" BINARY(16) NOT NULL,
    "slot_index"  INT        NOT NULL,
    "title_id"    INT        NOT NULL,
    PRIMARY KEY ("player_uuid", "slot_index", "title_id")
);

CREATE INDEX "${tablePrefix}idx_player_slot_pool_title" ON "${tablePrefix}player_slot_pool" ("title_id");
