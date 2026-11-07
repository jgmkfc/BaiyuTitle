package net.baiyu.title.hook.placeholderapi;

import net.baiyu.title.BaiYuTitle;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import net.baiyu.title.placeholder.InternalPlaceholderResolver;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * PlaceholderAPI 扩展：%byt_*% 全量变量委托
 * {@link InternalPlaceholderResolver} 解析（纯读内存缓存、零数据库 IO），
 * 输出前把 MiniMessage 转传统 § 码交给消费方插件。
 *
 * <p>解析器由业务装配产出：装配尚未完成（启动早期）或已收尾（停用中）时
 * 返回 null，PAPI 按"变量未解析"呈现。</p>
 */
public class PAPIExpansion extends PlaceholderExpansion {

    /** MiniMessage 解析器（无状态单例，线程安全）。 */
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    /** 传统 § 码序列化器（PAPI 消费方通用格式）。 */
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();
    private final BaiYuTitle plugin;

    public PAPIExpansion(BaiYuTitle plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        // 设计文档变量模板统一为 %byt_*%
        return "byt";
    }

    @Override
    @SuppressWarnings("UnstableApiUsage")
    public @NotNull String getAuthor() {
        return String.join(", ", plugin.getPluginMeta().getAuthors());
    }

    @Override
    @SuppressWarnings("UnstableApiUsage")
    public @NotNull String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        // 必须为 true，否则 PlaceholderAPI 在重载时会注销本扩展
        return true;
    }

    @Override
    public @Nullable String onRequest(OfflinePlayer p, @NotNull String params) {
        final InternalPlaceholderResolver resolver = plugin.getPlaceholderResolver();
        if (resolver == null || p == null) {
            return null;
        }
        return resolver.resolve(p.getUniqueId(), params)
                .map(PAPIExpansion::toLegacy)
                .orElse(null);
    }

    /** MiniMessage → 传统 § 码（纯数字等无标签文本原样透出）。 */
    private static String toLegacy(String miniMessage) {
        return LEGACY.serialize(MINI_MESSAGE.deserialize(miniMessage));
    }
}
