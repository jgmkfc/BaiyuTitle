-- ============================================================
-- V1__title.sql —— 称号主数据
-- 建表：
--   1. "${tablePrefix}title"                  称号主表（种类/稀有度/限购模式/获取窗口/软删除）
--   2. "${tablePrefix}title_condition"        购买条件表（每 (title_id, cond_type) 至多一条；免费=无条件行）
--   3. "${tablePrefix}title_bond_requirement" 羁绊称号需求表（bond -> required 集合，逻辑恒 AND）
-- 约定：时间列 BIGINT（UTC epoch 毫秒）；UUID 列 BINARY(16)；金额 DECIMAL(18,2)；
--       窗口日期 CHAR(10)（ISO 格式，闭区间）；不用外键，列上建普通索引。
-- ============================================================

CREATE TABLE "${tablePrefix}title" (
    "id"                 INT           NOT NULL AUTO_INCREMENT PRIMARY KEY,
    "name"               VARCHAR(2048) NOT NULL,
    "name_plain"         VARCHAR(128)  NOT NULL,
    "category"           VARCHAR(16)   NOT NULL DEFAULT 'NORMAL',
    "combine_logic"      VARCHAR(4)    NOT NULL DEFAULT 'AND',
    "visible"            BOOLEAN       NOT NULL DEFAULT TRUE,
    "tradable"           BOOLEAN       NOT NULL DEFAULT TRUE,
    "rarity"             VARCHAR(64)   NULL,
    "description"        TEXT          NULL,
    "icon_material"      VARCHAR(128)  NOT NULL DEFAULT 'minecraft:name_tag',
    "hold_duration_days" INT           NOT NULL DEFAULT 0,
    "limit_mode"         VARCHAR(8)    NOT NULL DEFAULT 'NONE',
    "window_start"       CHAR(10)      NULL,
    "window_end"         CHAR(10)      NULL,
    "total_cap"          INT           NULL,
    "sold_count"         INT           NOT NULL DEFAULT 0,
    "group_id"           INT           NULL,
    "created_by"         BINARY(16)    NULL,
    "created_by_name"    VARCHAR(16)   NULL,
    "created_at"         BIGINT        NOT NULL,
    "updated_at"         BIGINT        NOT NULL,
    "deleted_at"         BIGINT        NULL
);

CREATE INDEX "${tablePrefix}idx_title_category" ON "${tablePrefix}title" ("category");
CREATE INDEX "${tablePrefix}idx_title_name_plain" ON "${tablePrefix}title" ("name_plain");

CREATE TABLE "${tablePrefix}title_condition" (
    "id"              INT           NOT NULL AUTO_INCREMENT PRIMARY KEY,
    "title_id"        INT           NOT NULL,
    "cond_type"       VARCHAR(16)   NOT NULL,
    "vault_amount"    DECIMAL(18,2) NULL,
    "coin_amount"     BIGINT        NULL,
    "item_data"       TEXT          NULL,
    "item_amount"     INT           NULL,
    "permission_node" VARCHAR(255)  NULL,
    UNIQUE ("title_id", "cond_type")
);

CREATE TABLE "${tablePrefix}title_bond_requirement" (
    "bond_title_id"     INT NOT NULL,
    "required_title_id" INT NOT NULL,
    PRIMARY KEY ("bond_title_id", "required_title_id")
);

CREATE INDEX "${tablePrefix}idx_title_bond_requirement_req" ON "${tablePrefix}title_bond_requirement" ("required_title_id");
