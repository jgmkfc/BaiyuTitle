package net.baiyu.title.input;

import net.baiyu.title.gui.core.MenuSpec;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * 聊天输入请求（管理员向输入）。
 *
 * <p>会话语义：开启时向玩家发送 {@code promptKey} 提示并拦截其下一条聊天；
 * {@code cancel}/{@code stop}（忽略大小写、容许前后空白）视为取消；
 * 校验失败报错并保持监听重试；取消后经 {@code router.replace(returnTo)}
 * 回到来源菜单。完成后默认同样返回；链式输入可将 {@code returnAfterComplete}
 * 设为 false，并在完成回调中立即开启下一输入会话。回调一律在主线程执行。</p>
 *
 * @param promptKey  开启提示的 lang 键
 * @param validator  输入校验器（失败→报错重试）
 * @param onComplete 完成回调（入参为去前后空白后的输入文本；主线程）
 * @param onCancel   取消回调（cancel/stop、超时、顶替、退出共用；主线程，恰一次）
 * @param returnTo   结束后回到的菜单规格；null = 无菜单流程（命令入口）
 * @param returnAfterComplete 完成后是否立即返回 {@code returnTo}；链式输入设为 false
 */
public record ChatInputRequest(
        String promptKey,
        InputValidator validator,
        Consumer<String> onComplete,
        Runnable onCancel,
        @Nullable MenuSpec returnTo,
        boolean returnAfterComplete) {

    /** 常规输入兼容构造：完成后自动返回来源菜单。 */
    public ChatInputRequest(String promptKey, InputValidator validator,
                            Consumer<String> onComplete, Runnable onCancel,
                            @Nullable MenuSpec returnTo) {
        this(promptKey, validator, onComplete, onCancel, returnTo, true);
    }

    /** 紧凑构造：除 returnTo 外全部必填。 */
    public ChatInputRequest {
        Objects.requireNonNull(promptKey, "提示 lang 键不能为空");
        Objects.requireNonNull(validator, "校验器不能为空");
        Objects.requireNonNull(onComplete, "完成回调不能为空");
        Objects.requireNonNull(onCancel, "取消回调不能为空");
    }
}
