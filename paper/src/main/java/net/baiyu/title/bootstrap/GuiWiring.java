package net.baiyu.title.bootstrap;

import dev.triumphteam.gui.TriumphGui;
import net.baiyu.title.BaiYuTitle;
import net.baiyu.title.config.ConfigHandler;
import net.baiyu.title.gui.core.GuiItemFactory;
import net.baiyu.title.gui.core.GuiSessionManager;
import net.baiyu.title.gui.core.MenuButtons;
import net.baiyu.title.gui.core.MenuRouter;
import net.baiyu.title.gui.core.ToggleCooldownGate;
import net.baiyu.title.gui.render.AdminLoreComposer;
import net.baiyu.title.gui.render.ItemNameRegistry;
import net.baiyu.title.gui.render.LoreComposer;
import net.baiyu.title.gui.render.ShopLoreComposer;
import net.baiyu.title.gui.render.TitleItemRenderer;
import net.baiyu.title.translation.Lang;

/**
 * GUI 基座与渲染门面装配切片（{@link PluginAssembly} 的内部协作者）。
 *
 * <p>集中构造：TriumphGUI 静态初始化（须先于首个菜单构建）→ 会话管理器 →
 * 菜单路由（返回栈导航；factory 绑定由 PluginAssembly 在 MenuDeps 齐备后补做）→
 * 物品工厂 / 通用按钮 → lore 组装器族（通用 + 商店 + 管理）→ 称号物品渲染门面 →
 * 物品名本地化注册表（构造后立即 reload 装载当前语言）。</p>
 *
 * @param sessionManager    GUI 会话管理器（每玩家状态）
 * @param menuRouter        菜单路由（open/replace/back/closeAll）
 * @param guiItemFactory    GUI 物品工厂（1.21.11 数据组件单点封装）
 * @param menuButtons       通用按钮物品构造器
 * @param titleItemRenderer 称号物品渲染门面（shop/warehouse/admin/trade 四上下文）
 * @param itemNameRegistry  物品名本地化注册表（lang/items/&lt;lang&gt;.json）
 * @param cooldownGate      装扮切换冷却闸门（{@code title.toggles-cool-down} 消费方）
 */
record GuiWiring(
        GuiSessionManager sessionManager,
        MenuRouter menuRouter,
        GuiItemFactory guiItemFactory,
        MenuButtons menuButtons,
        TitleItemRenderer titleItemRenderer,
        ItemNameRegistry itemNameRegistry,
        ToggleCooldownGate cooldownGate) {

    /**
     * 构造整套 GUI 基座与渲染门面（依赖与 PluginAssembly 同源，主线程调用）。
     *
     * @param plugin        插件实例
     * @param lang          语言门面
     * @param configHandler 配置处理器（渲染与语言选择取词）
     * @return 装配好的 GUI 切片
     */
    static GuiWiring wire(BaiYuTitle plugin, Lang lang, ConfigHandler configHandler) {
        // TriumphGUI 静态初始化须先于首个菜单构建
        TriumphGui.init(plugin);
        final GuiSessionManager sessionManager = new GuiSessionManager();
        final MenuRouter menuRouter = new MenuRouter(sessionManager);
        final GuiItemFactory guiItemFactory = new GuiItemFactory();
        final MenuButtons menuButtons = new MenuButtons(guiItemFactory, lang);
        // 渲染门面：lore 组装（通用 + 商店 + 管理）/ 称号物品渲染 / 物品名本地化
        final LoreComposer loreComposer = new LoreComposer(lang::raw);
        final ShopLoreComposer shopLoreComposer = new ShopLoreComposer(lang::raw);
        final TitleItemRenderer titleItemRenderer = new TitleItemRenderer(
                loreComposer, shopLoreComposer,
                new AdminLoreComposer(lang::raw, loreComposer, shopLoreComposer),
                configHandler::getConfig);
        final ItemNameRegistry itemNameRegistry = new ItemNameRegistry(
                plugin.getDataFolder().toPath().resolve("lang").resolve("items"),
                () -> configHandler.getConfig().language, plugin.getLogger());
        itemNameRegistry.reload();
        return new GuiWiring(sessionManager, menuRouter, guiItemFactory, menuButtons,
                titleItemRenderer, itemNameRegistry, new ToggleCooldownGate());
    }
}
