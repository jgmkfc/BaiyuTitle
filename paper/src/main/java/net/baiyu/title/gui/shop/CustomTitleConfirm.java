package net.baiyu.title.gui.shop;

import net.baiyu.title.domain.condition.ConditionType;
import net.baiyu.title.gui.core.ConfirmRequest;
import net.baiyu.title.gui.core.MenuContext;
import net.baiyu.title.text.ColorNormalizer;
import net.baiyu.title.translation.Lang;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import java.util.ArrayList;
import java.util.List;

/**
 * 自定义称号创建确认请求组装（业务设计 §1.3.2.3.1.4：铁砧命名后跳转确认菜单，
 * 同意后创建）：把所选付款方式 + 命名回执组装为 {@link ConfirmRequest}，
 * 确认回调接 {@code CustomTitleFlow.create} 完整创建链。
 *
 * <p>名称预览按与服务端同一条归一化路径（ColorNormalizer）构建；归一化失败
 * （剥色后为空等）降级为原文预览，权威校验仍由服务端开单链完成并按
 * {@code shop.reject.name-invalid} 拒绝。</p>
 */
final class CustomTitleConfirm {

    /** MiniMessage 解析器（归一化产物反序列化预览）。 */
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    private CustomTitleConfirm() {
    }

    /**
     * 组装创建确认请求（主线程）。
     *
     * @param ctx       菜单上下文
     * @param payment   所选付款方式
     * @param valueText 应付展示文本组件（付款选项视图预组装；物品方式含 translatable 物品名）
     * @param rawName   原始名称输入（铁砧回执）
     * @param onSettled 创建链终局回调（主线程）
     * @return 确认请求
     */
    static ConfirmRequest build(MenuContext ctx, ConditionType payment, Component valueText,
                                String rawName, Runnable onSettled) {
        Lang lang = ctx.deps().lang();
        Component name = preview(rawName);
        Component infoName = lang.component("menu.custom.confirm.info.name",
                Placeholder.component("name", name));
        List<Component> lore = new ArrayList<>(lang.list("menu.custom.confirm.info.intro",
                Placeholder.component("name", name)));
        lore.add(lang.component("menu.custom.confirm.cost",
                Placeholder.component("value", valueText)));
        return ConfirmRequest.of(infoName, lore,
                () -> ctx.deps().customFlow().create(ctx.player(), payment, rawName, onSettled));
    }

    /** 名称预览：归一化 + MiniMessage 构建；归一化失败降级原文纯文本。 */
    private static Component preview(String rawName) {
        try {
            return MINI_MESSAGE.deserialize(ColorNormalizer.normalizeName(rawName));
        } catch (IllegalArgumentException e) {
            return Component.text(rawName);
        }
    }
}
