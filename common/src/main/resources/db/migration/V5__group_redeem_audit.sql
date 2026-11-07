-- ============================================================
-- V5__group_redeem_audit.sql —— 玩家组、兑换（码/卡）与审计日志
-- 建表：
--   1. "${tablePrefix}player_group"        玩家组表（name 服务层限 [a-z0-9_]，软删除）
--   2. "${tablePrefix}player_group_member" 组成员表
--   3. "${tablePrefix}redeem_code"         兑换码表（code 大写归一化；max_uses=0 表示无限）
--   4. "${tablePrefix}redeem_code_use"     兑换码使用记录表（按玩家限次校验依据）
--   5. "${tablePrefix}redeem_card"         称号卡表（card_uuid 与物品 PDC 同值，条件 UPDATE 单次核销防刷）
--   6. "${tablePrefix}audit_log"           审计日志表（detail 为 JSON 文本）
-- 约定：时间列 BIGINT（UTC epoch 毫秒）；UUID 列 BINARY(16)；不用外键。
-- ============================================================

CREATE TABLE "${tablePrefix}player_group" (
    "id"           INT          NOT NULL AUTO_INCREMENT PRIMARY KEY,
    "name"         VARCHAR(64)  NOT NULL,
    "display_name" VARCHAR(255) NULL,
    "created_by"   VARCHAR(16)  NULL,
    "created_at"   BIGINT       NOT NULL,
    "deleted_at"   BIGINT       NULL,
    UNIQUE ("name")
);

CREATE TABLE "${tablePrefix}player_group_member" (
    "group_id"    INT         NOT NULL,
    "player_uuid" BINARY(16)  NOT NULL,
    "player_name" VARCHAR(16) NOT NULL,
    "added_by"    VARCHAR(16) NULL,
    "added_at"    BIGINT      NOT NULL,
    PRIMARY KEY ("group_id", "player_uuid")
);

CREATE INDEX "${tablePrefix}idx_player_group_member_player" ON "${tablePrefix}player_group_member" ("player_uuid");

CREATE TABLE "${tablePrefix}redeem_code" (
    "id"              BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    "code"            VARCHAR(64) NOT NULL,
    "title_id"        INT         NOT NULL,
    "max_uses"        INT         NOT NULL DEFAULT 1,
    "used_count"      INT         NOT NULL DEFAULT 0,
    "per_player_uses" INT         NOT NULL DEFAULT 1,
    "valid_from"      BIGINT      NULL,
    "valid_until"     BIGINT      NULL,
    "enabled"         BOOLEAN     NOT NULL DEFAULT TRUE,
    "created_by"      VARCHAR(16) NULL,
    "created_at"      BIGINT      NOT NULL,
    UNIQUE ("code")
);

CREATE TABLE "${tablePrefix}redeem_code_use" (
    "id"           BIGINT     NOT NULL AUTO_INCREMENT PRIMARY KEY,
    "code_id"      BIGINT     NOT NULL,
    "player_uuid"  BINARY(16) NOT NULL,
    "used_at"      BIGINT     NOT NULL,
    "ownership_id" BIGINT     NULL
);

CREATE INDEX "${tablePrefix}idx_redeem_code_use_code_player" ON "${tablePrefix}redeem_code_use" ("code_id", "player_uuid");

CREATE TABLE "${tablePrefix}redeem_card" (
    "card_uuid"          BINARY(16)  NOT NULL PRIMARY KEY,
    "title_id"           INT         NOT NULL,
    "hold_days_override" INT         NULL,
    "issued_by"          VARCHAR(16) NULL,
    "issued_at"          BIGINT      NOT NULL,
    "redeemed_by"        BINARY(16)  NULL,
    "redeemed_at"        BIGINT      NULL,
    "ownership_id"       BIGINT      NULL
);

CREATE TABLE "${tablePrefix}audit_log" (
    "id"           BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    "event_type"   VARCHAR(32) NOT NULL,
    "actor_uuid"   BINARY(16)  NULL,
    "actor_name"   VARCHAR(64) NOT NULL,
    "target_uuid"  BINARY(16)  NULL,
    "title_id"     INT         NULL,
    "ownership_id" BIGINT      NULL,
    "ref_table"    VARCHAR(32) NULL,
    "ref_id"       BIGINT      NULL,
    "detail"       TEXT        NULL,
    "created_at"   BIGINT      NOT NULL
);

CREATE INDEX "${tablePrefix}idx_audit_log_created" ON "${tablePrefix}audit_log" ("created_at");
CREATE INDEX "${tablePrefix}idx_audit_log_target" ON "${tablePrefix}audit_log" ("target_uuid");
