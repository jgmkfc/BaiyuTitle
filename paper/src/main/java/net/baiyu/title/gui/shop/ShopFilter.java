package net.baiyu.title.gui.shop;

import net.baiyu.title.domain.condition.ConditionType;
import net.baiyu.title.domain.title.LimitMode;
import net.baiyu.title.domain.title.Title;
import net.baiyu.title.domain.title.TitleCategory;
import net.baiyu.title.service.purchase.Eligibility;
import org.jetbrains.annotations.Nullable;

import java.time.LocalDate;
import java.util.Objects;

/**
 * 商店筛选状态（四维单选，纯值对象）。
 *
 * <p>获取方式语义（原文）：「只要有包含这其中的内容就可以显示，不管是或还是与逻辑」
 * ——按条件类型<b>包含</b>匹配，与组合逻辑无关；组称号不在获取方式选项中，
 * 仅「全部」可见。购买状态的「能/不能购买」已包含活动限定条件
 * （直接以 {@link Eligibility} 三分支裁决）。</p>
 *
 * @param acquire  获取方式（全部/免费/金币/称号币/物品/权限/羁绊）
 * @param own      购买状态（全部/已拥有/未拥有/能购买/不能购买）
 * @param rarity   稀有度名称（null = 全部；选项由 config rarities 动态生成）
 * @param activity 活动状态（全部/限时活动中/已结束/总数未满/已售罄）
 */
public record ShopFilter(
        AcquireType acquire,
        OwnState own,
        @Nullable String rarity,
        ActivityState activity) {

    /** 默认筛选（四维全部）。 */
    public static final ShopFilter DEFAULT =
            new ShopFilter(AcquireType.ALL, OwnState.ALL, null, ActivityState.ALL);

    /** 紧凑构造：枚举维度必填。 */
    public ShopFilter {
        Objects.requireNonNull(acquire, "获取方式不能为空");
        Objects.requireNonNull(own, "购买状态不能为空");
        Objects.requireNonNull(activity, "活动状态不能为空");
    }

    /** 获取方式选项。 */
    public enum AcquireType {
        /** 全部。 */
        ALL,
        /** 免费（条件空集）。 */
        FREE,
        /** 含金币（Vault）条件。 */
        VAULT,
        /** 含称号币条件。 */
        COIN,
        /** 含物品条件。 */
        ITEM,
        /** 含权限条件。 */
        PERMISSION,
        /** 羁绊称号。 */
        BOND
    }

    /** 购买状态选项。 */
    public enum OwnState {
        /** 全部。 */
        ALL,
        /** 已拥有（ACTIVE 持有中）。 */
        OWNED,
        /** 未拥有。 */
        NOT_OWNED,
        /** 能购买（含活动限定）。 */
        CAN_BUY,
        /** 不能购买（含活动限定）。 */
        CANNOT_BUY
    }

    /** 活动状态选项。 */
    public enum ActivityState {
        /** 全部。 */
        ALL,
        /** 限时活动中（获取窗口期内）。 */
        WINDOW_ACTIVE,
        /** 已结束（已过获取窗口期）。 */
        WINDOW_ENDED,
        /** 总数未满。 */
        TOTAL_OPEN,
        /** 已售罄（总数限定达上限）。 */
        SOLD_OUT
    }

    /**
     * 判定条目是否通过本筛选（纯函数）。
     *
     * @param entry 商店条目视图
     * @return 通过返回 true
     */
    public boolean matches(ShopEntryView entry) {
        Objects.requireNonNull(entry, "商店条目不能为空");
        return matchesAcquire(entry.title())
                && matchesOwn(entry.eligibility())
                && matchesRarity(entry.title())
                && matchesActivity(entry.title(), entry.snapshot().today());
    }

    /** 获取方式匹配：类型包含即显示（与/或逻辑无关）。 */
    private boolean matchesAcquire(Title title) {
        return switch (acquire) {
            case ALL -> true;
            case FREE -> title.isFree();
            case BOND -> title.category() == TitleCategory.BOND;
            case VAULT -> hasCondition(title, ConditionType.VAULT);
            case COIN -> hasCondition(title, ConditionType.COIN);
            case ITEM -> hasCondition(title, ConditionType.ITEM);
            case PERMISSION -> hasCondition(title, ConditionType.PERMISSION);
        };
    }

    /** 购买状态匹配：以资格三分支裁决。 */
    private boolean matchesOwn(Eligibility eligibility) {
        return switch (own) {
            case ALL -> true;
            case OWNED -> eligibility instanceof Eligibility.Owned;
            case NOT_OWNED -> !(eligibility instanceof Eligibility.Owned);
            case CAN_BUY -> eligibility instanceof Eligibility.CanBuy;
            case CANNOT_BUY -> eligibility instanceof Eligibility.CannotBuy;
        };
    }

    /** 稀有度匹配：名称精确相等（null = 全部）。 */
    private boolean matchesRarity(Title title) {
        return rarity == null || rarity.equals(title.rarity());
    }

    /** 活动状态匹配（窗口闭区间语义复用领域方法）。 */
    private boolean matchesActivity(Title title, LocalDate today) {
        return switch (activity) {
            case ALL -> true;
            case WINDOW_ACTIVE -> title.limitMode() == LimitMode.WINDOW && title.isWithinWindow(today);
            case WINDOW_ENDED -> title.limitMode() == LimitMode.WINDOW
                    && title.windowEnd() != null && today.isAfter(title.windowEnd());
            case TOTAL_OPEN -> title.limitMode() == LimitMode.TOTAL && !title.isSoldOut();
            case SOLD_OUT -> title.limitMode() == LimitMode.TOTAL && title.isSoldOut();
        };
    }

    /** 称号是否含给定类型的购买条件。 */
    private static boolean hasCondition(Title title, ConditionType type) {
        return title.conditions().stream().anyMatch(condition -> condition.type() == type);
    }
}
