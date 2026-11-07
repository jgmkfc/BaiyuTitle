package net.baiyu.title.gui.core;

import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 称号选择暂存状态（挂在 {@link GuiSession} 上）：
 * 目标栏位、普通模式单选暂选 / 暂定卸下、随机模式多选池与模式开关。
 *
 * <p>应用（选择菜单 B 键）时整体交给 WardrobeViewService 落库，
 * ESC 退出则由菜单 onClosed 丢弃——本类只暂存、不做业务判断。
 * 线程纪律：仅主线程读写。</p>
 */
public final class SlotSelectState {

    /** 目标装扮栏位号（1 起）。 */
    private final int slotNo;

    /** 普通模式暂选的称号 ID；未选时为 null。 */
    private @Nullable Integer pendingTitleId;

    /** 是否暂定卸下（右键任意称号触发；与暂选互斥）。 */
    private boolean pendingUnequip;

    /** 随机模式的候选池（称号 ID，保持点击顺序）。 */
    private final Set<Integer> randomPool = new LinkedHashSet<>();

    /** 是否处于随机佩戴模式（选择菜单首格 CHEST 切换）。 */
    private boolean randomMode;

    /**
     * 创建暂存状态（由 {@link GuiSession#beginSlotSelect(int)} 调用）。
     *
     * @param slotNo 目标栏位号（1 起）
     */
    SlotSelectState(int slotNo) {
        if (slotNo < 1) {
            throw new IllegalArgumentException("栏位号必须≥1，实际 " + slotNo);
        }
        this.slotNo = slotNo;
    }

    /** @return 目标栏位号（1 起） */
    public int slotNo() {
        return slotNo;
    }

    /** @return 普通模式暂选的称号 ID；未选时为 null */
    public @Nullable Integer pendingTitleId() {
        return pendingTitleId;
    }

    /**
     * 普通模式暂选某称号（清除暂定卸下标记）。
     *
     * @param titleId 称号 ID
     */
    public void selectFixed(int titleId) {
        this.pendingTitleId = titleId;
        this.pendingUnequip = false;
    }

    /** @return 是否暂定卸下 */
    public boolean isPendingUnequip() {
        return pendingUnequip;
    }

    /** 暂定卸下本栏位称号（清除暂选）。 */
    public void markUnequip() {
        this.pendingUnequip = true;
        this.pendingTitleId = null;
    }

    /** @return 随机模式候选池（可变视图，仅主线程操作） */
    public Set<Integer> randomPool() {
        return randomPool;
    }

    /**
     * 随机池增删切换。
     *
     * @param titleId 称号 ID
     * @return 切换后该称号是否在池中
     */
    public boolean toggleRandom(int titleId) {
        if (!randomPool.add(titleId)) {
            randomPool.remove(titleId);
            return false;
        }
        return true;
    }

    /** @return 是否随机佩戴模式 */
    public boolean isRandomMode() {
        return randomMode;
    }

    /**
     * 切换随机佩戴模式。
     *
     * @param randomMode 新值
     */
    public void setRandomMode(boolean randomMode) {
        this.randomMode = randomMode;
    }
}
