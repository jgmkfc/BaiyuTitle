package net.baiyu.title.placeholder;

import net.baiyu.title.cache.PlayerProfileCache;
import net.baiyu.title.cache.TitleCatalogCache;
import net.baiyu.title.chat.ChatDisplayCache;
import net.baiyu.title.chat.ChatDisplayService;
import net.baiyu.title.domain.condition.ConditionType;
import net.baiyu.title.domain.ownership.Ownership;
import net.baiyu.title.domain.title.Title;
import net.baiyu.title.domain.title.TitleCategory;
import net.baiyu.title.domain.wardrobe.SlotConfig;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PAPI 变量内部解析器：
 * {@code PAPIExpansion} 与个人信息聊天模板都委托本类，PAPI 缺失不影响内部功能。
 *
 * <p><b>零 DB IO 纪律</b>：全部变量只读
 * {@link PlayerProfileCache} / {@link TitleCatalogCache} 的 volatile 不可变快照，
 * 任意线程可调。离线玩家（档案未挂载）按业务语义降级：计数类返回 0、
 * 展示类返回空串。交易计数与栏位上限为档案快照字段（登录预载 + 结算写穿）。</p>
 *
 * <p>返回值为 MiniMessage 文本：个人信息路径直接解析；PAPI 路径由
 * {@code PAPIExpansion} 转传统 § 码后交给消费方插件。佩戴展示的字符串
 * 渲染委托 {@link UsingDisplay}。</p>
 */
public final class InternalPlaceholderResolver {

    /** 个人信息模板中的变量 token（%byt_xxx% 形式）。 */
    private static final Pattern TOKEN = Pattern.compile("%byt_([a-z0-9_~]+)%");

    /** 按类型计数变量前缀（多类型称号每命中一类各 +1）。 */
    private static final String AMOUNT_PREFIX = "player_title_amount_";

    /** 逐栏位展示变量前缀。 */
    private static final String USING_PREFIX = "player_title_using_";

    /** 在线玩家档案缓存。 */
    private final PlayerProfileCache profileCache;

    /** 佩戴展示渲染器（using / using_&lt;n&gt; 委托）。 */
    private final UsingDisplay usingDisplay;

    /**
     * 构造 PAPI 变量内部解析器。
     *
     * @param profileCache   在线玩家档案缓存
     * @param catalogCache   称号目录缓存
     * @param displayCache   聊天展示视图门面
     * @param formatSupplier 展示格式提供器（随 reload 取最新值）
     * @param messages       语言原文取词函数（生产传 {@code lang::raw}；单测注入映射函数）
     */
    public InternalPlaceholderResolver(PlayerProfileCache profileCache, TitleCatalogCache catalogCache,
                                       ChatDisplayCache displayCache,
                                       Supplier<ChatDisplayService.DisplayFormat> formatSupplier,
                                       Function<String, String> messages) {
        this.profileCache = Objects.requireNonNull(profileCache, "玩家档案缓存不能为空");
        this.usingDisplay = new UsingDisplay(catalogCache, displayCache, formatSupplier, messages);
    }

    /**
     * 解析单个变量（生产入口：系统时钟 + 线程随机源）。
     *
     * @param player 玩家 UUID
     * @param params 变量名（去掉 {@code byt_} 前缀，如 {@code player_coin}）
     * @return 变量值；未知变量为空
     */
    public Optional<String> resolve(UUID player, String params) {
        return resolve(player, params, System.currentTimeMillis(), ThreadLocalRandom.current());
    }

    /**
     * 解析单个变量（显式时钟与随机源重载，供单测注入）。
     *
     * @param player 玩家 UUID
     * @param params 变量名（去掉 {@code byt_} 前缀）
     * @param now    当前时刻（UTC epoch 毫秒；到期判定基准）
     * @param random 随机源（拼接预览的随机栏位取样）
     * @return 变量值；未知变量为空
     */
    public Optional<String> resolve(UUID player, String params, long now, RandomGenerator random) {
        Objects.requireNonNull(player, "玩家 UUID 不能为空");
        Objects.requireNonNull(params, "变量名不能为空");
        PlayerProfileCache.PlayerProfile profile = profileCache.get(player).orElse(null);
        return switch (params) {
            case "player_coin" -> Optional.of(Long.toString(profile == null ? 0L : profile.coinBalance()));
            case "player_title_amount" ->
                    Optional.of(Integer.toString(countOwned(profile, now, title -> true)));
            case "player_title_slots" ->
                    Optional.of(Integer.toString(profile == null ? 0 : profile.maxSlots()));
            case "player_title_slots_enabled" ->
                    Optional.of(Integer.toString(countEnabledSlots(profile)));
            case "player_trade_initiated" ->
                    Optional.of(Integer.toString(profile == null ? 0 : profile.tradeInitiated()));
            case "player_trade_completed" ->
                    Optional.of(Integer.toString(profile == null ? 0 : profile.tradeCompleted()));
            case "player_title_using" ->
                    Optional.of(profile == null ? "" : usingDisplay.preview(player, now, random));
            default -> resolvePrefixed(profile, params, now);
        };
    }

    /**
     * 展开模板行中的全部 %byt_*% token（个人信息聊天模板用；未知 token 原样保留）。
     *
     * @param template 模板行
     * @param player   玩家 UUID
     * @return 展开后的文本
     */
    public String expand(String template, UUID player) {
        Matcher matcher = TOKEN.matcher(template);
        StringBuilder result = new StringBuilder(template.length());
        while (matcher.find()) {
            String value = resolve(player, matcher.group(1)).orElse(matcher.group());
            matcher.appendReplacement(result, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    /** 前缀族变量：分类计数（amount_*）与逐栏位展示（using_&lt;n&gt;）。 */
    private Optional<String> resolvePrefixed(PlayerProfileCache.PlayerProfile profile,
                                             String params, long now) {
        if (params.startsWith(AMOUNT_PREFIX)) {
            Predicate<Title> predicate = typePredicate(params.substring(AMOUNT_PREFIX.length()));
            return predicate == null ? Optional.empty()
                    : Optional.of(Integer.toString(countOwned(profile, now, predicate)));
        }
        if (params.startsWith(USING_PREFIX)) {
            return slotIndexOf(params.substring(USING_PREFIX.length()))
                    .map(slotNo -> profile == null ? ""
                            : usingDisplay.slotDisplay(profile, slotNo, now));
        }
        return Optional.empty();
    }

    /** 有效持有称号计数：所有权 ACTIVE 未到期且目录未软删，再按类型谓词过滤。 */
    private int countOwned(PlayerProfileCache.PlayerProfile profile, long now,
                           Predicate<Title> predicate) {
        if (profile == null) {
            return 0;
        }
        int count = 0;
        for (Ownership ownership : profile.activeOwnerships().values()) {
            Optional<Title> title = usingDisplay.resolveValid(profile, ownership.titleId(), now);
            if (title.isPresent() && predicate.test(title.get())) {
                count++;
            }
        }
        return count;
    }

    /** 类型后缀 → 分类谓词（§8：多类型称号每命中一类各 +1；未知后缀为 null）。 */
    private static Predicate<Title> typePredicate(String suffix) {
        return switch (suffix) {
            case "free" -> Title::isFree;
            case "vault" -> hasCondition(ConditionType.VAULT);
            case "coin" -> hasCondition(ConditionType.COIN);
            case "item" -> hasCondition(ConditionType.ITEM);
            case "permission" -> hasCondition(ConditionType.PERMISSION);
            case "group" -> title -> title.category() == TitleCategory.GROUP;
            case "bond" -> title -> title.category() == TitleCategory.BOND;
            default -> null;
        };
    }

    /** 含指定购买条件类型的谓词。 */
    private static Predicate<Title> hasCondition(ConditionType type) {
        return title -> title.conditions().stream().anyMatch(cond -> cond.type() == type);
    }

    /** 已启用栏位数：1..上限逐栏判定，无记录按默认启用（与装扮视图同口径）。 */
    private static int countEnabledSlots(PlayerProfileCache.PlayerProfile profile) {
        if (profile == null) {
            return 0;
        }
        int enabled = 0;
        for (int slotNo = 1; slotNo <= profile.maxSlots(); slotNo++) {
            SlotConfig config = null;
            for (SlotConfig candidate : profile.slots()) {
                if (candidate.slotIndex() == slotNo) {
                    config = candidate;
                    break;
                }
            }
            if (config == null || config.enabled()) {
                enabled++;
            }
        }
        return enabled;
    }

    /** 解析栏位号后缀（非正整数视为未知变量）。 */
    private static Optional<Integer> slotIndexOf(String suffix) {
        try {
            int slotNo = Integer.parseInt(suffix);
            return slotNo >= 1 ? Optional.of(slotNo) : Optional.empty();
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
