package net.baiyu.title.gui.render;

import net.baiyu.title.domain.condition.CoinCondition;
import net.baiyu.title.domain.condition.ConditionType;
import net.baiyu.title.domain.condition.ItemCondition;
import net.baiyu.title.domain.condition.PermissionCondition;
import net.baiyu.title.domain.condition.PurchaseCondition;
import net.baiyu.title.domain.condition.VaultCondition;
import net.baiyu.title.domain.title.CombineLogic;
import net.baiyu.title.domain.title.Title;
import net.baiyu.title.domain.title.TitleCategory;
import net.baiyu.title.gui.shop.ShopEntryView;
import net.baiyu.title.service.purchase.Eligibility;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 商店上下文 lore 区段组装器（业务设计 §1.3.2.4.1 逐字对照；
 * {@link LoreComposer} 的商店侧扩展，因单类行数纪律独立成类）。
 *
 * <p>三个商店专属区段 + 底行操作提示：购买区（表头两空格；类型行 + 逐条件价格行 +
 * 限时行）、个人状态区（表头一空格；已拥有分支 / 资源对照分支，满足 {@code &a}
 * 不满足 {@code &7}）、限定区（期限 / 总数）；底行按 {@link Eligibility} 三分支
 * 渲染 点击购买 / 无法购买 / 跳转仓库。区段拼装与空行分隔仍走
 * {@link LoreComposer#compose}，渲染管线共用 {@link LoreLines}。</p>
 *
 * <p>裁决记录：①经济不可用时 Vault 条件的个人状态行整行替换为
 * 「经济系统不可用」，余额数字无意义不再展示；
 * ②权限条件个人状态行取 有/无 二值（文档仅示例未持有的「无」，持有态对称取「有」）；
 * ③持有有效期天数为 365 整数倍时按年展示（对照示例 730 天 → 2年），
 * 否则按天展示。全类纯函数、零 Bukkit 依赖。</p>
 */
public final class ShopLoreComposer {

    /** lore 模板取词接缝（生产为 {@code lang::raw}）。 */
    private final LoreTemplateSource templates;

    /**
     * 构造商店 lore 区段组装器。
     *
     * @param templates lore 模板取词接缝
     */
    public ShopLoreComposer(LoreTemplateSource templates) {
        this.templates = Objects.requireNonNull(templates, "lore 模板取词接缝不能为空");
    }

    /**
     * 购买区：表头 + 类型行 + 明细行（NORMAL = 逐条件价格行；GROUP = 所属组行；
     * BOND = 需要称号行）+ 限时行（持有有效期 &gt; 0 时）。
     *
     * @param view     商店条目视图
     * @param brackets 称号名括号格式（羁绊需求称号名展示用）
     * @return 购买区组件行
     */
    public List<Component> purchaseSection(ShopEntryView view, TitleItemRenderer.NameBrackets brackets) {
        Title title = view.title();
        List<Component> lines = new ArrayList<>();
        lines.add(line("gui-item.section.purchase"));
        lines.add(line("gui-item.line.type", LoreLines.value("types", typesText(title))));
        switch (title.category()) {
            case GROUP -> {
                if (view.groupName() != null) {
                    lines.add(line("gui-item.line.group", LoreLines.value("group", view.groupName())));
                }
            }
            case BOND -> lines.add(line("gui-item.line.bond-requires",
                    LoreLines.value("titles", bondNames(view, brackets))));
            default -> appendPriceLines(view, lines);
        }
        if (title.holdDurationDays() > 0) {
            lines.add(line("gui-item.line.hold-duration",
                    LoreLines.value("duration", durationText(title.holdDurationDays()))));
        }
        return List.copyOf(lines);
    }

    /**
     * 个人状态区：已拥有 → 已拥有 + 装扮态两行；BOND → 逐需求 拥有/暂无；
     * NORMAL 含条件 → 逐条件资源对照（满足 {@code &a} 不满足 {@code &7}）；
     * 免费 / 无可展示项 → 空列表（整区省略）。
     *
     * @param view     商店条目视图
     * @param brackets 称号名括号格式（羁绊需求称号名展示用）
     * @return 个人状态区组件行；无内容时为空列表
     */
    public List<Component> personalSection(ShopEntryView view, TitleItemRenderer.NameBrackets brackets) {
        if (view.eligibility() instanceof Eligibility.Owned) {
            return List.of(line("gui-item.section.personal"),
                    line("gui-item.line.owned"),
                    line(view.equipped() ? "gui-item.line.equipped" : "gui-item.line.not-equipped"));
        }
        Title title = view.title();
        if (title.category() == TitleCategory.BOND) {
            return bondPersonalLines(view, brackets);
        }
        if (title.conditions().isEmpty()) {
            return List.of();
        }
        List<Component> lines = new ArrayList<>();
        lines.add(line("gui-item.section.personal"));
        Map<ConditionType, Boolean> status = view.eligibility().conditionStatus();
        for (PurchaseCondition condition : title.conditions()) {
            lines.add(personalLine(view, condition, status.getOrDefault(condition.type(), false)));
        }
        return List.copyOf(lines);
    }

    /**
     * 限定区：WINDOW → 期限行；
     * TOTAL → 总数行；NONE → 空列表（整区省略）。
     *
     * @param title 称号
     * @return 限定区组件行；无限定时为空列表
     */
    public List<Component> limitSection(Title title) {
        return switch (title.limitMode()) {
            case NONE -> List.of();
            case WINDOW -> List.of(line("gui-item.section.limit"),
                    line("gui-item.line.window",
                            LoreLines.value("start", windowDateText(title.windowStart())),
                            LoreLines.value("end", windowDateText(title.windowEnd()))));
            case TOTAL -> List.of(line("gui-item.section.limit"),
                    line("gui-item.line.total-cap",
                            LoreLines.value("cap", String.valueOf(title.totalCap()))));
        };
    }

    /**
     * 底行操作提示：可购买 → 点击购买；已拥有 → 跳转仓库；不可购 → 无法购买。
     *
     * @param eligibility 购买资格
     * @return 单行操作提示
     */
    public List<Component> actionLine(Eligibility eligibility) {
        Objects.requireNonNull(eligibility, "购买资格不能为空");
        return List.of(line(switch (eligibility) {
            case Eligibility.CanBuy ignored -> "gui-item.action.buy";
            case Eligibility.Owned ignored -> "gui-item.action.goto-repository";
            case Eligibility.CannotBuy ignored -> "gui-item.action.cannot-buy";
        }));
    }

    /** 类型行文案：免费 / 单类型 / 多类型（与 = joiner-and，或 = joiner-or 连接；包内共享给管理 lore）。 */
    String typesText(Title title) {
        return switch (title.category()) {
            case GROUP -> "<yellow>" + templates.raw("shop.type.group");
            case BOND -> "<yellow>" + templates.raw("shop.type.bond");
            default -> {
                List<PurchaseCondition> conditions = title.conditions();
                if (conditions.isEmpty()) {
                    yield "<yellow>" + templates.raw("shop.type.free");
                }
                String joiner = title.combineLogic() == CombineLogic.OR && conditions.size() >= 2
                        ? templates.raw("shop.type.joiner-or")
                        : templates.raw("shop.type.joiner-and");
                yield conditions.stream()
                        .map(condition -> "<yellow>" + typeName(condition.type()))
                        .collect(Collectors.joining(joiner));
            }
        };
    }

    /** 逐条件价格行（金币 / 称号币 / 物品价格 / 权限节点，按条件声明顺序）。 */
    private void appendPriceLines(ShopEntryView view, List<Component> lines) {
        for (PurchaseCondition condition : view.title().conditions()) {
            lines.add(switch (condition) {
                case VaultCondition vault -> line("gui-item.line.price-vault",
                        LoreLines.value("amount", plainNumber(vault.amount())));
                case CoinCondition coin -> line("gui-item.line.price-coin",
                        LoreLines.value("amount", Long.toString(coin.amount())));
                case ItemCondition item -> line("gui-item.line.price-item",
                        LoreLines.component("item", itemName(view, item.itemBase64())),
                        LoreLines.value("amount", Integer.toString(item.amount())));
                case PermissionCondition permission -> line("gui-item.line.price-permission",
                        LoreLines.value("node", permission.node()));
            });
        }
    }

    /** 单条件的个人状态行（满足 met 模板、不满足 unmet 模板）。 */
    private Component personalLine(ShopEntryView view, PurchaseCondition condition, boolean met) {
        String suffix = met ? "met" : "unmet";
        return switch (condition) {
            case VaultCondition ignored -> view.snapshot().economyAvailable()
                    ? line("gui-item.line.personal-vault-" + suffix,
                            LoreLines.value("balance", plainNumber(view.snapshot().vaultBalance())))
                    : line("gui-item.line.personal-economy-unavailable");
            case CoinCondition ignored -> line("gui-item.line.personal-coin-" + suffix,
                    LoreLines.value("balance", Long.toString(view.snapshot().coinBalance())));
            case ItemCondition item -> line("gui-item.line.personal-item-" + suffix,
                    LoreLines.component("item", itemName(view, item.itemBase64())),
                    LoreLines.value("count", Integer.toString(view.snapshot().itemCount(item.itemBase64()))));
            case PermissionCondition ignored -> line("gui-item.line.personal-permission-" + suffix);
        };
    }

    /** 羁绊个人状态：逐需求称号 拥有 / 暂无（无需求数据时整区省略）。 */
    private List<Component> bondPersonalLines(ShopEntryView view, TitleItemRenderer.NameBrackets brackets) {
        if (view.bondRequirements().isEmpty()) {
            return List.of();
        }
        List<Component> lines = new ArrayList<>();
        lines.add(line("gui-item.section.personal"));
        for (ShopEntryView.BondRequirementView requirement : view.bondRequirements()) {
            lines.add(line(requirement.owned()
                            ? "gui-item.line.personal-bond-met"
                            : "gui-item.line.personal-bond-unmet",
                    LoreLines.value("title", bracketed(requirement.name(), brackets))));
        }
        return List.copyOf(lines);
    }

    /** 羁绊需求称号名列表：逐个加括号段后以 list-joiner 连接。 */
    private String bondNames(ShopEntryView view, TitleItemRenderer.NameBrackets brackets) {
        return view.bondRequirements().stream()
                .map(requirement -> bracketed(requirement.name(), brackets))
                .collect(Collectors.joining(templates.raw("format.list-joiner")));
    }

    /** 条件物品展示名组件（视图预解析映射；缺失兜底 ? 防御渲染）。 */
    private static Component itemName(ShopEntryView view, String itemBase64) {
        return view.itemNames().getOrDefault(itemBase64, Component.text("?"));
    }

    /** 称号名加括号段（三段原文拼接，不 trim 空格——§1.5.2 空格红线）。 */
    private static String bracketed(String name, TitleItemRenderer.NameBrackets brackets) {
        return brackets.prefix() + name + brackets.suffix();
    }

    /** 持有有效期文案：365 整数倍 → N年，否则 N天。 */
    private String durationText(int days) {
        if (days % 365 == 0) {
            return templates.raw("format.duration-years")
                    .replace("<years>", Integer.toString(days / 365));
        }
        return templates.raw("format.duration-days").replace("<days>", Integer.toString(days));
    }

    /** 窗口日期文案（format.window-date 值级插值；月日不补零）；null = 空串。 */
    private String windowDateText(@Nullable LocalDate date) {
        if (date == null) {
            return "";
        }
        return templates.raw("format.window-date")
                .replace("<year>", Integer.toString(date.getYear()))
                .replace("<month>", Integer.toString(date.getMonthValue()))
                .replace("<day>", Integer.toString(date.getDayOfMonth()));
    }

    /** BigDecimal → 无尾零普通数字串（500.00 → 500；避免科学计数法）。 */
    private static String plainNumber(BigDecimal amount) {
        return amount.stripTrailingZeros().toPlainString();
    }

    /** 条件类型 → 类型名模板键取词。 */
    private String typeName(ConditionType type) {
        return templates.raw(switch (type) {
            case VAULT -> "shop.type.vault";
            case COIN -> "shop.type.coin";
            case ITEM -> "shop.type.item";
            case PERMISSION -> "shop.type.permission";
        });
    }

    /** 单行组件构建（共享渲染管线）。 */
    private Component line(String key, TagResolver... resolvers) {
        return LoreLines.line(templates, key, resolvers);
    }
}
