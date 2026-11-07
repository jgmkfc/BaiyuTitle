package net.baiyu.title.bootstrap;

import net.baiyu.title.BaiYuTitle;
import net.baiyu.title.chat.TabDisplayService;
import net.baiyu.title.config.ConfigHandler;
import net.baiyu.title.config.DatabaseConfig;
import net.baiyu.title.gui.core.GuiSessionManager;
import net.baiyu.title.gui.core.MenuRouter;
import net.baiyu.title.gui.render.ItemNameRegistry;
import net.baiyu.title.input.InputSessionManager;
import net.baiyu.title.translation.TranslationHandler;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * 白名单式重载协调器（/byt reload 只刷新可安全重建的部分）。
 *
 * <p>重载序列：取消全部输入会话 → 关全部菜单 + 清 GUI 会话 →
 * 重读 config.yml + database.yml → YAML 语言快照重建（语言键可能已变化）→
 * 物品名注册表重读 → 称号目录缓存全量重建（DbExecutor 读线程异步）。
 * database.yml 的改动<b>不</b>热应用——只对比新旧快照并日志告警需重启；
 * 命令 / 监听器 / 定时调度器一律不重建（CommandAPI 不允许二次 onLoad）。</p>
 *
 * <p>线程约定：{@link #reload()} 在主线程调用（管理命令入口）；
 * 配置与语言的重建同步完成，目录缓存重建异步完成，返回的 future
 * 在缓存就绪后完成，供命令层回发成功/失败消息。</p>
 */
public final class ReloadCoordinator {

    /** 插件实例（ConfigHandler / TranslationHandler 生命周期方法的入参）。 */
    private final BaiYuTitle plugin;

    /** 配置处理器（重读 config.yml / database.yml）。 */
    private final ConfigHandler configHandler;

    /** 翻译生命周期（reload 时重建 YAML 语言快照）。 */
    private final TranslationHandler translationHandler;

    /** 输入会话管理器（序列头部取消全部输入）。 */
    private final InputSessionManager inputSessionManager;

    /** 菜单路由（取消输入后关全部菜单）。 */
    private final MenuRouter menuRouter;

    /** GUI 会话管理器（关菜单后清空会话状态）。 */
    private final GuiSessionManager sessionManager;

    /** 物品名注册表（语言重初始化后按新语言重读）。 */
    private final ItemNameRegistry itemNameRegistry;

    /** Tab 列表展示服务（按新括号段/分隔段全量刷新在线玩家）。 */
    private final TabDisplayService tabDisplayService;

    /** 目录缓存重建器（由 PluginAssembly 注入，复用启动预热逻辑）。 */
    private final Supplier<CompletableFuture<Integer>> catalogReloader;

    /**
     * 构造重载协调器。
     *
     * @param plugin              插件实例
     * @param configHandler       配置处理器
     * @param translationHandler  翻译生命周期
     * @param inputSessionManager 输入会话管理器
     * @param menuRouter          菜单路由
     * @param sessionManager      GUI 会话管理器
     * @param itemNameRegistry    物品名注册表
     * @param tabDisplayService   Tab 列表展示服务（重载后全量刷新前缀）
     * @param catalogReloader     目录缓存重建器（返回重建后的称号条数）
     */
    public ReloadCoordinator(BaiYuTitle plugin, ConfigHandler configHandler,
                             TranslationHandler translationHandler,
                             InputSessionManager inputSessionManager, MenuRouter menuRouter,
                             GuiSessionManager sessionManager, ItemNameRegistry itemNameRegistry,
                             TabDisplayService tabDisplayService,
                             Supplier<CompletableFuture<Integer>> catalogReloader) {
        this.plugin = Objects.requireNonNull(plugin, "插件实例不能为空");
        this.configHandler = Objects.requireNonNull(configHandler, "配置处理器不能为空");
        this.translationHandler = Objects.requireNonNull(translationHandler, "翻译生命周期不能为空");
        this.inputSessionManager = Objects.requireNonNull(inputSessionManager, "输入会话管理器不能为空");
        this.menuRouter = Objects.requireNonNull(menuRouter, "菜单路由不能为空");
        this.sessionManager = Objects.requireNonNull(sessionManager, "GUI 会话管理器不能为空");
        this.itemNameRegistry = Objects.requireNonNull(itemNameRegistry, "物品名注册表不能为空");
        this.tabDisplayService = Objects.requireNonNull(tabDisplayService, "Tab 展示服务不能为空");
        this.catalogReloader = Objects.requireNonNull(catalogReloader, "目录缓存重建器不能为空");
    }

    /**
     * 执行一次白名单式重载。
     *
     * @return 重载结果 future：成功时携带 {@link Result}；
     *         任一环节失败以异常完成（命令层据此回发失败消息）
     */
    public CompletableFuture<Result> reload() {
        final boolean databaseChanged;
        try {
            // 0. 收口表现层瞬态状态：取消全部输入 → 关全部菜单（不触发 onClosed 仲裁）→ 清会话
            inputSessionManager.cancelAll();
            menuRouter.closeAll();
            sessionManager.clear();

            // 1. 重读配置（先快照旧数据库配置供对比）
            final DatabaseConfig before = configHandler.getDatabaseConfig();
            configHandler.onLoad(plugin);
            if (configHandler.getConfig() == null || configHandler.getDatabaseConfig() == null) {
                // ConfigLoader.build 失败时返回 null（详情已由其内部日志输出）
                return CompletableFuture.failedFuture(new IllegalStateException(
                        "配置文件解析失败（config.yml / database.yml），详见控制台日志"));
            }
            databaseChanged = databaseConfigChanged(before, configHandler.getDatabaseConfig());
            if (databaseChanged) {
                plugin.getComponentLogger().warn(
                        "检测到 database.yml 有改动：数据库配置不支持热重载，需重启服务器才会生效。");
            }

            // 2. 重建 YAML 语言快照（应用可能变化的 language 键与 lang 文件改动）
            translationHandler.reload(plugin);

            // 3. 物品名注册表按新语言重读（失败自降级告警，不中断序列）
            itemNameRegistry.reload();

            // 3.5 Tab 列表前缀按新的括号段/分隔段/开关全量刷新（主线程下一 tick 执行）
            tabDisplayService.requestRefreshAll();
        } catch (Exception e) {
            plugin.getComponentLogger().error("BaiyuTitle 重载失败。", e);
            return CompletableFuture.failedFuture(e);
        }

        // 4. 称号目录缓存全量重建（读线程装载，volatile 快照整体替换，无空窗）
        return catalogReloader.get()
                .thenApply(titleCount -> new Result(databaseChanged, titleCount))
                .whenComplete((result, failure) -> {
                    if (failure != null) {
                        plugin.getComponentLogger().error("BaiyuTitle 重载目录缓存失败。", failure);
                    }
                });
    }

    /**
     * 对比新旧数据库配置的关键字段（连接目标 / 凭据 / 表前缀）。
     * 任一变化即视为"需重启生效"。
     */
    private static boolean databaseConfigChanged(DatabaseConfig before, DatabaseConfig after) {
        final DatabaseConfig.Database old = before.database;
        final DatabaseConfig.Database fresh = after.database;
        return old.type != fresh.type
                || !Objects.equals(old.tablePrefix, fresh.tablePrefix)
                || !Objects.equals(old.host, fresh.host)
                || old.port != fresh.port
                || !Objects.equals(old.database, fresh.database)
                || !Objects.equals(old.username, fresh.username)
                || !Objects.equals(old.password, fresh.password);
    }

    /**
     * 重载结果。
     *
     * @param databaseChanged database.yml 是否有改动（true 时命令层应提示需重启）
     * @param reloadedTitles  目录缓存重建后的称号条数（含软删行）
     */
    public record Result(boolean databaseChanged, int reloadedTitles) {
    }
}
