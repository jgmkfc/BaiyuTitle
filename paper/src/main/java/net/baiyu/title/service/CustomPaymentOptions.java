package net.baiyu.title.service;

import net.baiyu.title.config.PluginConfig;
import net.baiyu.title.domain.condition.ConditionType;
import net.baiyu.title.gui.shop.PaymentOptionView;
import net.baiyu.title.translation.Lang;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 自定义称号付款选项组装器（{@link CustomTitleFlow} 的内部协作者）：
 * 把 config {@code custom-title} 四种付款方式预计算为付款菜单视图，应付展示文本按 lang
 * {@code menu.custom.price.*} 模板组装。
 *
 * <p>可用性裁决：Vault 缺失 H 不可用；
 * 权限节点未配置 K 不可用；物品材质 key 非法 J 不可用；
 * 价格 ≤ 0 的货币方式展示为免费。仅主线程调用（触碰经济门面）。</p>
 *
 * <p>物品方式（J）的展示文本含 translatable 物品名组件，由客户端按自身语言
 * 翻译——经 {@link Component#translatable(Material)} 兜底，无需插件维护翻译表。</p>
 */
final class CustomPaymentOptions {

    /** 经济门面（Vault 可用性）。 */
    private final EconomyGateway economyGateway;

    /** 插件配置提供器（custom-title 段随 reload 取新值）。 */
    private final Supplier<PluginConfig> configSupplier;

    /** 语言门面（价格模板取词与组件构建）。 */
    private final Lang lang;

    /** 物品名解析器（物品付款选项的本地化展示，返回 Component）。 */
    private final Function<Material, Component> materialNames;

    /**
     * 构造付款选项组装器（依赖与 {@link CustomTitleFlow} 同源）。
     *
     * @param economyGateway 经济门面
     * @param configSupplier 插件配置提供器
     * @param lang           语言门面
     * @param materialNames  物品名解析器（返回 Component）
     */
    CustomPaymentOptions(EconomyGateway economyGateway, Supplier<PluginConfig> configSupplier,
                         Lang lang, Function<Material, Component> materialNames) {
        this.economyGateway = Objects.requireNonNull(economyGateway, "经济门面不能为空");
        this.configSupplier = Objects.requireNonNull(configSupplier, "插件配置提供器不能为空");
        this.lang = Objects.requireNonNull(lang, "语言门面不能为空");
        this.materialNames = Objects.requireNonNull(materialNames, "物品名解析器不能为空");
    }

    /**
     * 组装四付款选项视图（H/I/J/K 固定顺序，不可变）。
     *
     * @return 付款选项视图列表
     */
    List<PaymentOptionView> options() {
        PluginConfig.CustomTitleSettings cfg = configSupplier.get().customTitle;
        Material material = Material.matchMaterial(cfg.itemMaterial);
        return List.of(
                new PaymentOptionView(ConditionType.VAULT, economyGateway.isAvailable(),
                        cfg.vaultPrice > 0
                                ? lang.component("menu.custom.price.vault",
                                        Placeholder.parsed("amount",
                                                plainNumber(BigDecimal.valueOf(cfg.vaultPrice))))
                                : lang.component("menu.custom.price.free")),
                new PaymentOptionView(ConditionType.COIN, true,
                        cfg.coinPrice > 0
                                ? lang.component("menu.custom.price.coin",
                                        Placeholder.parsed("amount", Long.toString(cfg.coinPrice)))
                                : lang.component("menu.custom.price.free")),
                new PaymentOptionView(ConditionType.ITEM, material != null,
                        lang.component("menu.custom.price.item",
                                Placeholder.component("item", material != null
                                        ? materialNames.apply(material)
                                        : Component.text(cfg.itemMaterial)),
                                Placeholder.parsed("amount", Integer.toString(cfg.itemAmount)))),
                new PaymentOptionView(ConditionType.PERMISSION,
                        cfg.permissionNode != null && !cfg.permissionNode.isBlank(),
                        lang.component("menu.custom.price.permission",
                                Placeholder.parsed("node", String.valueOf(cfg.permissionNode)))));
    }

    /** BigDecimal → 无尾零普通数字串（与商店 lore 同口径）。 */
    private static String plainNumber(BigDecimal amount) {
        return amount.stripTrailingZeros().toPlainString();
    }
}
