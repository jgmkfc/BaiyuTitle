package net.baiyu.title.gui.core;

import dev.triumphteam.gui.guis.BaseGui;
import dev.triumphteam.gui.guis.Gui;
import dev.triumphteam.gui.guis.GuiItem;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Objects;

/**
 * 菜单基类：模板方法 open / refresh + ESC 关闭仲裁（§3.5）。
 *
 * <p>生命周期：{@link #open()} = 异步线程 {@link #loadData()} 预取视图数据 →
 * 回主线程 build + {@link #populate(BaseGui)} + 打开背包。查询类服务方法
 * 只允许在 loadData 阶段调用（Bukkit API 仅主线程、JDBC 仅 DbExecutor 线程，
 * 两不相犯）。菜单不做业务判断：只渲染服务预计算的视图对象与转发点击。</p>
 *
 * <p>ESC 仲裁（§3.5）：closeGuiAction 统一挂在本基类——
 * navigating（菜单切换）或 expectingInput（进入输入会话）引发的关闭
 * 仅复位标记；否则视为玩家主动 ESC → {@link #onClosed()} + 清返回栈。</p>
 */
public abstract class AbstractMenu {

    /** 菜单上下文（玩家 + 依赖聚合 + 会话）。 */
    protected final MenuContext ctx;

    /**
     * 构造菜单。
     *
     * @param ctx 菜单上下文
     */
    protected AbstractMenu(MenuContext ctx) {
        this.ctx = Objects.requireNonNull(ctx, "菜单上下文不能为空");
    }

    /** @return 布局字符画 */
    protected abstract MenuLayout layout();

    /** @return 菜单标题（必须来自 Lang 构建） */
    protected abstract Component title();

    /**
     * 异步线程预取视图数据（服务查询走这里；默认无数据可取）。
     * <p>禁止触碰任何 Bukkit API。</p>
     */
    protected void loadData() {
    }

    /**
     * 主线程填充物品与点击回调。
     *
     * @param gui 已构建的 GUI（尚未打开）
     */
    protected abstract void populate(BaseGui gui);

    /** 玩家主动 ESC 关闭时回调（丢弃暂存等清理；默认无动作）。 */
    protected void onClosed() {
    }

    /** @return 本菜单的规格（与返回栈中的记录一致） */
    public abstract MenuSpec spec();

    /** @return 是否瞬态菜单（true → 导航离开时不入返回栈；确认菜单等） */
    public boolean isTransient() {
        return false;
    }

    /**
     * 打开菜单：异步 loadData → 主线程 build + populate + open。
     * <p>加载失败只告警并提示玩家，不打开残缺菜单；
     * 回主线程时玩家已离线则静默放弃。</p>
     */
    public final void open() {
        final Player player = ctx.player();
        ctx.session().touch();
        Bukkit.getScheduler().runTaskAsynchronously(ctx.deps().plugin(), () -> {
            try {
                loadData();
            } catch (Exception e) {
                ctx.deps().plugin().getComponentLogger()
                        .warn("菜单数据预取失败（玩家 {}，规格 {}）", player.getName(), spec(), e);
                Bukkit.getScheduler().runTask(ctx.deps().plugin(), () -> {
                    if (player.isOnline()) {
                        player.sendMessage(lang("menu.common.load-failed"));
                    }
                });
                return;
            }
            Bukkit.getScheduler().runTask(ctx.deps().plugin(), this::buildAndShow);
        });
    }

    /**
     * 刷新：重走 open 流程（重新 loadData 后原地重开，返回栈不动——
     * 栈由路由维护，open 自身不触栈）。
     */
    public final void refresh() {
        open();
    }

    /** 主线程：构建 GUI、填充、挂 ESC 仲裁并打开。 */
    private void buildAndShow() {
        final Player player = ctx.player();
        if (!player.isOnline()) {
            return;
        }
        final Gui gui = Gui.gui()
                .title(title())
                .rows(layout().rows())
                .disableAllInteractions()
                .create();
        gui.setCloseGuiAction(event -> handleClose());
        populate(gui);
        // 打开新背包会同步触发旧背包的 InventoryClose——以 navigating 括起，
        // 让旧菜单的关闭仲裁走「导航切换」分支而非「玩家主动 ESC」
        final GuiSession session = ctx.session();
        session.setNavigating(true);
        try {
            gui.open(player);
        } finally {
            session.setNavigating(false);
        }
    }

    /** ESC 关闭仲裁（§3.5）：导航 / 输入引发的关闭只复位标记。 */
    private void handleClose() {
        final GuiSession session = ctx.session();
        session.touch();
        if (session.consumeNavigating() || session.isExpectingInput()) {
            return;
        }
        onClosed();
        session.clearNavigation();
    }

    // ---- 子类工具 ----

    /**
     * 在布局全部边框位（'#'）铺边框物品。
     *
     * @param gui 目标 GUI
     */
    protected final void border(BaseGui gui) {
        button(gui, MenuLayout.BORDER, ctx.deps().buttons().border());
    }

    /**
     * 把物品放到某角色的全部槽位。
     *
     * @param gui  目标 GUI
     * @param role 布局角色字符
     * @param item GUI 物品
     */
    protected final void button(BaseGui gui, char role, GuiItem item) {
        for (final int slot : layout().slots(role)) {
            gui.setItem(slot, item);
        }
    }

    /**
     * 取单行语言组件（{@code Lang.component} 便捷转发）。
     *
     * @param key       语言键
     * @param resolvers 占位符解析器
     * @return 组件
     */
    protected final Component lang(String key, TagResolver... resolvers) {
        return ctx.deps().lang().component(key, resolvers);
    }

    /**
     * 取多行语言组件（lore 用）。
     *
     * @param key       语言键
     * @param resolvers 占位符解析器
     * @return 组件列表
     */
    protected final List<Component> langList(String key, TagResolver... resolvers) {
        return ctx.deps().lang().list(key, resolvers);
    }

    /**
     * 跳回主线程执行（服务 future 在 DB 线程完成后回 UI 统一经此；
     * 插件停用中则静默丢弃）。
     *
     * @param task 主线程任务
     */
    protected final void runSync(Runnable task) {
        if (ctx.deps().plugin().isEnabled()) {
            Bukkit.getScheduler().runTask(ctx.deps().plugin(), task);
        }
    }

    /**
     * 装扮切换冷却闸门（config {@code title.toggles-cool-down}，0 = 关闭）。
     *
     * <p>只在会改变佩戴状态的入口调用（栏位开关 / 选择菜单应用），
     * 翻页、筛选、打开详情等纯读操作不得调用。冷却中直接给玩家提示并返回
     * {@code false}，调用方应立即中止本次操作（不下发任何写请求）。</p>
     *
     * @return {@code true} 放行；{@code false} 仍在冷却（已提示玩家）
     */
    protected final boolean acquireToggleCooldown() {
        final int seconds = ctx.deps().configHandler().getConfig().title.togglesCoolDown;
        final long remainingMillis = ctx.deps().cooldownGate()
                .tryAcquire(ctx.player().getUniqueId(), seconds);
        if (remainingMillis <= 0) {
            return true;
        }
        final long remainingSeconds = Math.max(1, (remainingMillis + 999) / 1000);
        feedback("menu.common.result.cooldown",
                Placeholder.parsed("seconds", Long.toString(remainingSeconds)));
        return false;
    }

    /**
     * 请求刷新当前玩家的 Tab 列表名（装扮变更成功后调用；
     * {@code title.is-tab} 关闭时为空操作）。
     */
    protected final void refreshTabDisplay() {
        ctx.deps().tabDisplay().requestRefresh(ctx.player().getUniqueId());
    }

    /**
     * 给玩家发一条带插件前缀的提示消息（占位提示 / 操作反馈）。
     *
     * @param key       语言键
     * @param resolvers 占位符解析器
     */
    protected final void feedback(String key, TagResolver... resolvers) {
        ctx.player().sendMessage(
                ctx.deps().lang().component("prefix").append(lang(key, resolvers)));
    }

    /**
     * 顶栏 P 键：返回服务器主菜单按钮（§11 裁决⑫；指令取 config
     * {@code menu.main-menu-command}，未配置时点击只提示）。
     *
     * @return 返回主菜单按钮
     */
    protected final GuiItem mainMenuButton() {
        return ctx.deps().buttons().mainMenu(event -> {
            final String command = ctx.deps().configHandler().getConfig().menu.mainMenuCommand;
            if (command == null || command.isBlank()) {
                feedback("menu.common.main-menu.not-configured");
                return;
            }
            ctx.deps().router().closeAll(ctx.player());
            final String trimmed = command.trim();
            ctx.player().performCommand(trimmed.startsWith("/") ? trimmed.substring(1) : trimmed);
        });
    }

    /**
     * 导航到新菜单（当前菜单按路由语义压栈）。
     *
     * @param spec 目标菜单规格
     */
    protected final void navigate(MenuSpec spec) {
        ctx.deps().router().open(ctx.player(), spec);
    }

    /** 返回上一级（弹栈重建；栈空关闭背包）。 */
    protected final void back() {
        ctx.deps().router().back(ctx.player());
    }

    /**
     * 弹出通用确认菜单（瞬态，不入返回栈）。
     *
     * @param request 确认请求
     */
    protected final void confirm(ConfirmRequest request) {
        navigate(new MenuSpec.Confirm(request));
    }
}
