package net.baiyu.title.gui.admin;

import dev.triumphteam.gui.guis.BaseGui;
import net.baiyu.title.domain.title.TitleCategory;
import net.baiyu.title.gui.core.AbstractMenu;
import net.baiyu.title.gui.core.MenuContext;
import net.baiyu.title.gui.core.MenuLayout;
import net.baiyu.title.gui.core.MenuSpec;
import net.baiyu.title.service.TitleEditorView;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * 购买类型选择菜单。
 *
 * <p>H 免费 / I 金币 / J 称号币 / K 物品 / L 权限 / M 羁绊 / N 组称号：
 * 点击切换暂挂启用（附魔 = 启用；免费/羁绊/组互斥清空其他——规则内聚
 * {@link AdminEditState#toggle}）；暂挂不生效，A 应用才落库（服务侧清价联动：
 * 被取消类型的价格条件整条清除）；B 取消丢弃暂挂。羁绊/组称号/自定义的类型
 * 由种类决定，普通称号亦不可切换为羁绊/组——越界点击仅提示。</p>
 *
 * <p>返回语义：本菜单经编辑器 navigate 进入（编辑器已压栈），应用 / 取消
 * 以 {@code router.back()} 弹栈重建编辑器——效果即「replace 回 AdminEditor」
 * 且返回栈不留重复层。ESC 关闭同取消丢弃暂挂。</p>
 */
public final class BuyTypeSelectMenu extends AbstractMenu {

    /** 布局字符画。 */
    private static final MenuLayout LAYOUT = MenuLayout.of(
            "#########",
            "#HIJKLMN#",
            "##A###B##");

    /** 角色 → 购买类型（布局 H~N 顺序）。 */
    private static final char[] ROLES = {'H', 'I', 'J', 'K', 'L', 'M', 'N'};

    /** 角色对应物品（业务表格：DIRT/金锭/绿宝石/草方块/命名牌/蛛网/钻石剑）。 */
    private static final Material[] MATERIALS = {
            Material.DIRT, Material.GOLD_INGOT, Material.EMERALD, Material.GRASS_BLOCK,
            Material.NAME_TAG, Material.COBWEB, Material.DIAMOND_SWORD};

    /** 目标称号 id。 */
    private final int titleId;

    /** 编辑器视图（loadData 异步装载；称号缺失时为 null）。 */
    private @Nullable TitleEditorView view;

    /** 应用中防双击标记。 */
    private boolean applying;

    /**
     * 构造购买类型选择菜单（主线程：以现状购买类型播种会话暂挂）。
     *
     * @param ctx     菜单上下文
     * @param titleId 目标称号 id
     */
    public BuyTypeSelectMenu(MenuContext ctx, int titleId) {
        super(ctx);
        this.titleId = titleId;
        final AdminEditState existing = ctx.session().adminEdit();
        if (existing == null || existing.titleId() != titleId) {
            ctx.deps().adminViews().editorView(titleId).ifPresent(v ->
                    ctx.session().beginAdminEdit(new AdminEditState(titleId, v.buyTypes())));
        }
    }

    @Override
    protected MenuLayout layout() {
        return LAYOUT;
    }

    @Override
    protected Component title() {
        return lang("menu.admin.buytype.title");
    }

    @Override
    public MenuSpec spec() {
        return new MenuSpec.BuyTypeSelect(titleId);
    }

    /** 玩家主动 ESC：丢弃编辑暂挂（已落库类型不动）。 */
    @Override
    protected void onClosed() {
        ctx.session().clearAdminEdit();
    }

    /** 异步装载编辑器视图（种类判定与缺失降级用）。 */
    @Override
    protected void loadData() {
        view = ctx.deps().adminViews().editorView(titleId).orElse(null);
    }

    @Override
    protected void populate(BaseGui gui) {
        border(gui);
        gui.setItem(LAYOUT.slot('B'), ctx.deps().buttons().cancel(event -> finish()));
        if (view == null || ctx.session().adminEdit() == null) {
            feedback("menu.admin.editor.missing");
            return;
        }
        final BuyType[] types = BuyType.values();
        for (int i = 0; i < ROLES.length; i++) {
            paintOption(gui, ROLES[i], MATERIALS[i], types[i]);
        }
        gui.setItem(LAYOUT.slot('A'), ctx.deps().buttons().apply(event -> apply()));
    }

    /** 放置单个类型按钮（附魔 = 暂挂启用），点击切换或按种类锁定提示。 */
    private void paintOption(BaseGui gui, char role, Material material, BuyType type) {
        final AdminEditState state = ctx.session().adminEdit();
        final String stateKey = state != null && state.isEnabled(type)
                ? "menu.admin.buytype.state.on" : "menu.admin.buytype.state.off";
        gui.setItem(LAYOUT.slot(role), ctx.deps().itemFactory().item(material,
                lang("menu.admin.buytype.option." + type.name().toLowerCase(Locale.ROOT)),
                langList("menu.admin.buytype.lore",
                        Placeholder.component("state", lang(stateKey))),
                state != null && state.isEnabled(type), 1, event -> toggle(type)));
    }

    /** 切换暂挂：种类锁定（非普通称号 / 普通称号点羁绊与组）仅提示。 */
    private void toggle(BuyType type) {
        final AdminEditState state = ctx.session().adminEdit();
        if (view == null || state == null) {
            return;
        }
        final boolean allowed = view.title().category() == TitleCategory.NORMAL
                && type != BuyType.BOND && type != BuyType.GROUP;
        if (!allowed) {
            feedback("menu.admin.buytype.locked");
            return;
        }
        state.toggle(type);
        refresh();
    }

    /** A 应用：空集拦截 → 服务清价联动落库 → 丢弃暂挂并弹栈重建编辑器。 */
    private void apply() {
        final AdminEditState state = ctx.session().adminEdit();
        if (state == null || applying) {
            return;
        }
        if (state.pending().isEmpty()) {
            feedback("menu.admin.buytype.empty");
            return;
        }
        applying = true;
        ctx.deps().adminViews().setBuyTypes(titleId, state.pending())
                .whenComplete((result, failure) -> runSync(() -> {
                    applying = false;
                    if (failure != null) {
                        ctx.deps().plugin().getComponentLogger()
                                .warn("购买类型应用失败（称号 {}）", titleId, failure);
                        feedback("menu.admin.editor.failed");
                    } else {
                        feedback("menu.admin.buytype.applied");
                    }
                    finish();
                }));
    }

    /** 收尾：丢弃暂挂，若仍停留在本菜单则弹栈重建编辑器（栈空关闭）。 */
    private void finish() {
        ctx.session().clearAdminEdit();
        if (ctx.player().isOnline()
                && ctx.session().currentSpec() instanceof MenuSpec.BuyTypeSelect) {
            back();
        }
    }
}
