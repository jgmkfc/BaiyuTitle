package net.baiyu.title.bootstrap;

import net.baiyu.title.cache.PlayerProfileCache;
import net.baiyu.title.cache.TitleCatalogCache;
import net.baiyu.title.chat.ChatDisplayCache;
import net.baiyu.title.chat.ChatDisplayService;
import net.baiyu.title.command.CommandRoot;
import net.baiyu.title.gui.core.MenuDeps;
import net.baiyu.title.input.InputSessionManager;
import net.baiyu.title.listener.ListenerHandler;
import net.baiyu.title.placeholder.InternalPlaceholderResolver;
import net.baiyu.title.repository.AuditRepository;
import net.baiyu.title.repository.CoinRepository;
import net.baiyu.title.repository.GroupRepository;
import net.baiyu.title.repository.OrderRepository;
import net.baiyu.title.repository.OwnershipRepository;
import net.baiyu.title.repository.RedeemRepository;
import net.baiyu.title.repository.SlotRepository;
import net.baiyu.title.repository.TableNames;
import net.baiyu.title.repository.TitleRepository;
import net.baiyu.title.repository.TradeRepository;
import net.baiyu.title.repository.TransactionRunner;
import net.baiyu.title.service.CoinService;
import net.baiyu.title.service.EconomyGateway;
import net.baiyu.title.service.ExpiryService;
import net.baiyu.title.service.OwnershipService;
import net.baiyu.title.service.PlayerLifecycleService;
import net.baiyu.title.service.PurchaseFlow;
import net.baiyu.title.service.PurchaseService;
import net.baiyu.title.service.ShopQueryService;
import net.baiyu.title.service.TitleCatalogService;
import net.baiyu.title.service.WardrobeService;
import net.baiyu.title.service.WardrobeViewService;
import net.baiyu.title.service.executor.DbExecutor;
import net.baiyu.title.translation.Lang;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

import java.time.Duration;

/**
 * 业务装配产物（{@link PluginAssembly#assemble} 的返回值）。
 *
 * <p>主类持有本 record 完成生命周期编排；后续里程碑的表现层组件
 * （命令、菜单、监听器）由 PluginAssembly 内部继续取用这里的服务引用，
 * 表现层类自身零 {@code getInstance()}。</p>
 *
 * @param dbExecutor        数据库执行器（写单线程 / 读小池）
 * @param txRunner          事务执行器（写事务 + 无事务读）
 * @param tableNames        表名拼接器（前缀取自 database.yml）
 * @param repositories      九仓储聚合
 * @param titleCatalogCache 称号目录缓存（启动已全量预热，含软删行）
 * @param profileCache      在线玩家档案缓存（登录预载接线在后续阶段）
 * @param titleCatalogService 称号目录服务（创建/编辑/软删）
 * @param ownershipService  所有权服务（授予/回收）
 * @param wardrobeService   装扮服务（栏位配置）
 * @param coinService       称号币服务（余额/给予/扣减）
 * @param expiryService     到期收敛服务（TaskScheduler 周期驱动）
 * @param lang              语言门面（玩家可见文本唯一入口）
 * @param taskScheduler     定时任务调度器（主类 onEnable 时 start）
 * @param reloadCoordinator 白名单式重载协调器（/byt reload 消费）
 * @param chatDisplayCache  聊天展示视图门面（纯内存，PAPI 后续复用）
 * @param chatDisplayService 聊天称号前缀构建服务（每消息构建一次）
 * @param playerLifecycleService 玩家生命周期服务（登录预载 / 退出延迟驱逐）
 * @param listenerHandler   监听器注册器（主类 onEnable 时 register）
 * @param commandRoot       根命令组装器（主类 onEnable 时 register，恰一次）
 * @param menuDeps          菜单依赖聚合（路由 / 会话 / 渲染器等经此取用）
 * @param wardrobeViewService 装扮视图门面（装扮 / 选择 / 仓库菜单数据源）
 * @param economyGateway    经济门面（Vault 桥接 / 缺失降级，组合根按在场性选择）
 * @param shopQueryService  商店查询门面（商店菜单族数据源）
 * @param purchaseService   购买服务（订单状态机唯一入口）
 * @param purchaseFlow      购买执行链（paper 侧主线程支付编排）
 * @param inputSessionManager 输入会话管理器（聊天 / 铁砧输入统一入口）
 * @param placeholderResolver PAPI 变量内部解析器（PAPIExpansion 与个人信息共用）
 */
public record Assembled(
        DbExecutor dbExecutor,
        TransactionRunner txRunner,
        TableNames tableNames,
        Repositories repositories,
        TitleCatalogCache titleCatalogCache,
        PlayerProfileCache profileCache,
        TitleCatalogService titleCatalogService,
        OwnershipService ownershipService,
        WardrobeService wardrobeService,
        CoinService coinService,
        ExpiryService expiryService,
        Lang lang,
        TaskScheduler taskScheduler,
        ReloadCoordinator reloadCoordinator,
        ChatDisplayCache chatDisplayCache,
        ChatDisplayService chatDisplayService,
        PlayerLifecycleService playerLifecycleService,
        ListenerHandler listenerHandler,
        CommandRoot commandRoot,
        MenuDeps menuDeps,
        WardrobeViewService wardrobeViewService,
        EconomyGateway economyGateway,
        ShopQueryService shopQueryService,
        PurchaseService purchaseService,
        PurchaseFlow purchaseFlow,
        InputSessionManager inputSessionManager,
        InternalPlaceholderResolver placeholderResolver) {

    /** 数据库执行器优雅关闭的总等待预算。 */
    private static final Duration DB_SHUTDOWN_TIMEOUT = Duration.ofSeconds(10);

    /**
     * 九仓储聚合（全部 JDBC 实现，SQL 只出现在 repository 层）。
     *
     * @param titles     称号仓储（V1）
     * @param ownerships 所有权仓储（V2）
     * @param slots      装扮栏位仓储（V2）
     * @param coins      称号币仓储（V3）
     * @param orders     购买订单仓储（V3）
     * @param trades     交易仓储（V4）
     * @param groups     玩家组仓储（V5）
     * @param redeems    兑换码仓储（V5）
     * @param audits     审计仓储（V5）
     */
    public record Repositories(
            TitleRepository titles,
            OwnershipRepository ownerships,
            SlotRepository slots,
            CoinRepository coins,
            OrderRepository orders,
            TradeRepository trades,
            GroupRepository groups,
            RedeemRepository redeems,
            AuditRepository audits) {
    }

    /**
     * 启动孤儿订单恢复（主类 onEnable 装配成功后调用一次六崩溃恢复）：
     * 写线程异步收敛 RESERVED（恒退款）/ PAYING（按 {@code purchase.recovery} 策略）
     * 孤儿订单，完成后按报告在控制台记数；存在 MANUAL 留置时告警提醒管理员裁决。
     * <p>不阻塞启动：恢复经条件迁移天然幂等，期间新购买请求互不干扰。</p>
     *
     * @param logger 控制台日志出口
     */
    public void recoverOrphanOrders(ComponentLogger logger) {
        purchaseService.recoverOrphans().whenComplete((report, failure) -> {
            if (failure != null) {
                logger.error("启动孤儿订单恢复失败，请检查数据库后重启。", failure);
                return;
            }
            if (report.resolvedCount() > 0) {
                logger.info("孤儿订单恢复完成：RESERVED 退款 {} 笔、PAYING 履约 {} 笔、"
                                + "PAYING 退款 {} 笔、转人工留置 {} 笔。",
                        report.reservedRefunded(), report.payingGranted(),
                        report.payingRefunded(), report.markedManual());
            }
            if (report.needsAttention()) {
                logger.warn("共有 {} 笔订单待管理员裁决（本次新转人工 {} 笔、既有留置 {} 笔）。",
                        report.markedManual() + report.manualPending(),
                        report.markedManual(), report.manualPending());
            }
        });
    }

    /**
     * 业务侧逆序收尾（主类 onDisable 在平台组件收尾之前调用）：
     * 先取消全部输入会话（onCancel 回调此刻还能安全触碰服务），
     * 再关全部菜单 + 清 GUI 会话，随后停定时任务（不再有新 sweep 入队），
     * 最后优雅关闭数据库执行器（等待在途写事务收尾，超时强制中断并让
     * 排队任务的 future 异常完成）。
     */
    public void shutdown() {
        inputSessionManager.cancelAll();
        menuDeps.router().closeAll();
        menuDeps.sessionManager().clear();
        // 复位 Tab 列表名，避免称号前缀残留在客户端直到玩家重连
        menuDeps.tabDisplay().resetAll();
        menuDeps.cooldownGate().clear();
        taskScheduler.stop();
        dbExecutor.shutdown(DB_SHUTDOWN_TIMEOUT);
    }
}
