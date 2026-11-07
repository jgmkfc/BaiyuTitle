package net.baiyu.title.gui.admin;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * 管理端编辑暂挂状态：购买类型选择菜单的
 * 暂挂启用集——业务设计 §1.3.2.3.5.4「在没点击应用按钮前，更改为暂挂且不生效」。
 *
 * <p>互斥规则内聚在 {@link #toggle}：启用互斥类型（免费/羁绊/组称号）时清空
 * 其他全部类型；启用普通类型时清掉互斥类型。允许全部关闭（应用时由菜单/服务
 * 拦截空集）。挂在 {@link net.baiyu.title.gui.core.GuiSession}，应用/取消/ESC
 * 时清除；线程纪律与会话一致（仅主线程）。</p>
 */
public final class AdminEditState {

    /** 编辑目标称号 id。 */
    private final int titleId;

    /** 暂挂启用类型集。 */
    private final EnumSet<BuyType> pending;

    /**
     * 开始一段购买类型编辑。
     *
     * @param titleId 目标称号 id
     * @param initial 初始启用集（通常 {@link BuyType#derive} 推导的现状）
     */
    public AdminEditState(int titleId, Set<BuyType> initial) {
        if (titleId < 1) {
            throw new IllegalArgumentException("称号 id 必须≥1，实际 " + titleId);
        }
        Objects.requireNonNull(initial, "初始启用集不能为空");
        this.titleId = titleId;
        this.pending = initial.isEmpty() ? EnumSet.noneOf(BuyType.class) : EnumSet.copyOf(initial);
    }

    /** @return 编辑目标称号 id */
    public int titleId() {
        return titleId;
    }

    /** @return 暂挂启用类型集（只读视图） */
    public Set<BuyType> pending() {
        return Collections.unmodifiableSet(pending);
    }

    /**
     * @param type 购买类型
     * @return 该类型当前是否暂挂启用
     */
    public boolean isEnabled(BuyType type) {
        return pending.contains(type);
    }

    /**
     * 切换某类型的暂挂启用状态（互斥规则见类注释）。
     *
     * @param type 购买类型
     */
    public void toggle(BuyType type) {
        Objects.requireNonNull(type, "购买类型不能为空");
        if (pending.remove(type)) {
            return;
        }
        if (type.exclusive()) {
            pending.clear();
        } else {
            pending.removeIf(BuyType::exclusive);
        }
        pending.add(type);
    }
}
