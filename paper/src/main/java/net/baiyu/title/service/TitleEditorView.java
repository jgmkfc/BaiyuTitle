package net.baiyu.title.service;

import net.baiyu.title.domain.condition.CoinCondition;
import net.baiyu.title.domain.condition.ItemCondition;
import net.baiyu.title.domain.condition.PermissionCondition;
import net.baiyu.title.domain.condition.PurchaseCondition;
import net.baiyu.title.domain.condition.VaultCondition;
import net.baiyu.title.domain.title.TitleCategory;
import net.baiyu.title.domain.title.Title;
import net.baiyu.title.gui.admin.BuyType;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Set;

/**
 * 称号编辑器视图：管理详情菜单 19 项的当前值展示素材。
 *
 * <p>纯派生 record——只包住目录缓存里的 {@link Title} 快照并按条件列表派生
 * 各价格字段与可用性判定，零 Bukkit、零 DB IO，可在异步 loadData 线程构建。
 * 条件物品的本地化名需要 Bukkit 反序列化，由菜单在主线程 populate 阶段解析
 * （{@link ItemStackCodec} + ItemNameRegistry），不进本视图。</p>
 *
 * @param title 称号快照（目录缓存现值）
 */
public record TitleEditorView(Title title) {

    /** 紧凑构造：称号必填。 */
    public TitleEditorView {
        Objects.requireNonNull(title, "称号不能为空");
    }

    /** @return 当前启用的购买类型集（{@link BuyType#derive} 推导） */
    public Set<BuyType> buyTypes() {
        return BuyType.derive(title);
    }

    /** @return 金币价格；无 Vault 条件时为 null */
    public @Nullable BigDecimal vaultPrice() {
        return condition(VaultCondition.class) instanceof VaultCondition vault ? vault.amount() : null;
    }

    /** @return 称号币价格；无称号币条件时为 null */
    public @Nullable Long coinPrice() {
        return condition(CoinCondition.class) instanceof CoinCondition coin ? coin.amount() : null;
    }

    /** @return 物品价格条件（Base64 + 数量）；无物品条件时为 null */
    public @Nullable ItemCondition itemCost() {
        return condition(ItemCondition.class);
    }

    /** @return 权限节点；无权限条件时为 null */
    public @Nullable String permissionNode() {
        return condition(PermissionCondition.class) instanceof PermissionCondition p ? p.node() : null;
    }

    /** @return 组合逻辑是否可编辑（仅 NORMAL 且条件 ≥ 2——业务表格「非多种购买类型时禁用」） */
    public boolean logicEditable() {
        return title.category() == TitleCategory.NORMAL && title.conditions().size() >= 2;
    }

    /**
     * @return 可交易性是否可编辑（仅 NORMAL——BOND/CUSTOM/GROUP 由领域不变量
     *         强制不可交易，业务表格「羁绊称号和自定义称号强制不可交易，此项禁用」）
     */
    public boolean tradableEditable() {
        return title.category() == TitleCategory.NORMAL;
    }

    /** @return 四种价格条件（金币/称号币/物品/权限）是否可编辑（仅 NORMAL 走条件表） */
    public boolean priceEditable() {
        return title.category() == TitleCategory.NORMAL;
    }

    /** @return 羁绊需求是否可编辑（仅 BOND——业务表格「购买类型为羁绊时可用」） */
    public boolean bondEditable() {
        return title.category() == TitleCategory.BOND;
    }

    /** 按实现类查找条件（每类型至多一条——领域不变量 2）。 */
    private <T extends PurchaseCondition> @Nullable T condition(Class<T> type) {
        for (PurchaseCondition condition : title.conditions()) {
            if (type.isInstance(condition)) {
                return type.cast(condition);
            }
        }
        return null;
    }
}
