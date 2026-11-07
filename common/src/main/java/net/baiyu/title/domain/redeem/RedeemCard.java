package net.baiyu.title.domain.redeem;

import java.util.Objects;
import java.util.UUID;

/**
 * 称号卡（对应 {@code redeem_card} 表，全列对齐）。
 * <p>物品 PDC 存同一 {@code cardUuid} 防刷；核销以条件 UPDATE 保证单次
 * （不变量 §十 14），成功后主线程消耗物品。
 *
 * @param cardUuid         卡片 UUID（主键，与物品 PDC 一致）
 * @param titleId          兑换目标称号 id
 * @param holdDaysOverride 持有天数覆写（可空；空 = 按称号默认）
 * @param issuedBy         发卡者名称（可空；控制台发卡为空）
 * @param issuedAt         发卡时刻（UTC epoch 毫秒）
 * @param redeemedBy       核销者 UUID（可空；未核销为空）
 * @param redeemedAt       核销时刻（可空；未核销为空）
 * @param ownershipId      核销产生的所有权 id（可空）
 */
public record RedeemCard(
        UUID cardUuid,
        int titleId,
        Integer holdDaysOverride,
        String issuedBy,
        long issuedAt,
        UUID redeemedBy,
        Long redeemedAt,
        Long ownershipId) {

    /**
     * 紧凑构造器：卡片 UUID 判空、覆写天数非负校验。
     */
    public RedeemCard {
        Objects.requireNonNull(cardUuid, "卡片 UUID 不能为空");
        if (holdDaysOverride != null && holdDaysOverride < 0) {
            throw new IllegalArgumentException("持有天数覆写不能为负: " + holdDaysOverride);
        }
    }

    /**
     * 是否已核销。
     *
     * @return 已核销返回 true
     */
    public boolean isRedeemed() {
        return redeemedAt != null;
    }

    /**
     * 是否带持有天数覆写。
     *
     * @return 有覆写返回 true
     */
    public boolean hasHoldOverride() {
        return holdDaysOverride != null;
    }
}
