package net.baiyu.title.domain.coin;

import java.util.Objects;
import java.util.UUID;

/**
 * 称号币流水（对应 {@code coin_log} 表）。
 *
 * @param id           主键（未落库时为 0）
 * @param playerUuid   玩家 UUID
 * @param delta        变动量（正 = 入账，负 = 扣款）
 * @param balanceAfter 变动后余额（余额恒 ≥ 0，条件 UPDATE 禁透支）
 * @param reason       变动原因
 * @param actor        操作者（管理员名或 SYSTEM）
 * @param refId        引用 id（可空；如订单 id）
 * @param createdAt    记账时刻（UTC epoch 毫秒）
 */
public record CoinLogEntry(
        long id,
        UUID playerUuid,
        long delta,
        long balanceAfter,
        CoinChangeReason reason,
        String actor,
        Long refId,
        long createdAt) {

    /**
     * 紧凑构造器：必填字段判空、余额非负校验。
     */
    public CoinLogEntry {
        Objects.requireNonNull(playerUuid, "玩家 UUID 不能为空");
        Objects.requireNonNull(reason, "变动原因不能为空");
        Objects.requireNonNull(actor, "操作者不能为空");
        if (balanceAfter < 0) {
            throw new IllegalArgumentException("变动后余额不能为负: " + balanceAfter);
        }
    }
}
