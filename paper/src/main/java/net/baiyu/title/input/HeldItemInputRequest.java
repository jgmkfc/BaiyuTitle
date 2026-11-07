package net.baiyu.title.input;

import net.baiyu.title.gui.core.MenuSpec;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * 手持物品输入请求：玩家关闭来源菜单后，手持目标物品并左键确认。
 *
 * <p>空手左键仅提示并继续等待；聊天输入 {@code cancel}/{@code stop}、
 * 超时、顶替、退出均走统一取消回调。完成时传入主手物品的单件副本，
 * 回调与导航一律在主线程执行。</p>
 *
 * @param promptKey  开启提示的 lang 键
 * @param onComplete 完成回调（入参为确认时主手物品的单件副本）
 * @param onCancel   取消回调
 * @param returnTo   完成或主动取消后返回的菜单；null = 不返回菜单
 */
public record HeldItemInputRequest(
        String promptKey,
        Consumer<ItemStack> onComplete,
        Runnable onCancel,
        @Nullable MenuSpec returnTo) {

    /** 紧凑构造：除 returnTo 外全部必填。 */
    public HeldItemInputRequest {
        Objects.requireNonNull(promptKey, "提示 lang 键不能为空");
        Objects.requireNonNull(onComplete, "完成回调不能为空");
        Objects.requireNonNull(onCancel, "取消回调不能为空");
    }
}
