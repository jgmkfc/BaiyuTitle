package net.baiyu.title.placeholder;

import net.baiyu.title.cache.PlayerProfileCache;
import net.baiyu.title.cache.TitleCatalogCache;
import net.baiyu.title.chat.ChatDisplayCache;
import net.baiyu.title.chat.ChatDisplayService;
import net.baiyu.title.domain.ownership.Ownership;
import net.baiyu.title.domain.title.Title;
import net.baiyu.title.domain.wardrobe.SlotConfig;
import net.baiyu.title.text.ColorNormalizer;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;

/**
 * 佩戴展示渲染器（{@link InternalPlaceholderResolver} 的内部协作者）：
 * 承载 %byt_player_title_using%（拼接预览）与 %byt_player_title_using_&lt;n&gt;%
 * （逐栏位展示）的字符串渲染——与聊天前缀同构但不含 hover。
 *
 * <p>零 DB IO：只读缓存快照；输出 MiniMessage 文本，三段各自归一化、
 * 段间 reset 阻断样式渗漏（与聊天分段构建同语义）。</p>
 */
final class UsingDisplay {

    /** 称号目录缓存。 */
    private final TitleCatalogCache catalogCache;

    /** 聊天展示视图门面（拼接预览与聊天前缀同源）。 */
    private final ChatDisplayCache displayCache;

    /** 展示格式提供器（随 reload 取最新括号段 / 分隔段 / 默认称号）。 */
    private final Supplier<ChatDisplayService.DisplayFormat> formatSupplier;

    /** 语言原文取词（papi.random / papi.slot-disabled）。 */
    private final Function<String, String> messages;

    /**
     * 构造佩戴展示渲染器。
     *
     * @param catalogCache   称号目录缓存
     * @param displayCache   聊天展示视图门面
     * @param formatSupplier 展示格式提供器
     * @param messages       语言原文取词函数
     */
    UsingDisplay(TitleCatalogCache catalogCache, ChatDisplayCache displayCache,
                 Supplier<ChatDisplayService.DisplayFormat> formatSupplier,
                 Function<String, String> messages) {
        this.catalogCache = Objects.requireNonNull(catalogCache, "称号目录缓存不能为空");
        this.displayCache = Objects.requireNonNull(displayCache, "聊天展示视图门面不能为空");
        this.formatSupplier = Objects.requireNonNull(formatSupplier, "展示格式提供器不能为空");
        this.messages = Objects.requireNonNull(messages, "语言取词函数不能为空");
    }

    /**
     * 逐栏位展示（§8：随机栏"随机(池大小)"；停用栏取 lang；固定栏 = 括号单元，
     * 无效引用降级默认称号，与聊天渲染同判据）。
     *
     * @param profile 玩家档案快照
     * @param slotNo  栏位号（1 起）
     * @param now     当前时刻（到期判定基准）
     * @return 展示文本；超出栏位上限为空串
     */
    String slotDisplay(PlayerProfileCache.PlayerProfile profile, int slotNo, long now) {
        if (slotNo < 1 || slotNo > profile.maxSlots()) {
            return "";
        }
        ChatDisplayService.DisplayFormat format = formatSupplier.get();
        SlotConfig config = slotConfigOf(profile, slotNo);
        if (config == null) {
            return ColorNormalizer.normalize(format.defaultTitle());
        }
        if (!config.enabled()) {
            return messages.apply("papi.slot-disabled");
        }
        if (config.isRandom()) {
            Set<Integer> pool = profile.randomPools().getOrDefault(slotNo, Set.of());
            long validCount = pool.stream()
                    .filter(titleId -> resolveValid(profile, titleId, now).isPresent())
                    .count();
            return messages.apply("papi.random").replace("<count>", Long.toString(validCount));
        }
        return config.titleId() == null
                ? ColorNormalizer.normalize(format.defaultTitle())
                : resolveValid(profile, config.titleId(), now)
                        .map(title -> unit(title, format))
                        .orElseGet(() -> ColorNormalizer.normalize(format.defaultTitle()));
    }

    /**
     * 拼接预览（§8：与聊天前缀同构但不含 hover、不含玩家名分隔段）：
     * 启用栏位逐个渲染（随机栏取样恰一次）后以分隔段连接。
     *
     * @param player 玩家 UUID
     * @param now    当前时刻
     * @param random 随机源（随机栏位取样）
     * @return 预览文本；全部栏位停用时为空串
     */
    String preview(UUID player, long now, RandomGenerator random) {
        List<ChatDisplayCache.EquippedSlotView> views = displayCache.equippedSlots(player, now);
        if (views.isEmpty()) {
            return "";
        }
        ChatDisplayService.DisplayFormat format = formatSupplier.get();
        String separator = ColorNormalizer.normalize(format.titleSeparator());
        StringBuilder preview = new StringBuilder();
        for (int i = 0; i < views.size(); i++) {
            if (i > 0) {
                preview.append(separator);
            }
            List<Title> candidates = views.get(i).candidates();
            if (candidates.isEmpty()) {
                preview.append(ColorNormalizer.normalize(format.defaultTitle()));
            } else {
                Title chosen = candidates.size() == 1
                        ? candidates.get(0)
                        : candidates.get(random.nextInt(candidates.size()));
                preview.append(unit(chosen, format));
            }
        }
        return preview.toString();
    }

    /**
     * 解析有效称号引用：本人 ACTIVE 未到期所有权 + 目录未软删（与聊天路径同判据）。
     *
     * @param profile 玩家档案快照
     * @param titleId 称号 id
     * @param now     当前时刻
     * @return 有效称号；任一判据不满足为空
     */
    Optional<Title> resolveValid(PlayerProfileCache.PlayerProfile profile, int titleId, long now) {
        Ownership ownership = profile.activeOwnerships().get(titleId);
        if (ownership == null || !ownership.isActive() || ownership.isExpired(now)) {
            return Optional.empty();
        }
        return catalogCache.byId(titleId).filter(title -> !title.isDeleted());
    }

    /** 单称号括号单元：三段各自归一化、段间 reset 阻断样式渗漏。 */
    private static String unit(Title title, ChatDisplayService.DisplayFormat format) {
        return ColorNormalizer.normalize(format.displayPrefix()) + "<reset>"
                + ColorNormalizer.normalize(title.name()) + "<reset>"
                + ColorNormalizer.normalize(format.displaySuffix());
    }

    /** 查栏位配置（无记录返回 null = 默认启用 + 默认称号）。 */
    private static SlotConfig slotConfigOf(PlayerProfileCache.PlayerProfile profile, int slotNo) {
        for (SlotConfig config : profile.slots()) {
            if (config.slotIndex() == slotNo) {
                return config;
            }
        }
        return null;
    }
}
