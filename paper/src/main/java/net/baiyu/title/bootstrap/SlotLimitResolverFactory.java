package net.baiyu.title.bootstrap;

import net.baiyu.title.config.ConfigHandler;
import net.baiyu.title.config.PluginConfig;
import net.baiyu.title.service.WardrobeService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;

/**
 * 栏位上限解析器工厂（{@link PluginAssembly} 的内部协作者）。
 *
 * <p>按 config {@code groups} 顺序取首个权限命中的组；{@code permission}
 * 为空的组是人人命中的兜底默认组。</p>
 */
final class SlotLimitResolverFactory {

    private SlotLimitResolverFactory() {
    }

    /**
     * 构造栏位上限解析器。
     * <p>只在主线程调用（装扮入口同步解析后带入异步链）；玩家离线时
     * 无法查权限，按兜底默认组处理。</p>
     *
     * @param configHandler 配置处理器（groups 段实时取值，reload 后生效）
     * @return 栏位上限解析器
     */
    static WardrobeService.SlotLimitResolver create(ConfigHandler configHandler) {
        return (UUID uuid) -> {
            final Player player = Bukkit.getPlayer(uuid);
            int fallback = 1;
            for (final Map.Entry<String, PluginConfig.GroupSettings> entry
                    : configHandler.getConfig().groups.entrySet()) {
                final PluginConfig.GroupSettings group = entry.getValue();
                final boolean isFallback = group.permission == null || group.permission.isEmpty();
                if (isFallback) {
                    fallback = group.maxTitles;
                    continue;
                }
                if (player != null && player.hasPermission(group.permission)) {
                    return group.maxTitles;
                }
            }
            return fallback;
        };
    }
}
