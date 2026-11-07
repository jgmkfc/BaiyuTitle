package net.baiyu.title.service;

import net.baiyu.title.domain.condition.ConditionType;
import net.baiyu.title.domain.condition.PurchaseCondition;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 购买条件列表编辑纯函数（包内共享）：{@link AdminTitleService} 的价格
 * 增改/清除/清价联动统一经此变换，保持「每类型至多一条」不变量与声明顺序。
 *
 * <p>全部方法返回新的不可变列表，不修改入参。</p>
 */
final class ConditionEdits {

    private ConditionEdits() {
    }

    /**
     * 增改条件：移除同类型旧条目后追加新条目（保留其余条件的相对顺序，
     * 新/改条目排尾）。
     *
     * @param current     现有条件列表
     * @param replacement 新条件
     * @return 变换后的不可变列表
     */
    static List<PurchaseCondition> upsert(List<PurchaseCondition> current, PurchaseCondition replacement) {
        Objects.requireNonNull(replacement, "新条件不能为空");
        List<PurchaseCondition> result = new ArrayList<>(without(current, replacement.type()));
        result.add(replacement);
        return List.copyOf(result);
    }

    /**
     * 移除指定类型的条件（不存在时原样返回拷贝）。
     *
     * @param current 现有条件列表
     * @param type    要移除的条件类型
     * @return 变换后的不可变列表
     */
    static List<PurchaseCondition> without(List<PurchaseCondition> current, ConditionType type) {
        Objects.requireNonNull(type, "条件类型不能为空");
        return List.copyOf(current.stream()
                .filter(condition -> condition.type() != type)
                .toList());
    }

    /**
     * 只保留指定类型集内的条件（购买类型切换的清价联动——被取消类型的
     * 价格条件整条清掉，业务设计 §1.3.2.3.5.4 功能键 A 备注）。
     *
     * @param current 现有条件列表
     * @param keep    要保留的条件类型集
     * @return 变换后的不可变列表
     */
    static List<PurchaseCondition> retain(List<PurchaseCondition> current, Set<ConditionType> keep) {
        Objects.requireNonNull(keep, "保留类型集不能为空");
        return List.copyOf(current.stream()
                .filter(condition -> keep.contains(condition.type()))
                .toList());
    }
}
