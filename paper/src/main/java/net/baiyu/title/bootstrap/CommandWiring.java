package net.baiyu.title.bootstrap;

import net.baiyu.title.BaiYuTitle;
import net.baiyu.title.cache.TitleCatalogCache;
import net.baiyu.title.command.CommandRoot;
import net.baiyu.title.command.CommandSupport;
import net.baiyu.title.command.argument.GroupNameArgument;
import net.baiyu.title.command.argument.PlayerNameArgument;
import net.baiyu.title.command.argument.TitleIdArgument;
import net.baiyu.title.command.sub.AdminCommand;
import net.baiyu.title.command.sub.CoinCommand;
import net.baiyu.title.command.sub.GroupCommand;
import net.baiyu.title.command.sub.HelpCommand;
import net.baiyu.title.command.sub.MenuCommand;
import net.baiyu.title.command.sub.PlayerAdminCommand;
import net.baiyu.title.command.sub.RedeemCommand;
import net.baiyu.title.command.sub.ReloadCommand;
import net.baiyu.title.command.sub.TitleAdminCommand;
import net.baiyu.title.command.sub.TradeCommand;
import net.baiyu.title.gui.core.MenuRouter;
import net.baiyu.title.gui.render.TitleItemRenderer;
import net.baiyu.title.service.CoinService;
import net.baiyu.title.service.OwnershipService;
import net.baiyu.title.service.PurchaseService;
import net.baiyu.title.service.RedeemCodeFlow;
import net.baiyu.title.service.TitleCatalogService;
import net.baiyu.title.service.TradeService;
import net.baiyu.title.service.TradeViewService;
import net.baiyu.title.service.WardrobeService;
import net.baiyu.title.translation.Lang;

/**
 * 命令树装配切片（{@link PluginAssembly} 的内部协作者）。
 *
 * <p>集中构造：命令公共支撑（权限/取词/回执）→ 参数补全（玩家名 / 称号 ID /
 * 组名，全部零 DB IO 取自缓存）→ 十个子命令 → 根命令组装器。
 * 注册时机由主类驱动（CommandAPI onEnable 恰一次，reload 不重建）。</p>
 */
final class CommandWiring {

    private CommandWiring() {
    }

    /**
     * 构造根命令组装器（依赖与 PluginAssembly 同源，主线程调用）。
     *
     * @param plugin              插件实例
     * @param lang                语言门面
     * @param menuRouter          菜单路由（菜单类命令入口）
     * @param reloadCoordinator   白名单式重载协调器（/byt reload）
     * @param titleCatalogService 称号目录服务
     * @param ownershipService    所有权服务
     * @param wardrobeService     装扮服务
     * @param coinService         称号币服务
     * @param purchaseService     购买服务（/byt admin order resolve）
     * @param tradeService        交易服务
     * @param tradeViewService    交易视图门面
     * @param groupWiring         组/羁绊链切片（组服务与组名快照/权限注册器）
     * @param redeemWiring        兑换链切片（兑换服务与称号卡编解码器）
     * @param redeemCodeFlow      玩家兑换码输入与兑换反馈流程
     * @param titleCatalogCache   称号目录缓存（兑换命令渲染取名）
     * @param titleItemRenderer   称号物品渲染门面（称号卡渲染）
     * @return 根命令组装器
     */
    static CommandRoot wire(BaiYuTitle plugin, Lang lang, MenuRouter menuRouter,
                            ReloadCoordinator reloadCoordinator,
                            TitleCatalogService titleCatalogService,
                            OwnershipService ownershipService, WardrobeService wardrobeService,
                            CoinService coinService, PurchaseService purchaseService,
                            TradeService tradeService, TradeViewService tradeViewService,
                            GroupWiring groupWiring, RedeemWiring redeemWiring,
                            RedeemCodeFlow redeemCodeFlow,
                            TitleCatalogCache titleCatalogCache,
                            TitleItemRenderer titleItemRenderer) {
        final CommandSupport commandSupport = new CommandSupport(plugin, lang);
        final PlayerNameArgument playerNames = new PlayerNameArgument();
        final TitleIdArgument titleIds = new TitleIdArgument(titleCatalogService);
        return new CommandRoot(commandSupport, menuRouter,
                new HelpCommand(commandSupport),
                new ReloadCommand(commandSupport, reloadCoordinator),
                new CoinCommand(commandSupport, coinService, playerNames),
                new TitleAdminCommand(commandSupport, titleCatalogService, titleIds),
                new PlayerAdminCommand(commandSupport, titleCatalogService,
                        ownershipService, wardrobeService, playerNames, titleIds),
                new MenuCommand(commandSupport, menuRouter),
                new AdminCommand(commandSupport, menuRouter, purchaseService),
                new TradeCommand(commandSupport, menuRouter, tradeViewService, tradeService),
                new GroupCommand(commandSupport, groupWiring.groupService(),
                        groupWiring.groupNameCache(), groupWiring.permissionRegistrar(),
                        new GroupNameArgument(groupWiring.groupNameCache()), playerNames),
                new RedeemCommand(commandSupport, redeemCodeFlow,
                        redeemWiring.redeemService(), titleCatalogCache, titleItemRenderer,
                        redeemWiring.cardItems(), titleIds));
    }
}
