package net.baiyu.title.gui.trade;

import net.baiyu.title.service.trade.OfferItem;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 报价草稿单测：阶段状态机
 * SELF → OTHER → CONFIRM、两侧独立暂选、每侧 ≤12 上限与增删切换。
 */
class TradeDraftTest {

    /** 被测草稿（目标固定）。 */
    private final TradeDraft draft = new TradeDraft(new UUID(1L, 2L), "Target");

    /** 初始阶段为选己方；setPhase 按流程推进到确认。 */
    @Test
    void phaseAdvancesSelfToOtherToConfirm() {
        assertEquals(TradeDraft.Phase.SELECT_SELF, draft.phase(), "初始阶段应为选己方");
        draft.setPhase(TradeDraft.Phase.SELECT_OTHER);
        assertEquals(TradeDraft.Phase.SELECT_OTHER, draft.phase());
        draft.setPhase(TradeDraft.Phase.CONFIRM);
        assertEquals(TradeDraft.Phase.CONFIRM, draft.phase());
    }

    /** 切换语义：首击选中、再击取消；选中状态可查询。 */
    @Test
    void toggleSelectsAndDeselects() {
        final OfferItem item = new OfferItem(100L, 7);
        assertEquals(TradeDraft.ToggleResult.SELECTED, draft.toggle(TradeDraft.Side.SELF, item));
        assertTrue(draft.isSelected(TradeDraft.Side.SELF, 100L));
        assertEquals(1, draft.count(TradeDraft.Side.SELF));

        assertEquals(TradeDraft.ToggleResult.DESELECTED, draft.toggle(TradeDraft.Side.SELF, item));
        assertFalse(draft.isSelected(TradeDraft.Side.SELF, 100L));
        assertEquals(0, draft.count(TradeDraft.Side.SELF));
    }

    /** 每侧 ≤12：第 13 个报 LIMIT_REACHED 且不入选；另一侧不受影响。 */
    @Test
    void limitTwelvePerSideIndependently() {
        for (int i = 1; i <= TradeDraft.MAX_PER_SIDE; i++) {
            assertEquals(TradeDraft.ToggleResult.SELECTED,
                    draft.toggle(TradeDraft.Side.SELF, new OfferItem(i, i)));
        }
        assertEquals(TradeDraft.ToggleResult.LIMIT_REACHED,
                draft.toggle(TradeDraft.Side.SELF, new OfferItem(99L, 99)));
        assertEquals(TradeDraft.MAX_PER_SIDE, draft.count(TradeDraft.Side.SELF));
        assertFalse(draft.isSelected(TradeDraft.Side.SELF, 99L), "超限项不应入选");

        assertEquals(TradeDraft.ToggleResult.SELECTED,
                draft.toggle(TradeDraft.Side.OTHER, new OfferItem(99L, 99)),
                "另一侧独立计数，不受己方上限影响");
    }

    /** 达上限后取消一个可再选入（上限是活动约束而非一次性闸门）。 */
    @Test
    void deselectFreesCapacity() {
        for (int i = 1; i <= TradeDraft.MAX_PER_SIDE; i++) {
            draft.toggle(TradeDraft.Side.OTHER, new OfferItem(i, i));
        }
        assertEquals(TradeDraft.ToggleResult.DESELECTED,
                draft.toggle(TradeDraft.Side.OTHER, new OfferItem(1L, 1)));
        assertEquals(TradeDraft.ToggleResult.SELECTED,
                draft.toggle(TradeDraft.Side.OTHER, new OfferItem(50L, 50)));
    }

    /** remove 与 items：移除指定所有权；列表保持点击顺序。 */
    @Test
    void removeAndOrderedItems() {
        draft.toggle(TradeDraft.Side.SELF, new OfferItem(3L, 30));
        draft.toggle(TradeDraft.Side.SELF, new OfferItem(1L, 10));
        draft.toggle(TradeDraft.Side.SELF, new OfferItem(2L, 20));
        draft.remove(TradeDraft.Side.SELF, 1L);
        final List<OfferItem> items = draft.items(TradeDraft.Side.SELF);
        assertEquals(List.of(new OfferItem(3L, 30), new OfferItem(2L, 20)), items,
                "移除后剩余项保持点击顺序");
    }

    /** isEmpty：双侧全空为 true；任一侧有标的即 false。 */
    @Test
    void emptyDetection() {
        assertTrue(draft.isEmpty(), "新草稿双侧全空");
        draft.toggle(TradeDraft.Side.OTHER, new OfferItem(5L, 50));
        assertFalse(draft.isEmpty(), "单侧有标的即非空（赠予/索要合法）");
    }
}
