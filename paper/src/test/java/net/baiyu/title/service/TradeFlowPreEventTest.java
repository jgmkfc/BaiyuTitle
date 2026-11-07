package net.baiyu.title.service;

import net.baiyu.title.cache.PlayerProfileCache;
import net.baiyu.title.cache.TitleCatalogCache;
import net.baiyu.title.config.PluginConfig;
import net.baiyu.title.repository.AuditRepository;
import net.baiyu.title.repository.OwnershipRepository;
import net.baiyu.title.repository.SlotRepository;
import net.baiyu.title.repository.TradeRepository;
import net.baiyu.title.repository.TransactionRunner;
import net.baiyu.title.service.executor.DbExecutor;
import net.baiyu.title.translation.Lang;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 交易执行链 Pre 事件分支单测：TradePreAcceptEvent 被取消时
 * {@code TradeService.accept} 不被调用（零 DB 写、报价保持 PENDING）。
 *
 * <p>不引 MockBukkit：Player / Plugin 用 JDK 动态代理桩（取消分支不应触碰
 * 任何 Bukkit 能力，代理对未预期调用直接抛错）；服务不被调的硬证据是
 * 交易设置供应器绊线——accept 写任务的第一步必读它，执行器排空后仍未触发
 * 即证明服务未被调度。</p>
 */
class TradeFlowPreEventTest {

    /** Pre 事件取消：闸门拦截后服务零调度、终局回调同步恰好一次。 */
    @Test
    void cancelledPreAcceptSkipsServiceEntirely() {
        DbExecutor dbExecutor = new DbExecutor();
        try {
            AtomicBoolean settingsTouched = new AtomicBoolean();
            TradeService trades = tradeService(dbExecutor, settingsTouched);
            AtomicInteger gateCalls = new AtomicInteger();
            AtomicInteger settled = new AtomicInteger();
            TradeFlow flow = new TradeFlow(disabledPlugin(), trades, new Lang(),
                    (acceptor, tradeId) -> {
                        gateCalls.incrementAndGet();
                        assertEquals(42L, tradeId, "闸门应收到原样交易 id");
                        return false; // 模拟第三方插件取消 Pre 事件
                    });

            flow.accept(untouchablePlayer(), 42L, settled::incrementAndGet);

            assertEquals(1, gateCalls.get(), "Pre 闸门恰好询问一次");
            assertEquals(1, settled.get(), "取消分支应同步回调终局恰好一次");
            dbExecutor.shutdown(Duration.ofSeconds(3)); // 排空执行器：若有误入队任务此处必已执行
            assertFalse(settingsTouched.get(), "Pre 取消后交易服务不得被调度（设置供应器绊线未触发）");
        } finally {
            dbExecutor.shutdown(Duration.ofSeconds(1));
        }
    }

    /** 绊线有效性对照：闸门放行时服务确实被调度（设置供应器被读到）。 */
    @Test
    void allowedPreAcceptReachesService() {
        DbExecutor dbExecutor = new DbExecutor();
        try {
            AtomicBoolean settingsTouched = new AtomicBoolean();
            TradeService trades = tradeService(dbExecutor, settingsTouched);
            TradeFlow flow = new TradeFlow(disabledPlugin(), trades, new Lang(),
                    (acceptor, tradeId) -> true);

            flow.accept(identifiedPlayer(), 42L, () -> { });

            dbExecutor.shutdown(Duration.ofSeconds(3));
            assertTrue(settingsTouched.get(), "闸门放行时 accept 写任务必读交易设置（绊线自证有效）");
        } finally {
            dbExecutor.shutdown(Duration.ofSeconds(1));
        }
    }

    // ---- 构造器与桩 ----

    /**
     * 抛错桩交易服务：仓储 / 连接全部不可触碰；交易设置供应器作为"服务被调"绊线
     * （总开关关闭，放行路径在读到设置后即返回 Disabled，不再深入仓储）。
     */
    private static TradeService tradeService(DbExecutor dbExecutor, AtomicBoolean settingsTouched) {
        PluginConfig.TradeSettings settings = new PluginConfig.TradeSettings();
        settings.enabled = false;
        return new TradeService(throwingProxy(TradeRepository.class),
                throwingProxy(OwnershipRepository.class), throwingProxy(SlotRepository.class),
                throwingProxy(AuditRepository.class),
                new TransactionRunner(() -> {
                    throw new SQLException("取消分支不应触碰数据库连接");
                }),
                dbExecutor, new TitleCatalogCache(), new PlayerProfileCache(),
                () -> {
                    settingsTouched.set(true);
                    return settings;
                }, null);
    }

    /** 玩家桩（取消分支用）：不应触碰任何玩家能力，未预期调用直接抛错。 */
    private static Player untouchablePlayer() {
        return throwingProxy(Player.class);
    }

    /** 玩家桩（放行分支用）：仅提供 UUID 与名字（Actor 构造所需），其余抛错。 */
    private static Player identifiedPlayer() {
        UUID uuid = new UUID(5L, 5L);
        return proxyOf(Player.class, method -> switch (method) {
            case "getUniqueId" -> uuid;
            case "getName" -> "Stub";
            default -> null;
        });
    }

    /** 插件桩：isEnabled 恒 false（异步回调静默丢弃，测试不触碰 Bukkit 调度器）。 */
    private static Plugin disabledPlugin() {
        return proxyOf(Plugin.class, method -> {
            if (method.equals("isEnabled")) {
                return Boolean.FALSE;
            }
            return null; // 未匹配：由通用兜底抛错
        });
    }

    /** 通用抛错代理（Object 三方法安全实现，业务方法一律抛错）。 */
    private static <T> T throwingProxy(Class<T> type) {
        return proxyOf(type, method -> null);
    }

    /** 构造代理：override 返回非 null 即采用；否则 Object 方法兜底、业务方法抛错。 */
    @SuppressWarnings("unchecked")
    private static <T> T proxyOf(Class<T> type, java.util.function.Function<String, Object> override) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> {
                    Object handled = override.apply(method.getName());
                    if (handled != null) {
                        return handled;
                    }
                    return switch (method.getName()) {
                        case "toString" -> type.getSimpleName() + "Stub";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> throw new UnsupportedOperationException(
                                "取消分支不应触碰 " + type.getSimpleName() + "." + method.getName());
                    };
                });
    }
}
