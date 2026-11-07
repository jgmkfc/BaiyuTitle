package net.baiyu.title.domain.condition;

import java.util.Objects;

/**
 * 物品购买条件（对应 V1 表列 item_data TEXT + item_amount INT）。
 * <p>物品以 Paper {@code ItemStack#serializeAsBytes()} 的 Base64 字符串存储，
 * 领域层不引用任何 Bukkit 类型。
 *
 * @param itemBase64 物品序列化 Base64 字符串
 * @param amount     所需数量（必须 ≥ 1）
 */
public record ItemCondition(String itemBase64, int amount) implements PurchaseCondition {

    /**
     * 校验物品数据非空白且数量 ≥ 1。
     */
    public ItemCondition {
        Objects.requireNonNull(itemBase64, "物品数据不能为空");
        if (itemBase64.isBlank()) {
            throw new IllegalArgumentException("物品数据不能为空白");
        }
        if (amount < 1) {
            throw new IllegalArgumentException("物品数量必须 ≥ 1: " + amount);
        }
    }

    @Override
    public ConditionType type() {
        return ConditionType.ITEM;
    }
}
