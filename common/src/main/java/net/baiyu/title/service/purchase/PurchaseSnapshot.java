package net.baiyu.title.service.purchase;

import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 玩家购买上下文快照（判定引擎与购买服务的共同输入）。
 * <p>由 paper 侧在主线程（权限/背包物品/Vault 余额）与缓存（称号币/已拥有）
 * 预先收集为纯数据，{@link EligibilityEngine} 与
 * {@link net.baiyu.title.service.PurchaseService} 消费时零 Bukkit 零数据库 IO。
 * <p>快照仅供展示预判与分支裁决；权威裁决在写线程事务内以条件 UPDATE 完成
 * （称号币扣款、售卖占位），Vault/物品在 PAYING 阶段主线程重验后才实际扣付。
 *
 * @param today            服务器本地当天日期（获取窗口判定用）
 * @param economyAvailable Vault 经济是否可用
 * @param vaultBalance     Vault 余额（经济不可用时传 0）
 * @param coinBalance      称号币余额
 * @param heldPermissions  玩家持有的权限节点集合（仅需包含被判定称号涉及的节点）
 * @param heldItemCounts   物品持有数（键 = 条件物品的 Base64 串，值 = 背包持有总数）
 * @param alreadyOwned     是否已 ACTIVE 持有被判定称号
 */
public record PurchaseSnapshot(
        LocalDate today,
        boolean economyAvailable,
        BigDecimal vaultBalance,
        long coinBalance,
        Set<String> heldPermissions,
        Map<String, Integer> heldItemCounts,
        boolean alreadyOwned) {

    /**
     * 紧凑构造器：判空、防御性拷贝（null 集合视为空集，null 余额视为 0）。
     */
    public PurchaseSnapshot {
        Objects.requireNonNull(today, "当天日期不能为空");
        vaultBalance = vaultBalance == null ? BigDecimal.ZERO : vaultBalance;
        heldPermissions = heldPermissions == null ? Set.of() : Set.copyOf(heldPermissions);
        heldItemCounts = heldItemCounts == null ? Map.of() : Map.copyOf(new HashMap<>(heldItemCounts));
    }

    /**
     * 判断是否持有给定权限节点。
     *
     * @param node 权限节点（可空；空节点恒 false）
     * @return 持有返回 true
     */
    public boolean hasPermission(@Nullable String node) {
        return node != null && heldPermissions.contains(node);
    }

    /**
     * 查询给定物品的持有数量。
     *
     * @param itemBase64 条件物品的 Base64 序列化串
     * @return 持有数量；未持有为 0
     */
    public int itemCount(String itemBase64) {
        return heldItemCounts.getOrDefault(itemBase64, 0);
    }
}
