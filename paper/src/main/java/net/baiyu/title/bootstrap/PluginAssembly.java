package net.baiyu.title.bootstrap;

import net.baiyu.title.BaiYuTitle;
import net.baiyu.title.api.event.TitlePrePurchaseEvent;
import net.baiyu.title.api.event.TradePreAcceptEvent;
import net.baiyu.title.cache.PlayerProfileCache;
import net.baiyu.title.cache.TitleCatalogCache;
import net.baiyu.title.chat.ChatListener;
import net.baiyu.title.command.CommandRoot;
import net.baiyu.title.config.ConfigHandler;
import net.baiyu.title.database.handler.DatabaseHandler;
import net.baiyu.title.gui.core.MenuDeps;
import net.baiyu.title.gui.core.MenuFactory;
import net.baiyu.title.hook.Hook;
import net.baiyu.title.hook.vault.VaultEconomyGateway;
import net.baiyu.title.hook.vault.VaultHook;
import net.baiyu.title.input.InputSessionManager;
import net.baiyu.title.listener.ListenerHandler;
import net.baiyu.title.listener.PlayerJoinListener;
import net.baiyu.title.listener.PlayerQuitListener;
import net.baiyu.title.repository.TableNames;
import net.baiyu.title.repository.TransactionRunner;
import net.baiyu.title.repository.jdbc.JdbcAuditRepository;
import net.baiyu.title.repository.jdbc.JdbcCoinRepository;
import net.baiyu.title.repository.jdbc.JdbcGroupRepository;
import net.baiyu.title.repository.jdbc.JdbcOrderRepository;
import net.baiyu.title.repository.jdbc.JdbcOwnershipRepository;
import net.baiyu.title.repository.jdbc.JdbcRedeemRepository;
import net.baiyu.title.repository.jdbc.JdbcSlotRepository;
import net.baiyu.title.repository.jdbc.JdbcTitleRepository;
import net.baiyu.title.repository.jdbc.JdbcTradeRepository;
import net.baiyu.title.service.AdminTitleService;
import net.baiyu.title.service.CoinService;
import net.baiyu.title.service.EconomyGateway;
import net.baiyu.title.service.ExpiryService;
import net.baiyu.title.service.GrantEventBridge;
import net.baiyu.title.service.NoEconomyGateway;
import net.baiyu.title.service.OwnershipService;
import net.baiyu.title.service.PlayerLifecycleService;
import net.baiyu.title.service.PurchaseFlow;
import net.baiyu.title.service.PurchaseService;
import net.baiyu.title.service.RedeemCodeFlow;
import net.baiyu.title.service.ShopQueryService;
import net.baiyu.title.service.TitleCatalogService;
import net.baiyu.title.service.TradeFlow;
import net.baiyu.title.service.TradeService;
import net.baiyu.title.service.TradeViewService;
import net.baiyu.title.service.WardrobeService;
import net.baiyu.title.service.WardrobeViewService;
import net.baiyu.title.service.executor.DbExecutor;
import net.baiyu.title.service.purchase.EligibilityEngine;
import net.baiyu.title.service.purchase.RecoveryPolicy;
import net.baiyu.title.translation.Lang;
import net.baiyu.title.translation.TranslationHandler;

import java.time.LocalDate;

/**
 * 组合根：全插件唯一 new 业务对象的地方。
 *
 * <p>装配顺序：基础设施（执行器/事务/表名）→ 九仓储 → 两缓存 → 服务族
 * （羁绊/组链在前，各授予入口统一注入"Post 事件桥接 + 羁绊检测"组合钩子）→
 * 表现层公用件（语言门面/定时调度/重载协调）→ 启动缓存预热。
 * 一切依赖构造注入，产出 {@link Assembled} 交主类持有；
 * 表现层类零 {@code getInstance()}。</p>
 *
 * <p>体量控制：本类只做编排，切片装配下沉到同包协作者——
 * {@link GroupWiring}（组/羁绊链）、{@link RedeemWiring}（兑换/自定义链）、
 * {@link GuiWiring}（GUI 基座与渲染门面）、{@link ChatWiring}（聊天/PAPI 链）、
 * {@link CommandWiring}（命令树）、{@link SlotLimitResolverFactory}、
 * {@link CatalogPreloader}。</p>
 *
 * <p>预热失败（数据库读不通）异常上抛，由主类停用插件——
 * 目录缓存是聊天渲染与补全的零 IO 前提，宁缺毋残。</p>
 */
public final class PluginAssembly {

    private PluginAssembly() {
    }

    /**
     * 执行业务装配（主类 onEnable、平台组件就绪且数据库启动成功之后调用）。
     *
     * @param plugin             插件实例
     * @param configHandler      配置处理器（平台组件，主类管理生命周期）
     * @param translationHandler 翻译生命周期（平台组件，主类管理生命周期）
     * @param databaseHandler    数据库生命周期（平台组件，主类管理生命周期；此处只取连接）
     * @return 装配产物
     * @throws RuntimeException 表前缀非法或缓存预热失败时（主类捕获后停用插件）
     */
    public static Assembled assemble(BaiYuTitle plugin, ConfigHandler configHandler,
                                     TranslationHandler translationHandler,
                                     DatabaseHandler databaseHandler) {
        // ---- 基础设施 ----
        final DbExecutor dbExecutor = new DbExecutor();
        final TransactionRunner txRunner = new TransactionRunner(databaseHandler::getConnection);
        final TableNames tables = new TableNames(
                configHandler.getDatabaseConfig().database.tablePrefix);

        // ---- 九仓储（SQL 只出现在 repository 层） ----
        final Assembled.Repositories repositories = new Assembled.Repositories(
                new JdbcTitleRepository(tables),
                new JdbcOwnershipRepository(tables),
                new JdbcSlotRepository(tables),
                new JdbcCoinRepository(tables),
                new JdbcOrderRepository(tables),
                new JdbcTradeRepository(tables),
                new JdbcGroupRepository(tables),
                new JdbcRedeemRepository(tables),
                new JdbcAuditRepository(tables));

        // ---- 两缓存 + 语言门面（Lang 无状态，提前构造供羁绊通知钩子取词） ----
        final TitleCatalogCache titleCatalogCache = new TitleCatalogCache();
        final PlayerProfileCache profileCache = new PlayerProfileCache();
        final Lang lang = new Lang(translationHandler);

        // ---- 服务族 ----
        final TitleCatalogService titleCatalogService = new TitleCatalogService(
                repositories.titles(), repositories.slots(), txRunner, dbExecutor,
                titleCatalogCache, profileCache);
        // 组/羁绊链：各授予入口统一指向组合钩子（Post 事件桥接 + 羁绊检测）
        final GrantEventBridge grantEvents = new GrantEventBridge(plugin);
        final GroupWiring groupWiring = GroupWiring.wire(plugin, lang, repositories,
                txRunner, dbExecutor, titleCatalogCache, profileCache, grantEvents);
        final OwnershipService.PostGrantHook grantHook = groupWiring.grantHook();
        final OwnershipService ownershipService = new OwnershipService(
                repositories.ownerships(), repositories.slots(), repositories.audits(),
                txRunner, dbExecutor, titleCatalogCache, profileCache, grantHook, grantEvents);
        final WardrobeService.SlotLimitResolver slotLimitResolver =
                SlotLimitResolverFactory.create(configHandler);
        final WardrobeService wardrobeService = new WardrobeService(
                repositories.ownerships(), repositories.slots(), txRunner, dbExecutor,
                titleCatalogCache, profileCache, slotLimitResolver, groupWiring.accessBridge());
        final CoinService coinService = new CoinService(
                repositories.coins(), txRunner, dbExecutor, profileCache);
        final ExpiryService expiryService = new ExpiryService(
                repositories.ownerships(), repositories.slots(), repositories.audits(),
                txRunner, dbExecutor, profileCache);

        // ---- GUI 基座 + 渲染门面（切片见 GuiWiring） ----
        final GuiWiring gui = GuiWiring.wire(plugin, lang, configHandler);

        // ---- 经济门面：Vault 在场走桥接、缺失走降级实现 ----
        final EconomyGateway economyGateway = Hook.Vault.isLoaded()
                ? new VaultEconomyGateway((VaultHook) Hook.Vault.get())
                : new NoEconomyGateway();

        // ---- 购买链：订单状态机服务 + paper 侧主线程支付编排；
        // 购买前闸门主线程发可取消的 TitlePrePurchaseEvent（事件族） ----
        final PurchaseService purchaseService = new PurchaseService(
                repositories.orders(), repositories.titles(), repositories.ownerships(),
                repositories.coins(), repositories.audits(), txRunner, dbExecutor,
                titleCatalogCache, profileCache, grantHook,
                RecoveryPolicy.fromString(configHandler.getConfig().purchase.recovery),
                () -> configHandler.getConfig().customTitle);
        final PurchaseFlow purchaseFlow = new PurchaseFlow(
                plugin, purchaseService, economyGateway, lang, gui.titleItemRenderer(),
                (buyer, titleId) -> new TitlePrePurchaseEvent(buyer, titleId).callEvent());
        // 兑换 / 自定义称号链：兑换服务授予钩子同指组合钩子
        final RedeemWiring redeemWiring = RedeemWiring.wire(plugin, lang, repositories,
                txRunner, dbExecutor, titleCatalogCache, profileCache, grantHook,
                purchaseService, economyGateway, configHandler, gui.itemNameRegistry(),
                gui.titleItemRenderer());

        // ---- 视图门面（装扮 + 商店查询）+ 输入会话 ----
        final WardrobeViewService wardrobeViewService = new WardrobeViewService(
                wardrobeService, profileCache, titleCatalogCache, slotLimitResolver);
        final ShopQueryService shopQueryService = new ShopQueryService(
                titleCatalogCache, profileCache, new EligibilityEngine(), economyGateway,
                gui.itemNameRegistry()::displayComponent, () -> configHandler.getConfig().rarities,
                LocalDate::now);
        final InputSessionManager inputSessionManager = new InputSessionManager(
                plugin, gui.menuRouter(), gui.sessionManager(), lang);
        final RedeemCodeFlow redeemCodeFlow = new RedeemCodeFlow(
                plugin, inputSessionManager, redeemWiring.redeemService(), lang,
                titleCatalogCache, gui.titleItemRenderer());

        // ---- 管理端编辑门面：编辑器 19 项操作适配目录服务，
        // 兼收管理玩家仓库收回与购买者列表查询 ----
        final AdminTitleService adminTitleService = new AdminTitleService(titleCatalogService,
                ownershipService, repositories.orders(), txRunner, dbExecutor);

        // ---- 交易链：状态机服务 + 视图门面 + 主线程编排；
        // 接受前闸门主线程发可取消的 TradePreAcceptEvent（事件族） ----
        final TradeService tradeService = new TradeService(
                repositories.trades(), repositories.ownerships(), repositories.slots(),
                repositories.audits(), txRunner, dbExecutor, titleCatalogCache, profileCache,
                () -> configHandler.getConfig().trade, grantHook);
        final TradeViewService tradeViewService = new TradeViewService(
                tradeService, profileCache, titleCatalogCache, configHandler::getConfig, lang::raw);
        final TradeFlow tradeFlow = new TradeFlow(plugin, tradeService, lang,
                (acceptor, tradeId) -> new TradePreAcceptEvent(acceptor, tradeId).callEvent());

        // ---- 聊天切片 + PAPI 解析（切片见 ChatWiring）：全路径零 DB IO ----
        final ChatWiring chat = ChatWiring.wire(plugin, profileCache, titleCatalogCache,
                configHandler, lang);

        // ---- 菜单装配（router ↔ factory 经 bindFactory 打破循环） ----
        final MenuDeps menuDeps = new MenuDeps(plugin, gui.menuRouter(), gui.sessionManager(),
                lang, configHandler, gui.guiItemFactory(), gui.menuButtons(),
                gui.titleItemRenderer(), gui.itemNameRegistry(), wardrobeViewService,
                shopQueryService, purchaseFlow, inputSessionManager, adminTitleService,
                tradeViewService, tradeFlow, redeemCodeFlow, redeemWiring.customFlow(),
                chat.personalInfoService(), chat.tabDisplayService(), gui.cooldownGate());
        gui.menuRouter().bindFactory(new MenuFactory(menuDeps));

        final TaskScheduler taskScheduler = new TaskScheduler(
                plugin, configHandler, expiryService, gui.sessionManager(), tradeService,
                chat.tabDisplayService());
        // 重载链在目录重建后顺带刷新组名快照（同属"只刷缓存"白名单；计数仍报目录条数）
        final ReloadCoordinator reloadCoordinator = new ReloadCoordinator(
                plugin, configHandler, translationHandler, inputSessionManager, gui.menuRouter(),
                gui.sessionManager(), gui.itemNameRegistry(), chat.tabDisplayService(),
                () -> CatalogPreloader.preload(dbExecutor, txRunner, repositories.titles(),
                                titleCatalogCache)
                        .thenCompose(count -> groupWiring.refreshNames().thenApply(v -> count)));

        // ---- 玩家生命周期与监听器：登录 权限快照+栏位上限→组对账→
        // 预载（含交易计数）串行；退出先清 GUI 会话，延迟驱逐档案与组权限快照 ----
        final PlayerLifecycleService playerLifecycleService = new PlayerLifecycleService(
                plugin, dbExecutor, txRunner, repositories.ownerships(), repositories.slots(),
                repositories.coins(), repositories.trades(), expiryService, profileCache,
                groupWiring.groupService(), groupWiring.accessBridge(), slotLimitResolver);
        final ListenerHandler listenerHandler = new ListenerHandler(plugin, configHandler,
                new ChatListener(configHandler, chat.chatDisplayService()),
                new PlayerJoinListener(plugin, playerLifecycleService, chat.tabDisplayService()),
                new PlayerQuitListener(playerLifecycleService, gui.sessionManager(),
                        inputSessionManager, gui.cooldownGate()),
                inputSessionManager, redeemWiring.cardListener());

        // ---- 命令树（切片见 CommandWiring；注册由主类驱动） ----
        final CommandRoot commandRoot = CommandWiring.wire(plugin, lang, gui.menuRouter(),
                reloadCoordinator, titleCatalogService, ownershipService, wardrobeService,
                coinService, purchaseService, tradeService, tradeViewService, groupWiring,
                redeemWiring, redeemCodeFlow, titleCatalogCache, gui.titleItemRenderer());

        // ---- 启动缓存预热（读线程全量装载，同步等待；失败上抛停用插件） ----
        final int titleCount = CatalogPreloader.preload(
                dbExecutor, txRunner, repositories.titles(), titleCatalogCache).join();
        plugin.getComponentLogger().info("称号目录缓存预热完成，共装载 {} 条（含软删行）。", titleCount);
        // 组名快照预热 + 组权限节点批量注册（补全与登录快照的零 IO 前提）
        groupWiring.preheat(plugin.getComponentLogger());

        return new Assembled(dbExecutor, txRunner, tables, repositories,
                titleCatalogCache, profileCache,
                titleCatalogService, ownershipService, wardrobeService, coinService, expiryService,
                lang, taskScheduler, reloadCoordinator,
                chat.chatDisplayCache(), chat.chatDisplayService(), playerLifecycleService,
                listenerHandler, commandRoot, menuDeps, wardrobeViewService, economyGateway,
                shopQueryService, purchaseService, purchaseFlow, inputSessionManager,
                chat.placeholderResolver());
    }
}
