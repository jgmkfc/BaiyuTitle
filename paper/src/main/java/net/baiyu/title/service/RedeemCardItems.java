package net.baiyu.title.service;

import net.baiyu.title.domain.redeem.RedeemCard;
import net.baiyu.title.translation.Lang;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 称号卡物品编解码（称号卡可右键兑换）：
 * 卡片 UUID 经物品 PDC 读写的唯一实现——发卡命令与右键兑换监听器共用
 * 同一 {@link NamespacedKey}，防刷主体在服务端条件 UPDATE 单次核销，
 * 物品侧只承担携带与展示。
 *
 * <p>线程纪律：构造 / 解析触碰 Bukkit 物品元数据，仅主线程调用。</p>
 */
public final class RedeemCardItems {

    /** PDC 键名（{@code baiyutitle:card-uuid}，STRING 存卡片 UUID 文本）。 */
    private static final String KEY_NAME = "card-uuid";

    /** PDC 命名空间键。 */
    private final NamespacedKey cardKey;

    /** 语言门面（卡片名称 / lore 取词）。 */
    private final Lang lang;

    /**
     * 构造称号卡物品编解码器。
     *
     * @param plugin 插件实例（命名空间归属）
     * @param lang   语言门面
     */
    public RedeemCardItems(Plugin plugin, Lang lang) {
        this.cardKey = new NamespacedKey(Objects.requireNonNull(plugin, "插件实例不能为空"), KEY_NAME);
        this.lang = Objects.requireNonNull(lang, "语言门面不能为空");
    }

    /**
     * 构造称号卡物品（发卡命令消费）：名称 / lore 按 lang
     * {@code redeem.card.*}，PDC 写入卡片 UUID。
     *
     * @param card      称号卡（服务端已落库）
     * @param titleName 目标称号展示名组件（带括号）
     * @return 卡片物品（数量 1）
     */
    public ItemStack build(RedeemCard card, Component titleName) {
        Objects.requireNonNull(card, "称号卡不能为空");
        Objects.requireNonNull(titleName, "称号展示名不能为空");
        final ItemStack stack = new ItemStack(Material.PAPER);
        final String duration = durationText(card.holdDaysOverride());
        final String issuer = card.issuedBy() != null ? card.issuedBy() : "?";
        stack.editMeta(meta -> {
            meta.displayName(unitalic(lang.component("redeem.card.item-name",
                    Placeholder.component("name", titleName))));
            meta.lore(lang.list("redeem.card.item-lore",
                            Placeholder.component("name", titleName),
                            Placeholder.unparsed("duration", duration),
                            Placeholder.unparsed("issuer", issuer)).stream()
                    .map(RedeemCardItems::unitalic).toList());
            meta.setEnchantmentGlintOverride(Boolean.TRUE);
            meta.getPersistentDataContainer().set(cardKey, PersistentDataType.STRING,
                    card.cardUuid().toString());
        });
        return stack;
    }

    /**
     * 从物品解析卡片 UUID（右键兑换监听与消耗前二次校验共用）。
     *
     * @param stack 物品（可空）
     * @return 卡片 UUID；非称号卡或数据损坏时为空
     */
    public Optional<UUID> extract(@Nullable ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) {
            return Optional.empty();
        }
        final String raw = stack.getItemMeta().getPersistentDataContainer()
                .get(cardKey, PersistentDataType.STRING);
        if (raw == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(raw));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * 持有天数覆写 → 展示文本：null = 按称号默认；0 = 永久；n = n 天。
     *
     * @param holdDaysOverride 持有天数覆写
     * @return 展示文本（lang {@code redeem.card.duration-default} /
     *         {@code format.permanent} / {@code format.duration-days}）
     */
    public String durationText(@Nullable Integer holdDaysOverride) {
        if (holdDaysOverride == null) {
            return lang.raw("redeem.card.duration-default");
        }
        if (holdDaysOverride == 0) {
            return lang.raw("format.permanent");
        }
        return lang.raw("format.duration-days")
                .replace("<days>", String.valueOf(holdDaysOverride));
    }

    /** 未显式声明斜体时压为非斜体（与 GuiItemFactory 同口径）。 */
    private static Component unitalic(Component component) {
        return component.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }
}
