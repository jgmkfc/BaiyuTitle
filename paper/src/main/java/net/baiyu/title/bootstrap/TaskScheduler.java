package net.baiyu.title.bootstrap;

import net.baiyu.title.BaiYuTitle;
import net.baiyu.title.chat.TabDisplayService;
import net.baiyu.title.config.ConfigHandler;
import net.baiyu.title.gui.core.GuiSessionManager;
import net.baiyu.title.service.ExpiryService;
import net.baiyu.title.service.TradeService;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 定时任务调度器：集中注册/注销插件的周期任务。
 *
 * <p>M2 挂载：到期扫描——Bukkit 异步定时器周期触发
 * {@link ExpiryService#sweep()}（该方法自行投递到 DbExecutor 写线程，
 * 触发线程不做任何 JDBC）。间隔取 config {@code tasks.expiry-sweep-seconds}
 * （默认 60 秒），仅在 {@link #start()} 时读取一次——reload 白名单不重建
 * 调度器，改动需重启生效。</p>
 *
 * <p>M3 挂载：GUI 会话空闲清扫——<b>主线程同步</b>定时器周期触发
 * {@link GuiSessionManager#sweep()}（管理器内部为普通 HashMap，
 * 异步驱动会破坏线程纪律），周期取 {@link GuiSessionManager#SWEEP_INTERVAL}。</p>
 *
 * <p>M7 挂载：报价过期扫描——异步定时器周期触发
 * {@link TradeService#sweepExpired(long)}（收敛实际发生在 DbExecutor
 * 写线程），间隔取 config {@code tasks.trade-sweep-seconds}（默认 300 秒），
 * 同样仅在 {@link #start()} 时读取一次。</p>
 *
 * <p>Tab 前缀刷新——<b>主线程</b>定时器周期触发
 * {@link net.baiyu.title.chat.TabDisplayService#refreshAll()}，间隔复用
 * {@code tasks.expiry-sweep-seconds}。作用是兜底：管理端强制收回、交易转出、
 * 组对账对账收回等路径不会主动通知表现层，靠这一轮全量重算把玩家列表名
 * 拉回一致（纯内存读缓存，无 DB IO）。</p>
 *
 * <p>线程纪律：{@link #start()} / {@link #stop()} 只在主线程调用
 * （onEnable / onDisable）。</p>
 */
public final class TaskScheduler {

    /** 每秒 tick 数（Bukkit 调度单位换算）。 */
    private static final long TICKS_PER_SECOND = 20L;

    /** 插件实例（Bukkit 调度器归属与日志）。 */
    private final BaiYuTitle plugin;

    /** 配置处理器（读取扫描间隔）。 */
    private final ConfigHandler configHandler;

    /** 到期收敛服务。 */
    private final ExpiryService expiryService;

    /** GUI 会话管理器（主线程同步清扫）。 */
    private final GuiSessionManager sessionManager;

    /** 交易服务（报价过期扫描）。 */
    private final TradeService tradeService;

    /** Tab 列表展示服务（到期收敛后全量刷新）。 */
    private final TabDisplayService tabDisplayService;

    /** 已注册任务表（stop 时逐个取消）。 */
    private final List<BukkitTask> tasks = new ArrayList<>();

    /**
     * 构造定时任务调度器。
     *
     * @param plugin           插件实例
     * @param configHandler    配置处理器
     * @param expiryService    到期收敛服务
     * @param sessionManager   GUI 会话管理器
     * @param tradeService     交易服务
     * @param tabDisplayService Tab 列表展示服务（收敛后刷新在线玩家前缀）
     */
    public TaskScheduler(BaiYuTitle plugin, ConfigHandler configHandler, ExpiryService expiryService,
                         GuiSessionManager sessionManager, TradeService tradeService,
                         TabDisplayService tabDisplayService) {
        this.plugin = Objects.requireNonNull(plugin, "插件实例不能为空");
        this.configHandler = Objects.requireNonNull(configHandler, "配置处理器不能为空");
        this.expiryService = Objects.requireNonNull(expiryService, "到期收敛服务不能为空");
        this.sessionManager = Objects.requireNonNull(sessionManager, "GUI 会话管理器不能为空");
        this.tradeService = Objects.requireNonNull(tradeService, "交易服务不能为空");
        this.tabDisplayService = Objects.requireNonNull(tabDisplayService, "Tab 展示服务不能为空");
    }

    /**
     * 注册全部周期任务（onEnable 装配成功后调用一次）。
     * <p>到期扫描首轮延迟一个间隔再跑——启动时目录缓存刚预热完，
     * 无需立刻扫描；渲染层按时间戳即时隐藏到期称号，不依赖收敛时点。
     * 会话清扫同样首轮延迟一个周期。</p>
     */
    public void start() {
        final int intervalSeconds = Math.max(1, configHandler.getConfig().tasks.expirySweepSeconds);
        final long periodTicks = intervalSeconds * TICKS_PER_SECOND;
        tasks.add(Bukkit.getScheduler().runTaskTimerAsynchronously(
                plugin, this::sweepExpirations, periodTicks, periodTicks));
        final long sweepTicks = GuiSessionManager.SWEEP_INTERVAL.toSeconds() * TICKS_PER_SECOND;
        tasks.add(Bukkit.getScheduler().runTaskTimer(
                plugin, sessionManager::sweep, sweepTicks, sweepTicks));
        final int tradeSeconds = Math.max(1, configHandler.getConfig().tasks.tradeSweepSeconds);
        final long tradeTicks = tradeSeconds * TICKS_PER_SECOND;
        tasks.add(Bukkit.getScheduler().runTaskTimerAsynchronously(
                plugin, this::sweepTradeOffers, tradeTicks, tradeTicks));
        // Tab 前缀自愈：管理端强制收回、交易转出、组对账等低频路径不会主动通知
        // 表现层，靠这一轮全量重算兜底（纯内存读，代价可忽略）；周期随到期扫描。
        tasks.add(Bukkit.getScheduler().runTaskTimer(
                plugin, tabDisplayService::refreshAll, periodTicks, periodTicks));
    }

    /**
     * 注销全部周期任务（onDisable 收尾时调用；幂等）。
     */
    public void stop() {
        for (final BukkitTask task : tasks) {
            task.cancel();
        }
        tasks.clear();
    }

    /**
     * 执行一轮到期扫描：触发后立即返回（实际收敛在 DbExecutor 写线程），
     * 完成回调只做日志——失败告警、有收敛量时留痕。
     */
    private void sweepExpirations() {
        expiryService.sweep().whenComplete((converged, failure) -> {
            if (failure != null) {
                plugin.getComponentLogger().warn("到期称号扫描失败，将于下一轮重试。", failure);
            } else if (converged != null && converged > 0) {
                plugin.getComponentLogger().info("到期称号扫描完成，本轮收敛 {} 条所有权。", converged);
                // 收敛即时生效（周期 Tab 刷新只是兜底，不必等到下一轮）
                tabDisplayService.requestRefreshAll();
            }
        });
    }

    /**
     * 执行一轮报价过期扫描：触发后立即返回（实际收敛在 DbExecutor 写线程），
     * 完成回调只做日志——失败告警、有收敛量时留痕。
     */
    private void sweepTradeOffers() {
        tradeService.sweepExpired(System.currentTimeMillis()).whenComplete((converged, failure) -> {
            if (failure != null) {
                plugin.getComponentLogger().warn("报价过期扫描失败，将于下一轮重试。", failure);
            } else if (converged != null && converged > 0) {
                plugin.getComponentLogger().info("报价过期扫描完成，本轮收敛 {} 笔报价。", converged);
            }
        });
    }
}
