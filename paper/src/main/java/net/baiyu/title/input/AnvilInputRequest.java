package net.baiyu.title.input;

import net.baiyu.title.gui.core.MenuSpec;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * 铁砧输入请求（玩家向输入）。
 *
 * <p>会话语义：开启时打开 Paper 原生 {@code MenuType.ANVIL} 虚拟视图，
 * 左槽放命名牌；点击结果槽视为完成（读取重命名文本，码点计长由校验器负责），
 * 关闭界面视为取消；完成/取消后经 {@code router.replace(returnTo)}
 * 回到来源菜单。回调一律在主线程执行。</p>
 *
 * @param titleKey   铁砧界面标题的 lang 键
 * @param validator  输入校验器（失败→报错，界面保持打开重试）
 * @param onComplete 完成回调（入参为去前后空白后的重命名文本；主线程）
 * @param onCancel   取消回调（关闭界面、顶替、退出共用；主线程，恰一次）
 * @param returnTo   结束后回到的菜单规格；null = 无菜单流程（命令入口，如兑换码）
 */
public record AnvilInputRequest(
        String titleKey,
        InputValidator validator,
        Consumer<String> onComplete,
        Runnable onCancel,
        @Nullable MenuSpec returnTo) {

    /** 紧凑构造：除 returnTo 外全部必填。 */
    public AnvilInputRequest {
        Objects.requireNonNull(titleKey, "标题 lang 键不能为空");
        Objects.requireNonNull(validator, "校验器不能为空");
        Objects.requireNonNull(onComplete, "完成回调不能为空");
        Objects.requireNonNull(onCancel, "取消回调不能为空");
    }
}
