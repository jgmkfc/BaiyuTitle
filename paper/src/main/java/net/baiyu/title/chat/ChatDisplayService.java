package net.baiyu.title.chat;

import net.baiyu.title.config.PluginConfig;
import net.baiyu.title.domain.title.Title;
import net.baiyu.title.text.ColorNormalizer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;

/**
 * 聊天称号前缀构建服务。
 *
 * <p>每条消息只构建一次前缀：随机栏位在 {@link #buildPrefix(UUID)} 内用
 * {@link ThreadLocalRandom} 取样一次，同消息全观察者看到一致结果，
 * 下条消息再重新随机。</p>
 *
 * <p>单称号组件 = displayPrefix + 称号名 + displaySuffix 三段<b>分别</b>经
 * {@link ColorNormalizer} 归一化后构建 Component 再拼接，全程<b>不 trim
 * 空格</b>——修复业务设计 §1.5.2 的空格丢失回归点；分段构建同时防止括号段
 * 的默认色继承称号自身颜色。稀有度非空且合法时挂 hover。</p>
 *
 * <p>核心方法为纯函数风格（视图列表 / 格式快照 / 随机源全部显式传入），
 * 零 Bukkit 依赖、零 DB IO，供 M2 单测直接驱动。</p>
 */
public final class ChatDisplayService {

    /** 聊天展示视图门面。 */
    private final ChatDisplayCache displayCache;

    /** 插件配置提供器（每次构建时取最新格式快照，随 reload 刷新）。 */
    private final Supplier<PluginConfig> configSupplier;

    /** 稀有度 hover 组件工厂。 */
    private final RarityHoverFactory rarityHoverFactory;

    /**
     * 构造聊天称号前缀构建服务。
     *
     * @param displayCache       聊天展示视图门面
     * @param configSupplier     插件配置提供器（生产传 {@code configHandler::getConfig}；
     *                           纯函数单测不触碰它，可传返回 null 的占位）
     * @param rarityHoverFactory 稀有度 hover 组件工厂
     */
    public ChatDisplayService(ChatDisplayCache displayCache, Supplier<PluginConfig> configSupplier,
                              RarityHoverFactory rarityHoverFactory) {
        this.displayCache = Objects.requireNonNull(displayCache, "聊天展示视图门面不能为空");
        this.configSupplier = Objects.requireNonNull(configSupplier, "插件配置提供器不能为空");
        this.rarityHoverFactory = Objects.requireNonNull(rarityHoverFactory, "稀有度 hover 工厂不能为空");
    }

    /**
     * 为一条聊天消息构建完整称号前缀（事件处理线程调用，纯读缓存快照）。
     *
     * @param player 发言玩家 UUID
     * @return 前缀组件（含末尾的称号-玩家名分隔段）；无可展示栏位时为空
     */
    public Optional<Component> buildPrefix(UUID player) {
        return composePrefix(displayCache.equippedSlots(player),
                DisplayFormat.of(configSupplier.get()), ThreadLocalRandom.current());
    }

    /**
     * 纯函数：把佩戴栏位视图列表组装为前缀组件。
     * <p>多称号以 {@code titleSeparator} 连接，
     * 末尾追加 {@code nameSeparator}（称号与玩家名之间的分隔，§1.5.2）。</p>
     *
     * @param slots  佩戴栏位视图列表
     * @param format 展示格式快照
     * @param random 随机源（随机栏位取样；单测注入种子随机源）
     * @return 前缀组件；视图列表为空（全部栏位停用）时为空
     */
    public Optional<Component> composePrefix(List<ChatDisplayCache.EquippedSlotView> slots,
                                             DisplayFormat format, RandomGenerator random) {
        if (slots.isEmpty()) {
            return Optional.empty();
        }
        Component separator = parse(format.titleSeparator());
        TextComponent.Builder prefix = Component.text();
        for (int i = 0; i < slots.size(); i++) {
            if (i > 0) {
                prefix.append(separator);
            }
            prefix.append(renderSlot(slots.get(i), format, random));
        }
        prefix.append(parse(format.nameSeparator()));
        return Optional.of(prefix.build());
    }

    /**
     * 纯函数：渲染单个佩戴栏位。
     * <p>候选空集（默认称号）时整段渲染 config {@code defaultTitle}（自带括号，
     * 不再套 displayPrefix/Suffix）；随机栏位对候选取样<b>恰一次</b>。</p>
     *
     * @param slot   佩戴栏位视图
     * @param format 展示格式快照
     * @param random 随机源
     * @return 栏位组件
     */
    public Component renderSlot(ChatDisplayCache.EquippedSlotView slot,
                                DisplayFormat format, RandomGenerator random) {
        List<Title> candidates = slot.candidates();
        if (candidates.isEmpty()) {
            return parse(format.defaultTitle());
        }
        Title chosen = candidates.size() == 1
                ? candidates.get(0)
                : candidates.get(random.nextInt(candidates.size()));
        return renderTitle(chosen, format);
    }

    /**
     * 纯函数：渲染单个称号组件——三段分别归一化构建后拼接（不 trim 空格），
     * 稀有度合法时对整段挂 hover。
     *
     * @param title  称号（名称已按 MiniMessage 归一化存储；再归一化幂等无害）
     * @param format 展示格式快照
     * @return 称号组件
     */
    public Component renderTitle(Title title, DisplayFormat format) {
        Component unit = Component.text()
                .append(parse(format.displayPrefix()))
                .append(parse(title.name()))
                .append(parse(format.displaySuffix()))
                .build();
        return rarityHoverFactory.hoverOf(title.rarity())
                .<Component>map(hover -> unit.hoverEvent(HoverEvent.showText(hover)))
                .orElse(unit);
    }

    /** MiniMessage 解析器（无状态单例，线程安全）。 */
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    /**
     * 归一化并解析为组件：混合色码统一为 MiniMessage 后直接反序列化；
     * 空白与空串原样保留（不 trim）。
     * <p>刻意不走 ColorParser：其 build() 会触碰 Bukkit（探测 PlaceholderAPI），
     * 聊天渲染路径既不需要占位符展开（称号文本不应做 PAPI 展开），
     * 也必须保持纯函数可测。</p>
     */
    private static Component parse(String raw) {
        return MINI_MESSAGE.deserialize(ColorNormalizer.normalize(raw));
    }

    /**
     * 展示格式快照（从 config 一次性取值，构建期内不再回读配置）。
     *
     * @param displayPrefix  称号前括号段（config {@code title.prefix-brackets}）
     * @param displaySuffix  称号后括号段（config {@code title.suffix-brackets}）
     * @param titleSeparator 多称号之间的分隔段（config {@code chat.title-separator}）
     * @param nameSeparator  称号与玩家名之间的分隔段（config {@code title.is-chat-format}）
     * @param defaultTitle   默认称号完整文本（config {@code title.default-title}，自带括号）
     */
    public record DisplayFormat(String displayPrefix, String displaySuffix,
                                String titleSeparator, String nameSeparator, String defaultTitle) {

        /**
         * 从插件配置提取展示格式快照。
         *
         * @param config 插件配置
         * @return 展示格式快照
         */
        public static DisplayFormat of(PluginConfig config) {
            return new DisplayFormat(config.title.prefixBrackets, config.title.suffixBrackets,
                    config.chat.titleSeparator, config.title.isChatFormat, config.title.defaultTitle);
        }
    }
}
