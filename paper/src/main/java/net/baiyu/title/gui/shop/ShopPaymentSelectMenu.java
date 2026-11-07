package net.baiyu.title.gui.shop;

import dev.triumphteam.gui.guis.BaseGui;
import net.baiyu.title.domain.condition.ConditionType;
import net.baiyu.title.domain.condition.PurchaseCondition;
import net.baiyu.title.domain.title.CombineLogic;
import net.baiyu.title.domain.title.Title;
import net.baiyu.title.gui.core.AbstractMenu;
import net.baiyu.title.gui.core.MenuContext;
import net.baiyu.title.gui.core.MenuLayout;
import net.baiyu.title.gui.core.MenuSpec;
import net.baiyu.title.service.ShopQueryService;
import net.baiyu.title.service.purchase.ChargeResolver;
import net.baiyu.title.service.purchase.Eligibility;
import net.baiyu.title.service.purchase.PurchaseAbort;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * 付款方式选择菜单（T1-03）：OR 逻辑下多个分支同时满足时，
 * 弹出本菜单让玩家自行选择付款方式，而非由系统取首个满足分支。
 *
 * <p>布局（参考 CustomTitlePaymentMenu 风格）：</p>
 * <pre>
 * "#########"
 * "#HZIZJZK#"   VAULT=H  COIN=I  ITEM=J  PERMISSION=K
 * "####C####"   C = 取消返回
 * </pre>
 *
 * <p>触发条件（仅在此三者同时成立时进入本菜单）：
 * <ol>
 *   <li>OR 逻辑（{@link CombineLogic#OR}）且条件 ≥ 2</li>
 *   <li>至少 2 个分支当前满足</li>
 *   <li>不含权限免单强制（{@link ChargeResolver} 服务端强制免单分支已过滤）</li>
 * </ol>
 * </p>
 *
 * <p>选项按钮逻辑：
 * <ul>
 *   <li>称号有该类型条件 + 当前满足 → 正常物品，点击进确认菜单</li>
 *   <li>称号有该类型条件 + 当前不满足 → BARRIER 置灰</li>
 *   <li>称号无该类型条件 → 不渲染（跳过）</li>
 * </ul>
 * </p>
 */
public final class ShopPaymentSelectMenu extends AbstractMenu {

    /** 布局字符画。 */
    private static final MenuLayout LAYOUT = MenuLayout.of(
            "#########",
            "#HZIZJZK#",
            "####C####");

    /** 目标称号 id。 */
    private final int titleId;

    /** 玩家商店上下文（构造时主线程收集）。 */
    private final ShopQueryService.ShopContext context;

    /** 商店条目视图（loadData 异步装载；称号缺失时为 null）。 */
    private @Nullable ShopEntryView entry;

    /**
     * 构造付款方式选择菜单。
     *
     * @param ctx     菜单上下文
     * @param titleId 目标称号 id
     */
    public ShopPaymentSelectMenu(MenuContext ctx, int titleId) {
        super(ctx);
        this.titleId = titleId;
        this.context = ctx.deps().shopViews().collectContext(ctx.player());
    }

    @Override
    protected MenuLayout layout() {
        return LAYOUT;
    }

    @Override
    protected Component title() {
        return lang("menu.shop.payment-select.title");
    }

    @Override
    public MenuSpec spec() {
        return new MenuSpec.ShopPaymentSelect(titleId);
    }

    /** 异步装载商店条目（缺失置 null 降级）。 */
    @Override
    protected void loadData() {
        entry = ctx.deps().shopViews()
                .detail(ctx.player().getUniqueId(), context, titleId)
                .map(ShopDetailView::entry)
                .orElse(null);
    }

    @Override
    protected void populate(BaseGui gui) {
        border(gui);
        gui.setItem(LAYOUT.slot('C'), ctx.deps().buttons().cancel(event -> back()));
        if (entry == null) {
            feedback("menu.shop.detail.missing");
            return;
        }
        // 称号已不可购（竞态：窗口结束 / 售罄等）→ 提示并返回
        if (!(entry.eligibility() instanceof Eligibility.CanBuy)) {
            feedback("menu.shop.payment-select.unavailable");
            back();
            return;
        }
        paintOptions(gui, entry);
    }

    /** 按四种付款方式逐个渲染按钮。 */
    private void paintOptions(BaseGui gui, ShopEntryView shopEntry) {
        paintOption(gui, 'H', ConditionType.VAULT, Material.GOLD_INGOT, "vault", shopEntry);
        paintOption(gui, 'I', ConditionType.COIN, Material.EMERALD, "coin", shopEntry);
        paintOption(gui, 'J', ConditionType.ITEM, Material.GRASS_BLOCK, "item", shopEntry);
        paintOption(gui, 'K', ConditionType.PERMISSION, Material.COMMAND_BLOCK, "permission", shopEntry);
    }

    /**
     * 渲染单个付款选项。
     *
     * <ul>
     *   <li>称号无此类型条件 → 不放物品（格留空）</li>
     *   <li>条件存在但不满足 → BARRIER 置灰</li>
     *   <li>条件存在且满足 → 正常物品，点击进确认菜单</li>
     * </ul>
     */
    private void paintOption(BaseGui gui, char role, ConditionType type,
                             Material material, String langKey, ShopEntryView shopEntry) {
        // 称号是否有该类型条件
        Title title = shopEntry.title();
        boolean hasCond = title.conditions().stream().anyMatch(c -> c.type() == type);
        if (!hasCond) {
            return; // 无此条件 → 格留空
        }
        Map<ConditionType, Boolean> status = shopEntry.eligibility().conditionStatus();
        boolean satisfied = Boolean.TRUE.equals(status.get(type));
        Component name = lang("menu.shop.payment-select.option." + langKey + ".name");
        if (!satisfied) {
            gui.setItem(LAYOUT.slot(role), ctx.deps().itemFactory().item(
                    Material.BARRIER, name,
                    langList("menu.shop.payment-select.option.unavailable-lore"),
                    event -> feedback("menu.shop.payment-select.option-not-met")));
            return;
        }
        // 满足 → 组装应付展示组件并渲染可点击按钮
        Component costValue = buildCostValue(title, type, shopEntry);
        gui.setItem(LAYOUT.slot(role), ctx.deps().itemFactory().item(
                material, name,
                langList("menu.shop.payment-select.option.lore",
                        Placeholder.component("value", costValue)),
                false, 1,
                event -> openConfirm(shopEntry, type)));
    }

    /** 组装应付项展示组件（对齐 CustomTitlePaymentMenu 的 price.* 键风格；
     *  物品方式含 translatable 物品名组件，由客户端按自身语言翻译）。 */
    private Component buildCostValue(Title title, ConditionType chosen, ShopEntryView shopEntry) {
        for (PurchaseCondition cond : title.conditions()) {
            if (cond.type() != chosen) {
                continue;
            }
            return switch (cond) {
                case net.baiyu.title.domain.condition.VaultCondition v ->
                        ctx.deps().lang().component("menu.shop.payment-select.price.vault",
                                Placeholder.parsed("amount", v.amount().stripTrailingZeros().toPlainString()));
                case net.baiyu.title.domain.condition.CoinCondition c ->
                        ctx.deps().lang().component("menu.shop.payment-select.price.coin",
                                Placeholder.parsed("amount", Long.toString(c.amount())));
                case net.baiyu.title.domain.condition.ItemCondition i ->
                        ctx.deps().lang().component("menu.shop.payment-select.price.item",
                                Placeholder.component("item", shopEntry.itemNames()
                                        .getOrDefault(i.itemBase64(), Component.text("?"))),
                                Placeholder.parsed("amount", Integer.toString(i.amount())));
                case net.baiyu.title.domain.condition.PermissionCondition p ->
                        ctx.deps().lang().component("menu.shop.payment-select.price.permission",
                                Placeholder.parsed("node", p.node()));
            };
        }
        return Component.text("?");
    }

    /** 点击可用选项 → 组装确认菜单（应付裁决失败时提示，不弹确认）。 */
    private void openConfirm(ShopEntryView shopEntry, ConditionType chosen) {
        try {
            ctx.deps().router().open(ctx.player(),
                    new MenuSpec.Confirm(ShopPurchaseConfirm.build(ctx, shopEntry, chosen, this::settleBack)));
        } catch (PurchaseAbort abort) {
            feedback(net.baiyu.title.service.PurchaseFlow.rejectKey(abort.reason()));
        }
    }

    /**
     * 购买链终局：确认菜单仍在前台时连续两次 back
     * （弹确认菜单 → 弹本选择菜单），自然落回商店（称号变 Owned，重建后显示跳转仓库）。
     */
    private void settleBack() {
        if (!ctx.player().isOnline()) {
            return;
        }
        // 若当前规格仍为 Confirm，back 弹到本菜单；再 back 弹到商店
        if (ctx.session().currentSpec() instanceof MenuSpec.Confirm) {
            back(); // → ShopPaymentSelect
        }
        if (ctx.player().isOnline()) {
            back(); // → Shop
        }
    }

    /**
     * 判断给定商店条目是否需要弹出本选择菜单（对外静态工具方法，由 ShopMenu 调用）。
     *
     * <p>触发条件：OR 逻辑 + 满足分支 ≥ 2 + 无权限免单强制。</p>
     *
     * @param entry 商店条目视图
     * @return 需要弹出选择菜单时返回 true
     */
    public static boolean needsSelection(ShopEntryView entry) {
        Title title = entry.title();
        if (title.combineLogic() != CombineLogic.OR || title.conditions().size() < 2) {
            return false;
        }
        // 含权限条件且玩家持有 → 服务端强制免单权限分支，无需玩家选
        boolean permissionForced = title.conditions().stream()
                .anyMatch(c -> c instanceof net.baiyu.title.domain.condition.PermissionCondition p
                        && entry.snapshot().hasPermission(p.node()));
        if (permissionForced) {
            return false;
        }
        // 统计满足的分支数
        long metCount = entry.eligibility().conditionStatus().values().stream()
                .filter(Boolean.TRUE::equals).count();
        return metCount >= 2;
    }

    /**
     * 从满足的分支中取首个作为默认选择（无需弹菜单时 ShopMenu 直接用）。
     * 与原 {@code PurchaseFlow.chooseBranch} 语义相同，集中到此处供两处共用。
     *
     * @param entry 商店条目视图
     * @return 首个满足的分支类型；AND / 免费 / 单条件时为 null
     */
    public static @Nullable ConditionType firstMetBranch(ShopEntryView entry) {
        Title title = entry.title();
        if (title.combineLogic() != CombineLogic.OR || title.conditions().size() < 2) {
            return null;
        }
        for (Map.Entry<ConditionType, Boolean> kv : entry.eligibility().conditionStatus().entrySet()) {
            if (Boolean.TRUE.equals(kv.getValue())) {
                return kv.getKey();
            }
        }
        return null;
    }

}
