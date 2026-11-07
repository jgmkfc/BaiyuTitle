package net.baiyu.title.service;

import net.baiyu.title.domain.condition.ConditionType;
import net.baiyu.title.domain.condition.PurchaseCondition;
import net.baiyu.title.domain.title.CombineLogic;
import net.baiyu.title.domain.title.Title;
import net.baiyu.title.gui.admin.BuyType;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 购买类型集应用裁决纯函数（包内共享）：{@code BuyTypeSelectMenu} 应用 →
 * {@link AdminTitleService#setBuyTypes} 的校验与清价联动统一经此推演，
 * 零 Bukkit、零 IO，可直接单测。
 *
 * <p>规则：免费/羁绊/组称号互斥；羁绊/组/自定义
 * 的类型由种类决定不可切换（与现状一致时空操作）；NORMAL 应用时被取消
 * 类型的价格条件整条清除（清价联动），新启用类型不落条件行——录价即启用
 * （阶段 2约定：避免「启用类型需先有价格条件」的死锁）。</p>
 */
final class BuyTypeEdits {

    private BuyTypeEdits() {
    }

    /**
     * 应用计划（密封三分支）：拒绝（含原因）/ 空操作 / 落库替换条件表。
     */
    sealed interface Plan {

        /**
         * 拒绝：选择集非法或种类锁定。
         *
         * @param reason 拒绝原因（入日志用，玩家提示走菜单 lang 键）
         */
        record Reject(String reason) implements Plan {
        }

        /** 空操作：与现状一致（种类锁定称号的原样应用）。 */
        record NoOp() implements Plan {
        }

        /**
         * 落库：以给定组合逻辑与条件表整体替换。
         *
         * @param logic      组合逻辑（免费强制 AND，其余保留现值）
         * @param conditions 应用后的条件表（清价联动已完成）
         */
        record Apply(CombineLogic logic, List<PurchaseCondition> conditions) implements Plan {
        }
    }

    /**
     * 推演购买类型集应用计划（纯函数）。
     *
     * @param current 称号现状（缓存快照）
     * @param types   目标启用类型集
     * @return 应用计划
     */
    static Plan plan(Title current, Set<BuyType> types) {
        Objects.requireNonNull(current, "称号不能为空");
        Objects.requireNonNull(types, "购买类型集不能为空");
        if (types.isEmpty()) {
            return new Plan.Reject("至少需要启用一种购买类型: id=" + current.id());
        }
        final EnumSet<BuyType> selected = EnumSet.copyOf(types);
        if (selected.stream().anyMatch(BuyType::exclusive) && selected.size() > 1) {
            return new Plan.Reject("免费/羁绊/组称号为互斥类型，不能与其他类型组合: " + selected);
        }
        return switch (current.category()) {
            case BOND, GROUP, CUSTOM -> selected.equals(BuyType.derive(current))
                    ? new Plan.NoOp()
                    : new Plan.Reject("该称号种类的购买类型由种类决定，不可更改: id=" + current.id());
            case NORMAL -> {
                if (selected.contains(BuyType.BOND) || selected.contains(BuyType.GROUP)) {
                    yield new Plan.Reject("普通称号不能切换为羁绊/组称号类型: id=" + current.id());
                }
                if (selected.contains(BuyType.FREE)) {
                    yield new Plan.Apply(CombineLogic.AND, List.of());
                }
                final EnumSet<ConditionType> keep = EnumSet.noneOf(ConditionType.class);
                selected.forEach(type -> keep.add(type.conditionType()));
                yield new Plan.Apply(current.combineLogic(),
                        ConditionEdits.retain(current.conditions(), keep));
            }
        };
    }
}
