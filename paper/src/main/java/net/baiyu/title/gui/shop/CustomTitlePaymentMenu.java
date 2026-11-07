package net.baiyu.title.gui.shop;

import dev.triumphteam.gui.guis.BaseGui;
import net.baiyu.title.domain.condition.ConditionType;
import net.baiyu.title.gui.core.AbstractMenu;
import net.baiyu.title.gui.core.MenuContext;
import net.baiyu.title.gui.core.MenuLayout;
import net.baiyu.title.gui.core.MenuSpec;
import net.baiyu.title.input.AnvilInputRequest;
import net.baiyu.title.input.InputValidators;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;

import java.util.List;

/**
 * 自定义称号付款菜单（#HZIZJZK# 布局）。
 *
 * <p>H 金锭 / I 绿宝石 / J 草方块 / K 命令方块 = 付款方式单选（附魔 = 已选；
 * 不可用选项置灰 BARRIER——Vault 缺失 / 权限未配置 / 材质非法）；
 * A 绿玻璃 → 铁砧命名（{@code openAnvilInput}，长度红线 50 码点预检）；
 * B 纸 → 聊天输出 lang {@code custom-title.notice} 公告说明；C → back()。</p>
 *
 * <p>铁砧衔接（返回栈保持的关键）：命名完成回调只把原始名称暂存进
 * {@link CustomTitleDraft}，输入会话统一 replace 回本菜单，重建时
 * {@link #forwardPendingName} 消费暂存并自动前送确认菜单
 * （{@link CustomTitleConfirm}）；确认取消 back() 自然回到本菜单，
 * C / ESC 再 back() 回到商店 / 仓库 / 装扮入口。</p>
 */
public final class CustomTitlePaymentMenu extends AbstractMenu {

    /** 布局字符画。 */
    private static final MenuLayout LAYOUT = MenuLayout.of(
            "#########",
            "#HZIZJZK#",
            "##A#B#C##");

    /** 名称输入的最大码点数（最高 50 汉字）。 */
    private static final int NAME_MAX_CODE_POINTS = 50;

    /** 付款选项视图（构造时主线程经 CustomTitleFlow 预计算，H/I/J/K 序）。 */
    private final List<PaymentOptionView> options;

    /**
     * 构造自定义称号付款菜单（主线程：收集付款选项）。
     *
     * @param ctx 菜单上下文
     */
    public CustomTitlePaymentMenu(MenuContext ctx) {
        super(ctx);
        this.options = ctx.deps().customFlow().options(ctx.player());
    }

    @Override
    protected MenuLayout layout() {
        return LAYOUT;
    }

    @Override
    protected Component title() {
        return lang("menu.custom.title");
    }

    @Override
    public MenuSpec spec() {
        return new MenuSpec.CustomTitle();
    }

    /** 玩家主动 ESC：丢弃创建草稿（付款选择与命名回执一并放弃）。 */
    @Override
    protected void onClosed() {
        ctx.session().clearCustomDraft();
    }

    @Override
    protected void populate(BaseGui gui) {
        border(gui);
        paintOption(gui, 'H', ConditionType.VAULT, Material.GOLD_INGOT, "vault");
        paintOption(gui, 'I', ConditionType.COIN, Material.EMERALD, "coin");
        paintOption(gui, 'J', ConditionType.ITEM, Material.GRASS_BLOCK, "item");
        paintOption(gui, 'K', ConditionType.PERMISSION, Material.COMMAND_BLOCK, "permission");
        gui.setItem(LAYOUT.slot('A'), ctx.deps().itemFactory().item(
                Material.LIME_STAINED_GLASS_PANE,
                lang("menu.custom.confirm-select.name"),
                langList("menu.custom.confirm-select.lore"),
                event -> beginNaming()));
        gui.setItem(LAYOUT.slot('B'), ctx.deps().itemFactory().item(Material.PAPER,
                lang("menu.custom.notice-button.name"),
                langList("menu.custom.notice-button.lore"),
                event -> showNotice()));
        gui.setItem(LAYOUT.slot('C'), ctx.deps().buttons().cancel(event -> back()));
        forwardPendingName();
    }

    /** 放置单个付款选项（附魔 = 已选；不可用置灰 BARRIER）。 */
    private void paintOption(BaseGui gui, char role, ConditionType payment,
                             Material material, String key) {
        final PaymentOptionView option = option(payment);
        final Component name = lang("menu.custom.option." + key + ".name");
        if (!option.available()) {
            gui.setItem(LAYOUT.slot(role), ctx.deps().itemFactory().item(Material.BARRIER,
                    name, langList("menu.custom.option.unavailable-lore"),
                    event -> feedback("menu.custom.option-unavailable")));
            return;
        }
        final boolean selected = ctx.session().customDraft().payment() == payment;
        gui.setItem(LAYOUT.slot(role), ctx.deps().itemFactory().item(material, name,
                langList("menu.custom.option.lore",
                        Placeholder.component("value", option.value())),
                selected, 1, event -> {
                    ctx.session().customDraft().setPayment(payment);
                    refresh();
                }));
    }

    /** A 键：未选付款方式先提示；已选则进入铁砧命名（完成回执走草稿暂存）。 */
    private void beginNaming() {
        if (ctx.session().customDraft().payment() == null) {
            feedback("menu.custom.select-first");
            return;
        }
        final var session = ctx.session();
        ctx.deps().inputs().openAnvilInput(ctx.player(), new AnvilInputRequest(
                "menu.custom.anvil-title",
                InputValidators.length(1, NAME_MAX_CODE_POINTS),
                name -> session.customDraft().stashPendingName(name),
                () -> {
                },
                new MenuSpec.CustomTitle()));
    }

    /**
     * 命名回执自动前送：菜单重建时消费草稿暂存的名称，下一 tick 弹出创建
     * 确认菜单（导航经路由压栈，确认取消 back() 自然回到本菜单）。
     */
    private void forwardPendingName() {
        final CustomTitleDraft draft = ctx.session().customDraft();
        final String rawName = draft.consumePendingName();
        final ConditionType payment = draft.payment();
        if (rawName == null || payment == null) {
            return;
        }
        final PaymentOptionView option = option(payment);
        runSync(() -> {
            if (ctx.player().isOnline()) {
                confirm(CustomTitleConfirm.build(ctx, payment, option.value(),
                        rawName, this::settleBack));
            }
        });
    }

    /** 创建链终局：确认菜单仍在前台时返回本菜单（重建即拿到最新状态）。 */
    private void settleBack() {
        if (ctx.player().isOnline()
                && ctx.session().currentSpec() instanceof MenuSpec.Confirm) {
            back();
        }
    }

    /** B 键：聊天输出 language 中设置的自定义称号公告（多行，菜单保持打开）。 */
    private void showNotice() {
        for (final Component line : langList("custom-title.notice")) {
            ctx.player().sendMessage(line);
        }
    }

    /** 按付款方式取选项视图（options 恒含四项，取不到即编程错误）。 */
    private PaymentOptionView option(ConditionType payment) {
        return options.stream().filter(o -> o.payment() == payment).findFirst()
                .orElseThrow(() -> new IllegalStateException("付款选项缺失: " + payment));
    }
}
