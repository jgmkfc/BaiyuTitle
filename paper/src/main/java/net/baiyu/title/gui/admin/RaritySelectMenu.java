package net.baiyu.title.gui.admin;

import dev.triumphteam.gui.guis.BaseGui;
import dev.triumphteam.gui.guis.GuiItem;
import net.baiyu.title.gui.core.AbstractMenu;
import net.baiyu.title.gui.core.MenuContext;
import net.baiyu.title.gui.core.MenuLayout;
import net.baiyu.title.gui.core.MenuSpec;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * 稀有度选择菜单（编辑器项 4；表现层设计 §4 表——「无稀有度」+
 * config rarities 动态单行单选）。
 *
 * <p>首格 = 无稀有度（屏障），随后按 config {@code rarities} 顺序动态铺
 * 选项（末影珍珠；行容量 7，超出部分不展示——config 建议 ≤ 6 个稀有度）；
 * 附魔 = 当前暂选。点击暂选（不落库），A 应用 → {@code setRarity} 落库并
 * 弹栈重建编辑器；B 取消 / ESC 丢弃暂选。暂选存菜单实例字段——
 * refresh 同实例重开不丢，离开菜单即弃。</p>
 */
public final class RaritySelectMenu extends AbstractMenu {

    /** 布局字符画（与筛选/排序同族的单行选择布局）。 */
    private static final MenuLayout LAYOUT = MenuLayout.of(
            "#########",
            "#XXXXXXX#",
            "##A###B##");

    /** 目标称号 id。 */
    private final int titleId;

    /** 暂选稀有度名称；null = 无稀有度。 */
    private @Nullable String pending;

    /** 目标称号是否存在（构造时播种暂选的同时判定）。 */
    private final boolean present;

    /** 应用中防双击标记。 */
    private boolean applying;

    /**
     * 构造稀有度选择菜单（主线程：以现状稀有度播种暂选）。
     *
     * @param ctx     菜单上下文
     * @param titleId 目标称号 id
     */
    public RaritySelectMenu(MenuContext ctx, int titleId) {
        super(ctx);
        this.titleId = titleId;
        final var view = ctx.deps().adminViews().editorView(titleId);
        this.present = view.isPresent();
        this.pending = view.map(v -> v.title().rarity()).orElse(null);
    }

    @Override
    protected MenuLayout layout() {
        return LAYOUT;
    }

    @Override
    protected Component title() {
        return lang("menu.admin.rarity.title");
    }

    @Override
    public MenuSpec spec() {
        return new MenuSpec.RaritySelect(titleId);
    }

    @Override
    protected void populate(BaseGui gui) {
        border(gui);
        gui.setItem(LAYOUT.slot('B'), ctx.deps().buttons().cancel(event -> finish()));
        if (!present) {
            feedback("menu.admin.editor.missing");
            return;
        }
        final int[] slots = LAYOUT.slots('X');
        gui.setItem(slots[0], option(Material.BARRIER,
                lang("menu.admin.rarity.none.name"), null));
        final List<String> rarities = ctx.deps().configHandler().getConfig().rarities;
        for (int i = 0; i < rarities.size() && i + 1 < slots.length; i++) {
            final String rarity = rarities.get(i);
            gui.setItem(slots[i + 1], option(Material.ENDER_PEARL,
                    lang("menu.admin.rarity.option.name",
                            Placeholder.parsed("rarity", rarity)), rarity));
        }
        gui.setItem(LAYOUT.slot('A'), ctx.deps().buttons().apply(event -> apply()));
    }

    /** 构造单个选项（附魔 = 暂选中；点击单选暂定）。 */
    private GuiItem option(Material material, Component name, @Nullable String rarity) {
        return ctx.deps().itemFactory().item(material, name,
                langList("menu.admin.rarity.option.lore"),
                Objects.equals(pending, rarity), 1, event -> {
                    pending = rarity;
                    refresh();
                });
    }

    /** A 应用：落库暂选稀有度 → 弹栈重建编辑器。 */
    private void apply() {
        if (applying) {
            return;
        }
        applying = true;
        ctx.deps().adminViews().setRarity(titleId, pending)
                .whenComplete((result, failure) -> runSync(() -> {
                    applying = false;
                    if (failure != null) {
                        ctx.deps().plugin().getComponentLogger()
                                .warn("稀有度应用失败（称号 {}）", titleId, failure);
                        feedback("menu.admin.editor.failed");
                    } else {
                        feedback("menu.admin.rarity.applied");
                    }
                    finish();
                }));
    }

    /** 收尾：若仍停留在本菜单则弹栈重建编辑器（栈空关闭）。 */
    private void finish() {
        if (ctx.player().isOnline()
                && ctx.session().currentSpec() instanceof MenuSpec.RaritySelect) {
            back();
        }
    }
}
