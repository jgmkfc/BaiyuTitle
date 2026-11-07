package net.baiyu.title.domain.order;

import net.baiyu.title.domain.condition.ConditionType;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * 购买订单（对应 {@code purchase_order} 表，全列对齐）。
 * <p>价格字段为下单时的快照（vault/coin/item/permission 四类，按条件组合逻辑取用）；
 * OR 逻辑时 {@code chosenCond} 记录玩家所选分支，AND 为空。
 *
 * @param id             主键（未落库时为 0）
 * @param orderUuid      订单幂等键（唯一）
 * @param playerUuid     买家 UUID
 * @param playerName     买家名称缓存
 * @param titleId        目标称号 id（可空；kind=CUSTOM 创建前为空，履约后回填）
 * @param kind           订单种类
 * @param chosenCond     OR 分支快照（可空；AND 逻辑为空）
 * @param vaultAmount    Vault 金额快照（可空）
 * @param coinAmount     称号币数量快照（可空）
 * @param itemData       物品序列化 Base64 快照（可空）
 * @param itemAmount     物品数量快照（可空）
 * @param permissionNode 权限节点快照（可空）
 * @param customName     自定义称号名（可空；仅 kind=CUSTOM）
 * @param state          订单状态
 * @param failReason     失败原因（可空）
 * @param createdAt      创建时刻（UTC epoch 毫秒）
 * @param updatedAt      更新时刻（UTC epoch 毫秒）
 */
public record PurchaseOrder(
        long id,
        UUID orderUuid,
        UUID playerUuid,
        String playerName,
        Integer titleId,
        OrderKind kind,
        ConditionType chosenCond,
        BigDecimal vaultAmount,
        Long coinAmount,
        String itemData,
        Integer itemAmount,
        String permissionNode,
        String customName,
        OrderState state,
        String failReason,
        long createdAt,
        long updatedAt) {

    /**
     * 紧凑构造器：必填字段判空。
     */
    public PurchaseOrder {
        Objects.requireNonNull(orderUuid, "订单幂等键不能为空");
        Objects.requireNonNull(playerUuid, "买家 UUID 不能为空");
        Objects.requireNonNull(playerName, "买家名称不能为空");
        Objects.requireNonNull(kind, "订单种类不能为空");
        Objects.requireNonNull(state, "订单状态不能为空");
    }

    /**
     * 是否终态订单（委托 {@link OrderState#isTerminal()}）。
     *
     * @return 终态返回 true
     */
    public boolean isTerminal() {
        return state.isTerminal();
    }

    /**
     * 是否自定义称号订单。
     *
     * @return kind=CUSTOM 返回 true
     */
    public boolean isCustom() {
        return kind == OrderKind.CUSTOM;
    }
}
