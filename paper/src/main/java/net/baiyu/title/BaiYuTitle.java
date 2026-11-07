package net.baiyu.title;

import net.baiyu.title.bootstrap.Assembled;
import net.baiyu.title.bootstrap.PluginAssembly;
import net.baiyu.title.command.CommandHandler;
import net.baiyu.title.config.ConfigHandler;
import net.baiyu.title.database.handler.DatabaseHandler;
import net.baiyu.title.hook.HookManager;
import net.baiyu.title.placeholder.InternalPlaceholderResolver;
import net.baiyu.title.threadutil.SchedulerHandler;
import net.baiyu.title.translation.TranslationHandler;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * BaiyuTitle 插件主类：只做生命周期编排。
 *
 * <p>平台组件（配置、翻译、数据库、线程调度、软依赖钩子）按 {@link Reloadable}
 * 顺序表正序启动、逆序收尾；业务对象一律由组合根
 * {@link PluginAssembly#assemble} 装配，主类仅持有 {@link Assembled}
 * 产物并驱动其 start/shutdown，不直接触碰任何业务服务。</p>
 */
public class BaiYuTitle extends AbstractBaiYuTitle {
    /** 配置处理器（config.yml / database.yml） */
    private ConfigHandler configHandler;
    /** YAML 翻译生命周期 */
    private TranslationHandler translationHandler;
    /** HikariCP + Flyway 数据库生命周期 */
    private DatabaseHandler databaseHandler;
    /** thread-util 调度器生命周期 */
    private SchedulerHandler schedulerHandler;
    /** Vault / PlaceholderAPI / bStats 软依赖钩子 */
    private HookManager hookManager;

    /** 生命周期顺序表：onLoad/onEnable 正序，onDisable 逆序 */
    private List<Reloadable> handlers;

    /** 业务装配产物（onEnable 装配成功后非空；onDisable 收尾后置空） */
    private Assembled assembled;

    @Override
    public void onLoad() {
        configHandler = new ConfigHandler(this);
        translationHandler = new TranslationHandler(configHandler);
        databaseHandler = DatabaseHandler.builder()
            .withConfigHandler(configHandler)
            .withLogger(getComponentLogger())
            .withMigrate(true)
            .build();
        schedulerHandler = new SchedulerHandler();
        hookManager = new HookManager(this);

        handlers = List.of(
            configHandler,
            translationHandler,
            databaseHandler,
            schedulerHandler,
            hookManager
        );

        for (Reloadable handler : handlers)
            handler.onLoad(this);

        // CommandAPI 必须在 onLoad 初始化（带一次性守卫，reload 白名单不触碰）
        CommandHandler.onLoadOnce(this);
    }

    @Override
    public void onEnable() {
        for (Reloadable handler : handlers)
            handler.onEnable(this);

        // 数据库是硬前提：启动失败直接停用插件，避免半残状态运行
        if (!databaseHandler.isStarted()) {
            getComponentLogger().warn("数据库初始化失败，BaiyuTitle 已停用。请检查 database.yml 配置。");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        // 业务装配（组合根唯一入口）→ 命令注册 → 监听注册 → 定时任务启动；失败同样停用插件
        try {
            assembled = PluginAssembly.assemble(this, configHandler, translationHandler, databaseHandler);
            CommandHandler.enable();
            assembled.commandRoot().register();
            assembled.listenerHandler().register();
            assembled.taskScheduler().start();
            // 启动孤儿订单恢复（写线程异步收敛，不阻塞启动，完成后控制台记数）
            assembled.recoverOrphanOrders(getComponentLogger());
            preloadOnlinePlayers();
        } catch (Exception e) {
            getComponentLogger().error("业务装配失败，BaiyuTitle 已停用。", e);
            Bukkit.getPluginManager().disablePlugin(this);
        }
    }

    /**
     * 对启用时已在线的玩家补跑一次登录预载（服务器 /reload 或热启用场景，
     * 这些玩家不会再触发 Join 事件；正常首启时在线列表为空，本方法为 no-op）。
     */
    private void preloadOnlinePlayers() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            assembled.playerLifecycleService().handleJoin(player).whenComplete((ignored, failure) -> {
                if (failure != null)
                    getComponentLogger().warn("在线玩家 {} 的档案补载失败。", player.getName(), failure);
            });
        }
    }

    @Override
    public void onDisable() {
        // 先停 CommandAPI，再收尾业务侧（停定时任务、优雅关闭数据库执行器），最后逆序收尾平台组件
        CommandHandler.disable();
        if (assembled != null) {
            assembled.shutdown();
            assembled = null;
        }

        for (Reloadable handler : handlers.reversed())
            handler.onDisable(this);
    }

    @Override
    public @NotNull ConfigHandler getConfigHandler() {
        return configHandler;
    }

    /**
     * PAPI 变量内部解析器（PAPIExpansion 委托入口；软依赖钩子先于业务装配注册，
     * 故按需取用而非构造注入）。
     *
     * @return 解析器；业务尚未装配或已收尾时为 null
     */
    public @Nullable InternalPlaceholderResolver getPlaceholderResolver() {
        return assembled == null ? null : assembled.placeholderResolver();
    }
}
