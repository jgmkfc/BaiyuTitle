package net.baiyu.title.domain.wardrobe;

import java.util.Objects;

/**
 * 装扮栏位配置（对应 {@code player_slot} 表，玩家维度由仓储层键定位）。
 * <p>栏位上限来自 config groups 运行时解析不入库；上限缩水时数据保留、渲染截断。
 *
 * @param slotIndex 栏位下标（从 0 起）
 * @param enabled   是否启用该栏位
 * @param mode      栏位模式（固定 / 随机）
 * @param titleId   固定展示的称号 id（可空；空 = 默认称号；RANDOM 模式下不使用）
 */
public record SlotConfig(int slotIndex, boolean enabled, SlotMode mode, Integer titleId) {

    /**
     * 紧凑构造器：栏位下标非负、模式非空。
     */
    public SlotConfig {
        if (slotIndex < 0) {
            throw new IllegalArgumentException("栏位下标不能为负: " + slotIndex);
        }
        Objects.requireNonNull(mode, "栏位模式不能为空");
    }

    /**
     * 是否展示默认称号（FIXED 模式且未指定称号）。
     *
     * @return 展示默认称号返回 true
     */
    public boolean isDefaultTitle() {
        return mode == SlotMode.FIXED && titleId == null;
    }

    /**
     * 是否随机模式。
     *
     * @return 随机模式返回 true
     */
    public boolean isRandom() {
        return mode == SlotMode.RANDOM;
    }
}
