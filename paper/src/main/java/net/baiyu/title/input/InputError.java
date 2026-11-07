package net.baiyu.title.input;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 输入校验错误：携带报错 lang 键与占位符键值对。
 *
 * <p>刻意不依赖 Adventure——校验层保持纯函数可测；由
 * {@link InputSessionManager} 侧把占位符转成 MiniMessage
 * {@code Placeholder.unparsed} 后经 Lang 渲染。</p>
 *
 * @param langKey      报错文案的 lang 键（如 {@code input.error.length}）
 * @param placeholders 占位符键值对（键为 MiniMessage 标签名，值为纯文本）
 */
public record InputError(String langKey, Map<String, String> placeholders) {

    /** 紧凑构造：键必填；占位符防御性拷贝为不可变。 */
    public InputError {
        Objects.requireNonNull(langKey, "报错 lang 键不能为空");
        placeholders = Map.copyOf(Objects.requireNonNull(placeholders, "占位符表不能为空"));
    }

    /**
     * 无占位符的错误。
     *
     * @param langKey 报错 lang 键
     * @return 错误实例
     */
    public static InputError of(String langKey) {
        return new InputError(langKey, Map.of());
    }

    /**
     * 带占位符的错误（键值交替传参）。
     *
     * @param langKey       报错 lang 键
     * @param keyValuePairs 占位符键值交替序列（长度必须为偶数）
     * @return 错误实例
     */
    public static InputError of(String langKey, String... keyValuePairs) {
        if (keyValuePairs.length % 2 != 0) {
            throw new IllegalArgumentException("占位符键值必须成对出现，实际 " + keyValuePairs.length + " 个");
        }
        final Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            map.put(keyValuePairs[i], keyValuePairs[i + 1]);
        }
        return new InputError(langKey, map);
    }
}
