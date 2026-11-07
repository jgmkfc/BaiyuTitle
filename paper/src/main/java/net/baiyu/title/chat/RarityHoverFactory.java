package net.baiyu.title.chat;

import net.baiyu.title.translation.Lang;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 稀有度 hover 组件工厂。
 *
 * <p>稀有度按<b>名称字符串</b>存储；
 * 展示前须在 config {@code rarities} 列表中解析确认——名称不在列表中
 * （管理员删改配置后的"越界"情形）时降级为无稀有度展示（不挂 hover）。</p>
 *
 * <p>hover 文案取 lang 键 {@code chat.rarity-hover}（多行列表，占位符
 * {@code <rarity>}），逐行构建后以换行连接成单个组件。列表以 Supplier
 * 注入（组合根传 {@code () -> config.rarities}），reload 后即时生效，
 * 单测可注入固定列表而无需构造 ConfigHandler。</p>
 */
public final class RarityHoverFactory {

    /** hover 文案的 lang 键。 */
    private static final String LANG_KEY = "chat.rarity-hover";

    /** 稀有度列表提供者（组合根注入 config 读取器）。 */
    private final Supplier<List<String>> raritiesSupplier;

    /** 语言门面。 */
    private final Lang lang;

    /**
     * 构造稀有度 hover 组件工厂。
     *
     * @param raritiesSupplier 稀有度列表提供者（每次调用重新取值，随 reload 刷新）
     * @param lang             语言门面
     */
    public RarityHoverFactory(Supplier<List<String>> raritiesSupplier, Lang lang) {
        this.raritiesSupplier = Objects.requireNonNull(raritiesSupplier, "稀有度列表提供者不能为空");
        this.lang = Objects.requireNonNull(lang, "语言门面不能为空");
    }

    /**
     * 构建给定稀有度的 hover 组件。
     *
     * @param rarity 稀有度名称（可空）
     * @return hover 组件；稀有度为空、不在 config 列表中（越界降级）或
     *         lang 文案缺失时为空
     */
    public Optional<Component> hoverOf(@Nullable String rarity) {
        if (rarity == null || rarity.isBlank() || !isKnown(rarity)) {
            return Optional.empty();
        }
        List<Component> lines = lang.list(LANG_KEY, Placeholder.unparsed("rarity", rarity));
        if (lines.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(Component.join(JoinConfiguration.newlines(), lines));
    }

    /**
     * 判断稀有度名称是否存在于当前 config 列表（精确匹配，区分大小写）。
     *
     * @param rarity 稀有度名称
     * @return 存在返回 true
     */
    public boolean isKnown(String rarity) {
        List<String> rarities = raritiesSupplier.get();
        return rarities != null && rarities.contains(rarity);
    }
}
