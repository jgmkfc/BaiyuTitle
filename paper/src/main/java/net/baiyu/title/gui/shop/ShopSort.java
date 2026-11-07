package net.baiyu.title.gui.shop;

import net.baiyu.title.domain.condition.CoinCondition;
import net.baiyu.title.domain.condition.PurchaseCondition;
import net.baiyu.title.domain.condition.VaultCondition;
import net.baiyu.title.domain.title.Title;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 商店排序状态（单选 + 方向翻转，纯值对象）。
 *
 * <p>语义裁决：</p>
 * <ul>
 *   <li>时间（默认）：按创建时间，默认新→老；翻转为老→新。</li>
 *   <li>价格：免费恒最前、无价格的购买方式（物品/权限/羁绊/组）恒最后
 *       ——原文将其表述为价格排序的固定性质，两端组不随方向翻转，
 *       组内按默认时间排序；有价条目的标尺 = Vault 金额，无 Vault 时取称号币数量
 *       （两币种混排的含糊处，取声明显著者，文档未规定换算率）。</li>
 *   <li>购买热度：按已售份数（sold_count）；默认取菜单表格的高→低
 *       （表格与正文首击方向互斥，取表格）。</li>
 *   <li>稀有度：按 config rarities 列表位置，低→高默认；无稀有度
 *       （含名称不在列表中者）恒最后、不随方向翻转，组内按默认时间排序。</li>
 *   <li>全部键的并列项以默认时间排序（新→老）、再按称号 id 兜底，保证确定性。</li>
 * </ul>
 *
 * @param key       排序键
 * @param ascending 是否升序（时间升序 = 老→新；价格升序 = 低→高；
 *                  热度升序 = 低→高；稀有度升序 = 低→高）
 */
public record ShopSort(Key key, boolean ascending) {

    /** 默认排序：时间新→老。 */
    public static final ShopSort DEFAULT = new ShopSort(Key.TIME, false);

    /** 紧凑构造：排序键必填。 */
    public ShopSort {
        Objects.requireNonNull(key, "排序键不能为空");
    }

    /** 排序键。 */
    public enum Key {
        /** 时间（创建时间，默认键）。 */
        TIME,
        /** 价格。 */
        PRICE,
        /** 购买热度（已售份数）。 */
        POPULARITY,
        /** 稀有度。 */
        RARITY
    }

    /**
     * 方向翻转（排序菜单点击循环用）。
     *
     * @return 同键反向的新实例
     */
    public ShopSort flipped() {
        return new ShopSort(key, !ascending);
    }

    /**
     * 构建条目比较器（纯函数）。
     *
     * @param rarityOrder config rarities 列表（低→高；稀有度序号解析依据）
     * @return 商店条目比较器
     */
    public Comparator<ShopEntryView> comparator(List<String> rarityOrder) {
        Objects.requireNonNull(rarityOrder, "稀有度顺序表不能为空");
        return switch (key) {
            case TIME -> directed(Comparator.comparingLong(entry -> entry.title().createdAt()));
            case PRICE -> Comparator
                    .comparingInt((ShopEntryView entry) -> priceRank(entry.title()))
                    .thenComparing(directed(Comparator.comparing(
                            entry -> priceOf(entry.title()),
                            Comparator.nullsLast(Comparator.naturalOrder()))));
            case POPULARITY -> directed(
                    Comparator.comparingInt((ShopEntryView entry) -> entry.title().soldCount()));
            case RARITY -> Comparator
                    .comparingInt((ShopEntryView entry) -> rarityIndex(entry.title(), rarityOrder) < 0 ? 1 : 0)
                    .thenComparing(directed(Comparator.comparingInt(
                            entry -> rarityIndex(entry.title(), rarityOrder))));
        };
    }

    /** 应用方向：升序原样、降序反转，并列项统一按默认时间 + id 兜底。 */
    private Comparator<ShopEntryView> directed(Comparator<ShopEntryView> base) {
        Comparator<ShopEntryView> oriented = ascending ? base : base.reversed();
        return oriented.thenComparing(newestFirst());
    }

    /** 默认时间排序（新→老），id 降序兜底（同刻创建时新 id 在前）。 */
    private static Comparator<ShopEntryView> newestFirst() {
        return Comparator
                .comparingLong((ShopEntryView entry) -> entry.title().createdAt()).reversed()
                .thenComparing(Comparator.comparingInt(
                        (ShopEntryView entry) -> entry.title().id()).reversed());
    }

    /** 价格分组：免费 0、有价（Vault/称号币）1、无价购买方式 2。 */
    private static int priceRank(Title title) {
        if (title.isFree()) {
            return 0;
        }
        return priceOf(title) != null ? 1 : 2;
    }

    /** 价格标尺：Vault 金额优先，无 Vault 取称号币数量；两者皆无为 null。 */
    private static @Nullable BigDecimal priceOf(Title title) {
        BigDecimal coin = null;
        for (PurchaseCondition condition : title.conditions()) {
            if (condition instanceof VaultCondition vault) {
                return vault.amount();
            }
            if (condition instanceof CoinCondition coins) {
                coin = BigDecimal.valueOf(coins.amount());
            }
        }
        return coin;
    }

    /** 稀有度序号：按列表位置；null / 不在列表中返回 -1（视为无稀有度）。 */
    private static int rarityIndex(Title title, List<String> rarityOrder) {
        String rarity = title.rarity();
        return rarity == null ? -1 : rarityOrder.indexOf(rarity);
    }
}
