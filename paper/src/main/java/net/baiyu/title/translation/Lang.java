package net.baiyu.title.translation;

import io.github.milkdrinkers.colorparser.paper.ColorParser;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * 语言门面：玩家可见文本的唯一取词入口。
 *
 * <p>无状态：不持有配置与缓存，逐次读取 {@link TranslationHandler} 的
 * 不可变 YAML 语言快照，
 * 再经 ColorParser 构建 Adventure {@link Component}（MiniMessage 为主、
 * 传统 &amp; 色码兼容）。快照的启动装载与 reload 原子替换由
 * {@link TranslationHandler} 负责，本类只读、随取随新。</p>
 *
 * <p>纪律：表现层一律经 {@code bootstrap.PluginAssembly}
 * 构造注入本门面；缺键时自动回退默认语言（en_US），不会返回 null。</p>
 */
public final class Lang {

    private final Function<String, String> rawLookup;
    private final Function<String, List<String>> listLookup;

    /** 生产构造：由组合根注入翻译生命周期实例。 */
    public Lang(TranslationHandler translations) {
        Objects.requireNonNull(translations, "翻译生命周期不能为空");
        this.rawLookup = translations::raw;
        this.listLookup = translations::rawList;
    }

    /**
     * 纯逻辑测试构造：缺键直接返回键名，多行为空。
     * 生产装配必须使用 {@link #Lang(TranslationHandler)}。
     */
    public Lang() {
        this.rawLookup = Function.identity();
        this.listLookup = key -> List.of();
    }

    /**
     * 取单行文本并构建组件。
     *
     * @param key       语言键（点号路径，如 {@code command.reload.success}）
     * @param resolvers MiniMessage 占位符解析器（如 {@code Placeholder.component("reason", ...)}）
     * @return 解析后的组件
     */
    public Component component(String key, TagResolver... resolvers) {
        return ColorParser.of(rawLookup.apply(key)).with(resolvers).build();
    }

    /**
     * 取多行文本并逐行构建组件（lore、多行帮助等）。
     *
     * @param key       语言键（值须为 YAML 字符串列表）
     * @param resolvers MiniMessage 占位符解析器（应用到每一行）
     * @return 解析后的组件列表（缺键时为空列表）
     */
    public List<Component> list(String key, TagResolver... resolvers) {
        return listLookup.apply(key).stream()
                .map(line -> ColorParser.of(line).with(resolvers).build())
                .toList();
    }

    /**
     * 取原始字符串（不做组件解析；供拼接模板、二次加工等场景）。
     *
     * @param key 语言键
     * @return 原始文本（缺键时回退默认语言，最终回退键名本身）
     */
    public String raw(String key) {
        return rawLookup.apply(key);
    }

    /**
     * 取多行原始字符串（不做组件解析；个人信息模板等需先展开变量的场景）。
     *
     * @param key 语言键（值须为 YAML 字符串列表）
     * @return 原始文本列表（缺键时为空列表）
     */
    public List<String> rawList(String key) {
        return listLookup.apply(key);
    }
}
