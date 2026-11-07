package net.baiyu.title.gui.shop;

import net.baiyu.title.domain.condition.ConditionType;
import org.jetbrains.annotations.Nullable;

/**
 * 自定义称号创建草稿（表现层设计 §3.4 的 CustomTitleDraft；每玩家挂
 * {@code GuiSession}）：付款方式单选暂存 + 铁砧命名回执暂存。
 *
 * <p>命名回执用「暂存-消费」模式衔接铁砧输入与确认菜单：铁砧完成回调只把
 * 原始名称写入 {@link #stashPendingName}，随后输入会话统一 replace 回付款
 * 菜单，菜单重建时 {@link #consumePendingName} 取走名称并自动前送确认菜单——
 * 这样返回栈在整段输入期间保持不动（§3.5 仲裁）。</p>
 *
 * <p>线程纪律：全部读写只在主线程发生（GUI 事件与输入完成回调均为主线程）。</p>
 */
public final class CustomTitleDraft {

    /** 已选付款方式；未选择时为 null。 */
    private @Nullable ConditionType payment;

    /** 铁砧命名回执（原始输入，未归一化）；无待处理命名时为 null。 */
    private @Nullable String pendingName;

    /** @return 已选付款方式；未选择时为 null */
    public @Nullable ConditionType payment() {
        return payment;
    }

    /**
     * 选定付款方式（H/I/J/K 单选，点击即覆盖）。
     *
     * @param payment 付款方式
     */
    public void setPayment(ConditionType payment) {
        this.payment = payment;
    }

    /**
     * 暂存铁砧命名回执（铁砧完成回调调用；覆盖旧值）。
     *
     * @param rawName 原始名称输入
     */
    public void stashPendingName(String rawName) {
        this.pendingName = rawName;
    }

    /**
     * 消费命名回执：返回暂存名称并清空（付款菜单重建时的自动前送入口）。
     *
     * @return 暂存的原始名称；无待处理命名时为 null
     */
    public @Nullable String consumePendingName() {
        final String name = pendingName;
        this.pendingName = null;
        return name;
    }
}
