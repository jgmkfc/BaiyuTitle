package net.baiyu.title.bootstrap;

import net.baiyu.title.BaiYuTitle;
import net.baiyu.title.cache.PlayerProfileCache;
import net.baiyu.title.cache.TitleCatalogCache;
import net.baiyu.title.config.ConfigHandler;
import net.baiyu.title.gui.render.ItemNameRegistry;
import net.baiyu.title.gui.render.TitleItemRenderer;
import net.baiyu.title.listener.RedeemCardListener;
import net.baiyu.title.repository.TransactionRunner;
import net.baiyu.title.service.CustomTitleFlow;
import net.baiyu.title.service.EconomyGateway;
import net.baiyu.title.service.OwnershipService;
import net.baiyu.title.service.PurchaseService;
import net.baiyu.title.service.RedeemCardItems;
import net.baiyu.title.service.RedeemService;
import net.baiyu.title.service.executor.DbExecutor;
import net.baiyu.title.translation.Lang;

/**
 * 兑换 / 自定义称号链装配切片（{@link PluginAssembly} 的内部协作者）。
 *
 * <p>集中构造：{@link RedeemService}（授予钩子与其他授予入口同一组合钩子——
 * 兑换所得同样发 Post 事件并触发羁绊检测）→ 称号卡物品编解码器
 * → 称号卡右键兑换监听器 → 自定义称号执行链（付款选项预计算 + 创建编排）。</p>
 *
 * @param redeemService 兑换服务（兑换码 / 称号卡唯一入口）
 * @param customFlow    自定义称号执行链（MenuDeps 注入位）
 * @param cardItems     称号卡物品编解码器（发卡命令与监听器共用 PDC 键）
 * @param cardListener  称号卡右键兑换监听器（ListenerHandler 注册位）
 */
record RedeemWiring(
        RedeemService redeemService,
        CustomTitleFlow customFlow,
        RedeemCardItems cardItems,
        RedeemCardListener cardListener) {

    /**
     * 构造整条兑换 / 自定义称号链（依赖与 PluginAssembly 同源，主线程调用）。
     *
     * @param plugin            插件实例
     * @param lang              语言门面
     * @param repositories      仓储聚合
     * @param txRunner          事务执行器
     * @param dbExecutor        数据库执行器
     * @param titleCatalogCache 称号目录缓存
     * @param profileCache      玩家档案缓存
     * @param grantHook         授予提交后钩子组合（事件桥接 + 羁绊检测）
     * @param purchaseService   购买服务（CUSTOM 订单入口）
     * @param economyGateway    经济门面
     * @param configHandler     配置处理器（custom-title 段）
     * @param itemNames         物品名本地化注册表
     * @param renderer          称号物品渲染门面
     * @return 装配好的兑换 / 自定义称号链
     */
    static RedeemWiring wire(BaiYuTitle plugin, Lang lang, Assembled.Repositories repositories,
                             TransactionRunner txRunner, DbExecutor dbExecutor,
                             TitleCatalogCache titleCatalogCache, PlayerProfileCache profileCache,
                             OwnershipService.PostGrantHook grantHook, PurchaseService purchaseService,
                             EconomyGateway economyGateway, ConfigHandler configHandler,
                             ItemNameRegistry itemNames, TitleItemRenderer renderer) {
        final RedeemService redeemService = new RedeemService(
                repositories.redeems(), repositories.ownerships(), repositories.audits(),
                txRunner, dbExecutor, titleCatalogCache, profileCache, grantHook);
        final CustomTitleFlow customFlow = new CustomTitleFlow(plugin, purchaseService,
                economyGateway, profileCache, configHandler::getConfig, lang,
                itemNames::displayComponent);
        final RedeemCardItems cardItems = new RedeemCardItems(plugin, lang);
        final RedeemCardListener cardListener = new RedeemCardListener(plugin, redeemService,
                cardItems, lang, titleCatalogCache, renderer);
        return new RedeemWiring(redeemService, customFlow, cardItems, cardListener);
    }
}
