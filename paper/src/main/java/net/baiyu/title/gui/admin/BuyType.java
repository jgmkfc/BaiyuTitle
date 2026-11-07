package net.baiyu.title.gui.admin;

import net.baiyu.title.domain.condition.ConditionType;
import net.baiyu.title.domain.condition.PurchaseCondition;
import net.baiyu.title.domain.title.Title;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * 购买类型视图枚举（免费/金币/称号币/物品/权限/羁绊/组称号）。
 *
 * <p>纯视图概念——数据库无「购买类型」列：免费 = NORMAL 且条件空集（结构约定）、
 * 金币/称号币/物品/权限一一对应 {@link ConditionType} 条件行、羁绊/组称号由
 * {@code TitleCategory} 决定。{@link #derive} 是唯一的正向推导入口，
 * 管理端的类型选择菜单据此播种当前启用集。</p>
 *
 * <p>互斥裁决（§1.3.2.3.5.4 表格备注）：免费/羁绊/组称号三者启用时
 * 「把其他购买类型都暂设置为禁用」——见 {@link #exclusive()}。</p>
 */
public enum BuyType {

    /** 免费（NORMAL 条件空集；互斥）。 */
    FREE(true, null),

    /** 金币（Vault 经济条件）。 */
    VAULT(false, ConditionType.VAULT),

    /** 称号币条件。 */
    COIN(false, ConditionType.COIN),

    /** 物品条件。 */
    ITEM(false, ConditionType.ITEM),

    /** 权限条件。 */
    PERMISSION(false, ConditionType.PERMISSION),

    /** 羁绊（BOND 种类；互斥）。 */
    BOND(true, null),

    /** 组称号（GROUP 种类；互斥）。 */
    GROUP(true, null);

    /** 是否互斥类型（启用时清空其他全部类型）。 */
    private final boolean exclusive;

    /** 对应的购买条件类型；互斥类型无对应条件行时为 null。 */
    private final @Nullable ConditionType conditionType;

    BuyType(boolean exclusive, @Nullable ConditionType conditionType) {
        this.exclusive = exclusive;
        this.conditionType = conditionType;
    }

    /** @return 是否互斥类型（免费/羁绊/组称号） */
    public boolean exclusive() {
        return exclusive;
    }

    /** @return 对应的条件类型；免费/羁绊/组称号返回 null */
    public @Nullable ConditionType conditionType() {
        return conditionType;
    }

    /**
     * 从条件类型反查购买类型（四种价格条件一一对应）。
     *
     * @param type 条件类型
     * @return 对应购买类型
     */
    public static BuyType of(ConditionType type) {
        Objects.requireNonNull(type, "条件类型不能为空");
        return switch (type) {
            case VAULT -> VAULT;
            case COIN -> COIN;
            case ITEM -> ITEM;
            case PERMISSION -> PERMISSION;
        };
    }

    /**
     * 从称号现状推导当前启用的购买类型集（唯一正向推导入口）。
     * <p>CUSTOM 条件恒空集，按免费展示（自定义称号付款走 config 独立流程）。</p>
     *
     * @param title 称号
     * @return 启用类型集（EnumSet，声明序稳定；至少一个元素）
     */
    public static Set<BuyType> derive(Title title) {
        Objects.requireNonNull(title, "称号不能为空");
        return switch (title.category()) {
            case BOND -> EnumSet.of(BOND);
            case GROUP -> EnumSet.of(GROUP);
            case CUSTOM -> EnumSet.of(FREE);
            case NORMAL -> {
                if (title.conditions().isEmpty()) {
                    yield EnumSet.of(FREE);
                }
                EnumSet<BuyType> types = EnumSet.noneOf(BuyType.class);
                for (PurchaseCondition condition : title.conditions()) {
                    types.add(of(condition.type()));
                }
                yield types;
            }
        };
    }
}
