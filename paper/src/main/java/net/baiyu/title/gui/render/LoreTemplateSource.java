package net.baiyu.title.gui.render;

/**
 * lore 模板取词接缝：{@link LoreComposer} 只依赖本接口取
 * <b>原始模板字符串</b>，不依赖全局语言状态——渲染层因此保持纯函数可测。
 *
 * <p>生产装配传 {@code lang::raw}（{@code translation.Lang#raw(String)}，
 * 缺键时语言快照自动回退默认语言）；单测传真实 lang YAML 扁平化后的
 * map 查找，缺键直接失败以抓模板键拼写错误。</p>
 */
@FunctionalInterface
public interface LoreTemplateSource {

    /**
     * 取指定键的原始模板文本（未经组件解析）。
     *
     * @param key 语言键（点号路径，如 {@code gui-item.section.status}）
     * @return 原始模板字符串
     */
    String raw(String key);
}
