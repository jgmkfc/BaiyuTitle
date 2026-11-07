package net.baiyu.title.gui.shop;

import net.baiyu.title.domain.condition.ConditionType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 自定义称号草稿单测：付款单选覆盖语义与
 * 命名回执的「暂存-消费」一次性语义（铁砧 → 付款菜单重建 → 确认菜单的
 * 自动前送依赖消费后即清空，防止 back 重建时重复弹确认）。
 */
class CustomTitleDraftTest {

    /** 被测草稿。 */
    private final CustomTitleDraft draft = new CustomTitleDraft();

    /** 初始无付款选择；单选覆盖：后选顶掉先选。 */
    @Test
    void paymentSingleSelectionOverwrites() {
        assertNull(draft.payment(), "初始应无付款选择");
        draft.setPayment(ConditionType.VAULT);
        assertEquals(ConditionType.VAULT, draft.payment());
        draft.setPayment(ConditionType.ITEM);
        assertEquals(ConditionType.ITEM, draft.payment(), "单选应覆盖旧值");
    }

    /** 命名回执消费后即清空：第二次消费拿不到（back 重建不重复弹确认）。 */
    @Test
    void pendingNameConsumedExactlyOnce() {
        draft.stashPendingName("&b稗玉之家");
        assertEquals("&b稗玉之家", draft.consumePendingName(), "首次消费应取到暂存名");
        assertNull(draft.consumePendingName(), "二次消费应为空");
    }

    /** 未暂存时消费返回空；暂存覆盖旧值。 */
    @Test
    void stashOverwritesAndEmptyConsumeIsNull() {
        assertNull(draft.consumePendingName(), "未暂存时消费应为空");
        draft.stashPendingName("first");
        draft.stashPendingName("second");
        assertEquals("second", draft.consumePendingName(), "暂存应覆盖旧值");
    }
}
