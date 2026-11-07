package net.baiyu.title.service.purchase;

import net.baiyu.title.domain.condition.ConditionType;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 购买资格判定结果（{@link EligibilityEngine} 输出的密封类型，调用方 switch 穷举）。
 * <p>三分支对照业务文档 §1.3.2.4.1 的商店 lore 底行：
 * {@link CanBuy} = 「点击购买」、{@link Owned} = 「跳转仓库」、
 * {@link CannotBuy} = 「无法购买」。
 * <p>{@link #conditionStatus()} 按称号条件声明顺序给出逐条满足明细
 * （个人状态区"满足 &amp;a / 不满足 &amp;7"的渲染依据）。
 */
public sealed interface Eligibility {

    /**
     * 逐条件满足明细（键 = 条件类型，按称号条件声明顺序；免费称号为空 Map）。
     *
     * @return 不可变有序 Map
     */
    Map<ConditionType, Boolean> conditionStatus();

    /**
     * 可购买。
     *
     * @param conditionStatus 逐条件满足明细
     * @param freeOfCharge    是否免单（免费称号，或"或"逻辑含权限且玩家持有
     *                        ——设计 01 §六：服务端强制免单分支）
     */
    record CanBuy(Map<ConditionType, Boolean> conditionStatus,
                  boolean freeOfCharge) implements Eligibility {

        /** 紧凑构造器：判空 + 保序防御性拷贝。 */
        public CanBuy {
            conditionStatus = copyOrdered(conditionStatus);
        }
    }

    /**
     * 已拥有（ACTIVE 同款持有中；商店点击跳转仓库，禁止重复购买）。
     *
     * @param conditionStatus 逐条件满足明细（lore 渲染仍可参考）
     */
    record Owned(Map<ConditionType, Boolean> conditionStatus) implements Eligibility {

        /** 紧凑构造器：判空 + 保序防御性拷贝。 */
        public Owned {
            conditionStatus = copyOrdered(conditionStatus);
        }
    }

    /**
     * 不可购买。
     *
     * @param reasons         不可购原因集（非空）
     * @param conditionStatus 逐条件满足明细
     */
    record CannotBuy(Set<BlockReason> reasons,
                     Map<ConditionType, Boolean> conditionStatus) implements Eligibility {

        /** 紧凑构造器：原因集非空校验 + 防御性拷贝。 */
        public CannotBuy {
            Objects.requireNonNull(reasons, "原因集不能为空");
            if (reasons.isEmpty()) {
                throw new IllegalArgumentException("不可购买结果必须至少携带一条原因");
            }
            reasons = Set.copyOf(reasons);
            conditionStatus = copyOrdered(conditionStatus);
        }

        /**
         * 是否包含给定原因。
         *
         * @param reason 原因
         * @return 包含返回 true
         */
        public boolean has(BlockReason reason) {
            return reasons.contains(reason);
        }
    }

    /**
     * 保序不可变拷贝（LinkedHashMap 保持条件声明顺序；null 视为空）。
     *
     * @param source 原始明细
     * @return 不可变有序 Map
     */
    private static Map<ConditionType, Boolean> copyOrdered(Map<ConditionType, Boolean> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
