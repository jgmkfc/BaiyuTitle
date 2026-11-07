package net.baiyu.title.gui.common;

import dev.triumphteam.gui.guis.BaseGui;
import net.baiyu.title.gui.core.AbstractMenu;
import net.baiyu.title.gui.core.ConfirmRequest;
import net.baiyu.title.gui.core.GuiItemFactory;
import net.baiyu.title.gui.core.MenuContext;
import net.baiyu.title.gui.core.MenuLayout;
import net.baiyu.title.gui.core.MenuSpec;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 通用确认菜单：购买 / 删除 /
 * 发起报价 / 同意报价 / 自定义创建 / 收回称号等全部二次确认复用。
 *
 * <p>瞬态菜单（不入返回栈）：B 拒绝走 {@link ConfirmRequest#onCancel()}
 * （默认 {@code router.back()}——回到发起确认的菜单，栈空则关闭）。
 * 确认按钮持一次性 {@link AtomicBoolean} token 防双击（排程方案风险 5）：
 * 第二次点击直接吞掉，确保回调恰执行一次。</p>
 */
public final class ConfirmMenu extends AbstractMenu {

    /** 布局字符画。 */
    private static final MenuLayout LAYOUT = MenuLayout.of(
            "####O####",
            "##A###B##",
            "#########");

    /** 确认请求（展示信息与确认 / 取消回调）。 */
    private final ConfirmRequest request;

    /** 一次性防双击 token（确认回调恰执行一次）。 */
    private final AtomicBoolean consumed = new AtomicBoolean(false);

    /**
     * 构造通用确认菜单。
     *
     * @param ctx     菜单上下文
     * @param request 确认请求
     */
    public ConfirmMenu(MenuContext ctx, ConfirmRequest request) {
        super(ctx);
        this.request = Objects.requireNonNull(request, "确认请求不能为空");
    }

    @Override
    protected MenuLayout layout() {
        return LAYOUT;
    }

    @Override
    protected Component title() {
        return lang("menu.confirm.title");
    }

    @Override
    public MenuSpec spec() {
        return new MenuSpec.Confirm(request);
    }

    /** 瞬态：导航离开时不入返回栈（B 拒绝的 back 自然回到发起菜单）。 */
    @Override
    public boolean isTransient() {
        return true;
    }

    @Override
    protected void populate(BaseGui gui) {
        final GuiItemFactory items = ctx.deps().itemFactory();
        border(gui);
        gui.setItem(LAYOUT.slot('O'), items.item(Material.PAPER,
                request.infoName(), request.infoLore(), null));
        gui.setItem(LAYOUT.slot('A'), items.item(Material.LIME_STAINED_GLASS_PANE,
                lang("menu.confirm.confirm.name"),
                langList("menu.confirm.confirm.lore"),
                event -> {
                    if (consumed.compareAndSet(false, true)) {
                        request.onConfirm().run();
                    }
                }));
        gui.setItem(LAYOUT.slot('B'), items.item(Material.RED_STAINED_GLASS_PANE,
                lang("menu.confirm.cancel.name"),
                langList("menu.confirm.cancel.lore"),
                event -> cancel()));
    }

    /** B 拒绝：显式取消回调优先，默认返回上一级（栈空关闭——§1.3.2.3.3.1 备注）。 */
    private void cancel() {
        if (request.onCancel() != null) {
            request.onCancel().run();
        } else {
            back();
        }
    }
}
