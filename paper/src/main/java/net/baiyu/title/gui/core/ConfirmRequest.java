package net.baiyu.title.gui.core;

import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * 通用确认菜单请求：购买 / 删除 / 发起报价 / 同意报价 /
 * 自定义创建 / 收回称号等全部二次确认复用本载体。
 *
 * <p>确认按钮的一次性防双击 token（AtomicBoolean）由 ConfirmMenu 自身持有，
 * 不在请求里；请求只描述「展示什么、确认 / 取消各做什么」。</p>
 *
 * @param infoName  信息物品（O 键纸）的名称组件（须来自 Lang 构建）
 * @param infoLore  信息物品的 lore 组件列表
 * @param onConfirm 确认回调（主线程调用；内部自行调度异步业务）
 * @param onCancel  取消回调；{@code null} 表示默认行为 {@code router.back()}
 */
public record ConfirmRequest(
        Component infoName,
        List<Component> infoLore,
        Runnable onConfirm,
        @Nullable Runnable onCancel) {

    /** 紧凑构造：校验必填项并冻结 lore 列表。 */
    public ConfirmRequest {
        Objects.requireNonNull(infoName, "确认信息名称不能为空");
        Objects.requireNonNull(infoLore, "确认信息 lore 不能为空");
        Objects.requireNonNull(onConfirm, "确认回调不能为空");
        infoLore = List.copyOf(infoLore);
    }

    /**
     * 便捷工厂：取消行为采用默认 {@code router.back()}。
     *
     * @param infoName  信息物品名称
     * @param infoLore  信息物品 lore
     * @param onConfirm 确认回调
     * @return 确认请求
     */
    public static ConfirmRequest of(Component infoName, List<Component> infoLore, Runnable onConfirm) {
        return new ConfirmRequest(infoName, infoLore, onConfirm, null);
    }
}
