package net.baiyu.title.input;

import java.util.Optional;

/**
 * 输入校验器：纯函数——给定输入文本，返回校验错误或通过。
 *
 * <p>约定：入参已做前后去空白（会话管理器统一 trim 后再交校验）；
 * 校验失败时会话保持监听、玩家可重试，错误文案由 {@link InputError}
 * 携带的 lang 键渲染。常用校验器由 {@link InputValidators} 工厂提供。</p>
 */
@FunctionalInterface
public interface InputValidator {

    /**
     * 校验一段输入。
     *
     * @param input 输入文本（已去前后空白）
     * @return 空 = 通过；否则为报错信息
     */
    Optional<InputError> check(String input);

    /**
     * 恒通过的校验器（无格式约束的输入场景）。
     *
     * @return 恒通过校验器
     */
    static InputValidator any() {
        return input -> Optional.empty();
    }
}
