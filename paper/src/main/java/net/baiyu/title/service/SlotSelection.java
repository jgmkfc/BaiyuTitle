package net.baiyu.title.service;

import java.util.Set;

/**
 * 栏位选择意图：称号选择菜单"应用并返回"时提交的
 * 不可变载体，三种形态穷尽——固定单选 / 随机候选池 / 卸下回默认。
 *
 * <p>由 {@link WardrobeViewService#applySlotSelection} 消费并映射到
 * {@code WardrobeService} 对应写入口；菜单侧只负责把 SlotSelectState
 * 暂存折叠成本类型，不做业务判断。</p>
 */
public sealed interface SlotSelection {

    /**
     * 固定佩戴某称号。
     *
     * @param titleId 称号 id
     */
    record Fixed(int titleId) implements SlotSelection {
    }

    /**
     * 随机佩戴：全量替换候选池（空池 = 随机模式但无候选，聊天降级默认称号）。
     *
     * @param pool 候选池称号 id 集合
     */
    record Random(Set<Integer> pool) implements SlotSelection {

        /** 紧凑构造：候选池冻结为不可变（null 视为空池）。 */
        public Random {
            pool = pool == null ? Set.of() : Set.copyOf(pool);
        }
    }

    /** 卸下本栏位称号（回默认称号展示）。 */
    record Unequip() implements SlotSelection {
    }
}
