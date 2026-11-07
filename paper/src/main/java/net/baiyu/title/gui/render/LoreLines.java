package net.baiyu.title.gui.render;

import net.baiyu.title.text.ColorNormalizer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

/**
 * lore 行构建工具（包内共享）：模板取词 → {@link ColorNormalizer} 归一化 →
 * MiniMessage 反序列化为单行组件；占位符一律 {@link Placeholder#parsed}
 * （预处理替换，值先归一化再插入解析流）。
 *
 * <p>{@link LoreComposer} 与 {@link ShopLoreComposer} 共用本工具，
 * 保证两条组装路径的渲染管线逐字一致（刻意不走 ColorParser——
 * 其 build() 触碰 Bukkit 无法单测，亦不应对 lore 文本做占位符展开）。</p>
 */
final class LoreLines {

    /** MiniMessage 解析器（无状态单例，线程安全）。 */
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    private LoreLines() {
    }

    /**
     * 取模板 → 归一化 → 反序列化为单行组件。
     *
     * @param templates lore 模板取词接缝
     * @param key       语言键
     * @param resolvers 预处理占位符
     * @return 单行组件
     */
    static Component line(LoreTemplateSource templates, String key, TagResolver... resolvers) {
        return MINI_MESSAGE.deserialize(ColorNormalizer.normalize(templates.raw(key)), resolvers);
    }

    /**
     * 构造预处理占位符：值先归一化（兼容 &amp; 码文本）再插入解析流。
     *
     * @param name     占位符名
     * @param rawValue 原始值
     * @return 预处理占位符
     */
    static TagResolver value(String name, String rawValue) {
        return Placeholder.parsed(name, ColorNormalizer.normalize(rawValue));
    }

    /**
     * 构造组件占位符：把现成 {@link Component}（如 translatable 物品名组件）直接
     * 嵌入模板对应位置，不再经 MiniMessage 二次解析——保留组件原有的翻译 / 格式语义。
     *
     * @param name  占位符名
     * @param value 组件值
     * @return 组件占位符
     */
    static TagResolver component(String name, Component value) {
        return Placeholder.component(name, value);
    }
}
