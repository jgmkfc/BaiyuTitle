package net.baiyu.title.gui.admin;

import dev.triumphteam.gui.guis.GuiItem;
import net.baiyu.title.domain.title.LimitMode;
import net.baiyu.title.domain.title.Title;
import net.baiyu.title.gui.core.MenuContext;
import net.baiyu.title.gui.core.MenuSpec;
import net.baiyu.title.input.InputValidator;
import net.baiyu.title.input.InputValidators;
import net.baiyu.title.service.AdminTitleService;
import net.baiyu.title.service.ItemStackCodec;
import net.baiyu.title.service.TitleEditorView;
import net.baiyu.title.text.ColorNormalizer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Material;
import org.jetbrains.annotations.Nullable;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 编辑器 19 项按钮构建器。
 *
 * <p>三类交互：聊天输入项（returnTo = 当前编辑页，完成 / 取消自动回编辑器）、
 * 即点即改切换项（写操作落定后刷新）、子菜单项（navigate）。不可用项渲染
 * 禁用态（灰字 lore + 点击提示），如羁绊 / 自定义 / 组称号的「可交易」。</p>
 *
 * <p>裁决偏离记录：①价格四项（11/12/13/14）对 NORMAL 称号恒可点——
 * 表格「购买类型含 X 时可用」在「类型 = 条件行派生」的数据模型下会与
 * 类型选择菜单互为死锁（启用类型需先有价格条件），录价即启用类型；
 * ②窗口 / 总数（17/18）恒可点且录入即自动切换对应限定模式（模式字段
 * 领域联动），项 16 承担「清除限定」。</p>
 */
final class EditorButtons {

    /** 宿主编辑器菜单（输入 / 写收尾 / 导航 / 提示接缝）。 */
    private final AdminTitleEditorMenu host;

    /** 菜单上下文。 */
    private final MenuContext ctx;

    /** 编辑器视图（当前值展示素材）。 */
    private final TitleEditorView view;

    /** 构造编辑器按钮构建器（每次 populate 全新构建）。 */
    EditorButtons(AdminTitleEditorMenu host, MenuContext ctx, TitleEditorView view) {
        this.host = host;
        this.ctx = ctx;
        this.view = view;
    }

    /**
     * 构建指定编辑页的按钮列表（裁决⑩：第 1 页项 1~9、第 2 页项 11~19，
     * 项号 10 为设计文档预留空号）。
     *
     * @param editorPage 编辑页码（1 起）
     * @return 按布局 X 槽位顺序排列的按钮
     */
    List<GuiItem> page(int editorPage) {
        return editorPage <= 1 ? pageOne() : pageTwo();
    }

    /** 第 1 页：项 1 名称 ~ 项 9 有效期。 */
    private List<GuiItem> pageOne() {
        final Title t = view.title();
        final int id = host.titleId();
        final AdminTitleService admin = ctx.deps().adminViews();
        return List.of(
                chat(Material.NAME_TAG, "name", t.name(), true,
                        InputValidators.length(1, 100), input -> admin.rename(id, input)),
                chat(Material.BOOK, "description", descriptionValue(t), true,
                        InputValidators.length(1, 300), input -> admin.setDescription(id, input)),
                click(Material.ITEM_FRAME, "icon", iconComponent(t), true,
                        () -> host.heldItemEdit("icon", item -> admin.setIcon(id, item))),
                click(Material.ENDER_PEARL, "rarity", orNone(t.rarity()), true,
                        () -> host.goTo(new MenuSpec.RaritySelect(id))),
                click(Material.LEVER, "visible",
                        raw(t.visible() ? "menu.admin.editor.value.visible"
                                : "menu.admin.editor.value.hidden"),
                        true, () -> host.write(admin.toggleVisible(id))),
                click(Material.COMPASS, "buy-types", buyTypesValue(), true,
                        () -> host.goTo(new MenuSpec.BuyTypeSelect(id))),
                click(Material.COMPARATOR, "logic",
                        raw("menu.admin.editor.value."
                                + t.combineLogic().name().toLowerCase(Locale.ROOT)),
                        view.logicEditable(), () -> host.write(admin.toggleLogic(id))),
                click(Material.HOPPER, "tradable",
                        raw(t.tradable() ? "menu.admin.editor.value.yes"
                                : "menu.admin.editor.value.no"),
                        view.tradableEditable(), () -> host.write(admin.toggleTradable(id))),
                chat(Material.DAYLIGHT_DETECTOR, "hold", holdValue(t), true,
                        InputValidators.integerRange(0, 36_500), input -> admin.setHoldDuration(id,
                                InputValidators.parseInteger(input).orElseThrow().intValue())));
    }

    /** 第 2 页：项 11 金币价格 ~ 项 19 已售统计。 */
    private List<GuiItem> pageTwo() {
        final Title t = view.title();
        final int id = host.titleId();
        final AdminTitleService admin = ctx.deps().adminViews();
        return List.of(
                chat(Material.GOLD_INGOT, "price-vault",
                        view.vaultPrice() == null ? none()
                                : view.vaultPrice().stripTrailingZeros().toPlainString(),
                        view.priceEditable(), InputValidators.amount(), input -> admin.setVaultPrice(id,
                                InputValidators.parseAmount(input).orElseThrow())),
                chat(Material.EMERALD, "price-coin",
                        view.coinPrice() == null ? none() : Long.toString(view.coinPrice()),
                        view.priceEditable(), InputValidators.integerRange(0, 1_000_000_000L),
                        input -> admin.setCoinPrice(id,
                                InputValidators.parseInteger(input).orElseThrow())),
                click(Material.CHEST, "price-item", itemCostComponent(), view.priceEditable(),
                        () -> host.itemCostEdit((item, amount) ->
                                admin.setItemCost(id, item, amount))),
                chat(Material.NAME_TAG, "permission", orNone(view.permissionNode()),
                        view.priceEditable(), InputValidators.length(1, 255),
                        input -> admin.setPermissionNode(id, input)),
                chat(Material.NAME_TAG, "bond", bondValue(t), view.bondEditable(),
                        InputValidators.titleIdList(), input -> admin.setBondRequirements(id,
                                Set.copyOf(InputValidators.parseTitleIds(input).orElseThrow()))),
                click(Material.COMPARATOR, "limit-mode", limitModeValue(t), true, () -> {
                    if (t.limitMode() == LimitMode.NONE) {
                        host.hint("menu.admin.editor.limit-hint");
                    } else {
                        host.write(admin.cycleLimitMode(id));
                    }
                }),
                chat(Material.RECOVERY_COMPASS, "window", windowValue(t), true,
                        InputValidators.dateWindow(LocalDate::now), input -> {
                            final InputValidators.DateWindow window = InputValidators
                                    .parseDateWindow(input, LocalDate.now()).orElseThrow();
                            return admin.setWindow(id, window.start(), window.end());
                        }),
                chat(Material.BUNDLE, "total-cap",
                        t.limitMode() == LimitMode.TOTAL ? String.valueOf(t.totalCap()) : none(),
                        true, InputValidators.integerRange(1, 1_000_000_000L),
                        input -> admin.setTotalCap(id,
                                InputValidators.parseInteger(input).orElseThrow().intValue())),
                click(Material.PAPER, "stats", statsValue(t), true,
                        () -> host.goTo(new MenuSpec.BuyerList(id, 1))));
    }

    // ---- 按钮骨架 ----

    /** 聊天输入项：可用时点击开输入会话；禁用时点击仅提示。 */
    private GuiItem chat(Material material, String key, String value, boolean enabled,
                         InputValidator validator, Function<String, CompletableFuture<?>> operation) {
        return click(material, key, value, enabled, () -> host.chatEdit(key, validator, operation));
    }

    /** 即点即改 / 子菜单项：lore = 当前值行 + 空行 + 操作行（禁用态换灰字提示）。 */
    private GuiItem click(Material material, String key, String value, boolean enabled,
                          Runnable onClick) {
        final List<Component> lore = List.of(
                component("menu.admin.editor.current",
                        Placeholder.parsed("value", ColorNormalizer.normalize(value))),
                Component.text(" "),
                enabled ? component("menu.admin.editor.item." + key + ".action")
                        : component("menu.admin.editor.disabled"));
        return ctx.deps().itemFactory().item(material,
                component("menu.admin.editor.item." + key + ".name"), lore,
                event -> {
                    if (enabled) {
                        onClick.run();
                    } else {
                        host.hint("menu.admin.editor.unavailable");
                    }
                });
    }

    /** click 的组件值重载：物品价格等含 translatable 物品名的当前值经此注入
     *  （{@code value} 已是组件，直接嵌入不再二次解析，保留翻译语义）。 */
    private GuiItem click(Material material, String key, Component value, boolean enabled,
                          Runnable onClick) {
        final List<Component> lore = List.of(
                component("menu.admin.editor.current",
                        Placeholder.component("value", value)),
                Component.text(" "),
                enabled ? component("menu.admin.editor.item." + key + ".action")
                        : component("menu.admin.editor.disabled"));
        return ctx.deps().itemFactory().item(material,
                component("menu.admin.editor.item." + key + ".name"), lore,
                event -> {
                    if (enabled) {
                        onClick.run();
                    } else {
                        host.hint("menu.admin.editor.unavailable");
                    }
                });
    }

    // ---- 当前值文案 ----

    /** 简介：多行以 {@code |} 连接展示；无简介为「无」。 */
    private String descriptionValue(Title t) {
        return t.description().isEmpty() ? none() : String.join("|", t.description());
    }

    /** 购买类型：类型名以 list-joiner 连接。 */
    private String buyTypesValue() {
        return view.buyTypes().stream()
                .map(type -> raw("shop.type." + type.name().toLowerCase(Locale.ROOT)))
                .collect(Collectors.joining(raw("format.list-joiner")));
    }

    /** 有效期：0 = 永久，否则 N 天。 */
    private String holdValue(Title t) {
        return t.holdDurationDays() == 0 ? raw("format.permanent")
                : raw("format.duration-days").replace("<days>", Integer.toString(t.holdDurationDays()));
    }

    /** 物品价格：主线程反序列化取本地化物品名组件（损坏数据兜底 ?；
     *  含 translatable 物品名，由客户端按自身语言翻译）。 */
    private Component itemCostComponent() {
        if (view.itemCost() == null) {
            return component("menu.admin.editor.value.none");
        }
        final Component name = ItemStackCodec.tryDecode(view.itemCost().itemBase64())
                .map(stack -> ctx.deps().itemNames().displayComponent(stack.getType()))
                .orElse(Component.text("?"));
        return component("menu.admin.editor.value.item-cost",
                Placeholder.component("item", name),
                Placeholder.parsed("amount", Integer.toString(view.itemCost().amount())));
    }

    /** 称号图标：材质键解析成功时走物品名语言表，损坏键保留原值便于排查。 */
    private Component iconComponent(Title title) {
        return iconComponent(title.iconMaterial(),
                ctx.deps().itemNames()::displayComponent);
    }

    /** 包级纯函数接缝：供编辑器图标本地化回归测试使用。 */
    static Component iconComponent(String iconMaterial,
                                   Function<Material, Component> itemNames) {
        final Material material = Material.matchMaterial(iconMaterial);
        return material == null ? Component.text(iconMaterial) : itemNames.apply(material);
    }

    /** 羁绊需求：需求 id 升序以 {@code |} 连接；无需求为「无」。 */
    private String bondValue(Title t) {
        if (t.bondRequirements().isEmpty()) {
            return none();
        }
        return t.bondRequirements().stream().sorted()
                .map(String::valueOf).collect(Collectors.joining("|"));
    }

    /** 限定模式：无限制 / 时间窗口 / 总数限量。 */
    private String limitModeValue(Title t) {
        return raw(switch (t.limitMode()) {
            case NONE -> "menu.admin.editor.value.mode-none";
            case WINDOW -> "menu.admin.editor.value.mode-window";
            case TOTAL -> "menu.admin.editor.value.mode-total";
        });
    }

    /** 获取窗口：WINDOW → 起止范围（起始缺省为空串），否则「无」。 */
    private String windowValue(Title t) {
        if (t.limitMode() != LimitMode.WINDOW) {
            return none();
        }
        return raw("menu.admin.editor.value.window-range")
                .replace("<start>", dateText(t.windowStart()))
                .replace("<end>", dateText(t.windowEnd()));
    }

    /** 已售统计：TOTAL 模式带上限，否则仅已售份数。 */
    private String statsValue(Title t) {
        if (t.limitMode() == LimitMode.TOTAL && t.totalCap() != null) {
            return raw("menu.admin.editor.value.sold-capped")
                    .replace("<sold>", Integer.toString(t.soldCount()))
                    .replace("<cap>", String.valueOf(t.totalCap()));
        }
        return raw("menu.admin.editor.value.sold")
                .replace("<sold>", Integer.toString(t.soldCount()));
    }

    /** 窗口日期文案（format.window-date 值级插值）；null = 空串。 */
    private String dateText(@Nullable LocalDate date) {
        if (date == null) {
            return "";
        }
        return raw("format.window-date")
                .replace("<year>", Integer.toString(date.getYear()))
                .replace("<month>", Integer.toString(date.getMonthValue()))
                .replace("<day>", Integer.toString(date.getDayOfMonth()));
    }

    /** 可空值展示：null / 空白 → 「无」。 */
    private String orNone(@Nullable String value) {
        return value == null || value.isBlank() ? none() : value;
    }

    /** 「无」占位文案。 */
    private String none() {
        return raw("menu.admin.editor.value.none");
    }

    /** 单行语言组件便捷转发。 */
    private Component component(String key, TagResolver... resolvers) {
        return ctx.deps().lang().component(key, resolvers);
    }

    /** 原始模板取词便捷转发。 */
    private String raw(String key) {
        return ctx.deps().lang().raw(key);
    }
}
