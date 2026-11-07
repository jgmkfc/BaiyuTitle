package net.baiyu.title.gui.trade;

import net.baiyu.title.domain.trade.Trade;
import net.baiyu.title.service.trade.OfferItem;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 报价草稿：
 * 交易目标 + 两侧标的暂选（每侧 ≤ {@value #MAX_PER_SIDE}）+ 流程阶段。
 *
 * <p>只暂存、不做业务判断——创建报价时的权威校验（归属 / 可交易 /
 * 未到期 / 不与自己交易等）全部由 common 层 OfferValidator 在写事务内
 * 执行；本类仅承担「每侧上限」这一 UI 级即时反馈（服务端仍会复核）。
 * 线程纪律：仅主线程读写。</p>
 */
public final class TradeDraft {

    /** 每侧标的数量上限（每个玩家最多选择 12 个称号）。 */
    public static final int MAX_PER_SIDE = Trade.MAX_ITEMS_PER_SIDE;

    /** 草稿视角的交易方（SELF = 发起方自己，OTHER = 交易目标）。 */
    public enum Side {

        /** 己方（发起方自己拿出的称号）。 */
        SELF,

        /** 对方（希望交易目标拿出的称号）。 */
        OTHER
    }

    /** 草稿流程阶段（①选己方 → ②选对方 → ③确认报价）。 */
    public enum Phase {

        /** 选择己方称号。 */
        SELECT_SELF,

        /** 选择对方称号。 */
        SELECT_OTHER,

        /** 报价详情确认。 */
        CONFIRM
    }

    /** 标的增删切换结果（菜单据此反馈）。 */
    public enum ToggleResult {

        /** 已选中。 */
        SELECTED,

        /** 已取消选中。 */
        DESELECTED,

        /** 该侧已达上限，未选中。 */
        LIMIT_REACHED
    }

    /** 交易目标 UUID。 */
    private final UUID targetUuid;

    /** 交易目标名称（选择玩家时的快照）。 */
    private final String targetName;

    /** 己方暂选（所有权 id → 标的引用；保持点击顺序）。 */
    private final Map<Long, OfferItem> selfItems = new LinkedHashMap<>();

    /** 对方暂选（所有权 id → 标的引用；保持点击顺序）。 */
    private final Map<Long, OfferItem> otherItems = new LinkedHashMap<>();

    /** 当前流程阶段。 */
    private Phase phase = Phase.SELECT_SELF;

    /**
     * 创建报价草稿（选择交易玩家后由列表菜单调用）。
     *
     * @param targetUuid 交易目标 UUID
     * @param targetName 交易目标名称
     */
    public TradeDraft(UUID targetUuid, String targetName) {
        this.targetUuid = Objects.requireNonNull(targetUuid, "交易目标 UUID 不能为空");
        this.targetName = Objects.requireNonNull(targetName, "交易目标名称不能为空");
    }

    /** @return 交易目标 UUID */
    public UUID targetUuid() {
        return targetUuid;
    }

    /** @return 交易目标名称 */
    public String targetName() {
        return targetName;
    }

    /** @return 当前流程阶段 */
    public Phase phase() {
        return phase;
    }

    /**
     * 更新流程阶段（进入对应菜单时调用）。
     *
     * @param phase 新阶段
     */
    public void setPhase(Phase phase) {
        this.phase = Objects.requireNonNull(phase, "流程阶段不能为空");
    }

    /**
     * 标的增删切换：已选则取消、未选且未达上限则选中。
     *
     * @param side 草稿交易方
     * @param item 标的引用
     * @return 切换结果
     */
    public ToggleResult toggle(Side side, OfferItem item) {
        Objects.requireNonNull(item, "标的引用不能为空");
        final Map<Long, OfferItem> items = itemsOf(side);
        if (items.remove(item.ownershipId()) != null) {
            return ToggleResult.DESELECTED;
        }
        if (items.size() >= MAX_PER_SIDE) {
            return ToggleResult.LIMIT_REACHED;
        }
        items.put(item.ownershipId(), item);
        return ToggleResult.SELECTED;
    }

    /**
     * 移除一个标的（报价详情草稿态点击移除）。
     *
     * @param side        草稿交易方
     * @param ownershipId 所有权实例 id
     */
    public void remove(Side side, long ownershipId) {
        itemsOf(side).remove(ownershipId);
    }

    /**
     * 判断某所有权实例是否已被选中（选择菜单附魔标记）。
     *
     * @param side        草稿交易方
     * @param ownershipId 所有权实例 id
     * @return 已选中返回 true
     */
    public boolean isSelected(Side side, long ownershipId) {
        return itemsOf(side).containsKey(ownershipId);
    }

    /**
     * 取某侧已选标的列表（保持点击顺序的不可变拷贝）。
     *
     * @param side 草稿交易方
     * @return 标的引用列表
     */
    public List<OfferItem> items(Side side) {
        return List.copyOf(itemsOf(side).values());
    }

    /**
     * 某侧已选数量。
     *
     * @param side 草稿交易方
     * @return 已选数量
     */
    public int count(Side side) {
        return itemsOf(side).size();
    }

    /** @return 双侧是否同时为空（不可双方都为空） */
    public boolean isEmpty() {
        return selfItems.isEmpty() && otherItems.isEmpty();
    }

    /** 按草稿交易方取内部暂选表。 */
    private Map<Long, OfferItem> itemsOf(Side side) {
        Objects.requireNonNull(side, "草稿交易方不能为空");
        return side == Side.SELF ? selfItems : otherItems;
    }
}
