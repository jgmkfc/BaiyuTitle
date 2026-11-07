package net.baiyu.title.gui.shop;

import net.baiyu.title.domain.condition.ConditionType;
import net.baiyu.title.gui.core.ConfirmRequest;
import net.baiyu.title.gui.core.MenuContext;
import net.baiyu.title.service.PurchaseFlow;
import net.baiyu.title.service.purchase.ChargeResolver;
import net.baiyu.title.service.purchase.PurchaseAbort;
import net.baiyu.title.translation.Lang;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 购买确认请求组装（业务设计 §1.3.2.3.3.1：O 键信息纸「确认购买 [xxx]？」，
 * lore 详细解释应付项）：把商店条目 + 应付裁决结果组装为 {@link ConfirmRequest}，
 * 确认回调接 {@link PurchaseFlow} 完整购买链。
 *
 * <p>应付项经 {@link ChargeResolver}（阶段1 公开复用接缝）以与服务端相同的
 * 规则预演——"或"含权限且持有恒免单权限分支，确认菜单展示与实际扣付一致。
 * 裁决失败（经济不可用竞态等）上抛 {@link PurchaseAbort}，由商店菜单
 * 捕获后按拒绝原因提示。</p>
 */
final class ShopPurchaseConfirm {

    private ShopPurchaseConfirm() {
    }

    /**
     * 组装购买确认请求（主线程）。
     *
     * @param ctx       菜单上下文
     * @param entry     商店条目视图（CanBuy 分支）
     * @param chosen    玩家已选的"或"分支（T1-03 选择菜单传入；AND/免费/单条件为 null，
     *                  此时内部回落 {@link PurchaseFlow#chooseBranch} 自动取首个满足分支）
     * @param onSettled 购买链终局回调（主线程）
     * @return 确认请求
     * @throws PurchaseAbort 应付裁决失败时（分支非法 / 经济不可用等竞态）
     */
    static ConfirmRequest build(MenuContext ctx, ShopEntryView entry,
                                @Nullable ConditionType chosen, Runnable onSettled) {
        // chosen 为 null 时自动取首个满足分支（兼容不需要选择菜单的路径）
        ConditionType resolved = chosen != null ? chosen : PurchaseFlow.chooseBranch(entry);
        ChargeResolver.Charges charges = ChargeResolver.resolve(entry.title(), resolved, entry.snapshot());
        Lang lang = ctx.deps().lang();
        Component titleName = ctx.deps().renderer().nameWithBrackets(entry.title());
        Component infoName = lang.component("menu.shop.confirm.info.name",
                Placeholder.component("title", titleName));
        List<Component> lore = new ArrayList<>(lang.list("menu.shop.confirm.info.intro",
                Placeholder.component("title", titleName)));
        lore.addAll(costLines(lang, entry, charges));
        return ConfirmRequest.of(infoName, lore,
                () -> ctx.deps().purchases().start(ctx.player(), entry, resolved, onSettled));
    }

    /** 应付项逐行展示（免费 / 免单为单行；有价按 金币 / 称号币 / 物品 / 权限 逐项）。 */
    private static List<Component> costLines(Lang lang, ShopEntryView entry,
                                             ChargeResolver.Charges charges) {
        List<Component> lines = new ArrayList<>();
        if (charges.vaultAmount() != null) {
            lines.add(lang.component("menu.shop.confirm.cost.vault",
                    Placeholder.parsed("amount", plainNumber(charges.vaultAmount()))));
        }
        if (charges.coinAmount() != null) {
            lines.add(lang.component("menu.shop.confirm.cost.coin",
                    Placeholder.parsed("amount", Long.toString(charges.coinAmount()))));
        }
        if (charges.itemBase64() != null) {
            lines.add(lang.component("menu.shop.confirm.cost.item",
                    Placeholder.component("item", itemName(entry, charges.itemBase64())),
                    Placeholder.parsed("amount", String.valueOf(charges.itemAmount()))));
        }
        if (charges.permissionNode() != null) {
            lines.add(lang.component("menu.shop.confirm.cost.permission",
                    Placeholder.parsed("node", charges.permissionNode())));
        }
        if (lines.isEmpty()) {
            lines.add(lang.component("menu.shop.confirm.cost.free"));
        }
        return lines;
    }

    /** 条件物品展示名组件（视图预解析映射；缺失兜底 ? 防御渲染）。 */
    private static Component itemName(ShopEntryView entry, @Nullable String itemBase64) {
        return itemBase64 == null ? Component.text("?") : entry.itemNames().getOrDefault(itemBase64, Component.text("?"));
    }

    /** BigDecimal → 无尾零普通数字串（与商店 lore 同口径）。 */
    private static String plainNumber(BigDecimal amount) {
        return amount.stripTrailingZeros().toPlainString();
    }
}
