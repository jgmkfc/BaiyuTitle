package net.baiyu.title.service;

import net.baiyu.title.cache.PlayerProfileCache;
import net.baiyu.title.cache.TitleCatalogCache;
import net.baiyu.title.config.PluginConfig;
import net.baiyu.title.domain.condition.PurchaseCondition;
import net.baiyu.title.domain.title.CombineLogic;
import net.baiyu.title.domain.title.LimitMode;
import net.baiyu.title.domain.title.Title;
import net.baiyu.title.domain.title.TitleCategory;
import net.baiyu.title.gui.render.AdminLoreComposer;
import net.baiyu.title.gui.render.LoreComposer;
import net.baiyu.title.gui.render.ShopLoreComposer;
import net.baiyu.title.gui.render.TitleItemRenderer;
import net.baiyu.title.gui.shop.ShopEntryView;
import net.baiyu.title.repository.AuditRepository;
import net.baiyu.title.repository.CoinRepository;
import net.baiyu.title.repository.OrderRepository;
import net.baiyu.title.repository.OwnershipRepository;
import net.baiyu.title.repository.TitleRepository;
import net.baiyu.title.repository.TransactionRunner;
import net.baiyu.title.service.executor.DbExecutor;
import net.baiyu.title.service.purchase.Eligibility;
import net.baiyu.title.service.purchase.PurchaseSnapshot;
import net.baiyu.title.service.purchase.RecoveryPolicy;
import net.baiyu.title.translation.Lang;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 购买执行链 Pre 事件分支单测：TitlePrePurchaseEvent 被取消时
 * {@code PurchaseService.beginPurchase} 不被调用（零订单、零扣款）。
 *
 * <p>不引 MockBukkit：Player / Plugin 用 JDK 动态代理桩，购买服务建立在
 * 抛错仓储与抛错连接之上——取消分支若误触服务，任何 DB 触碰都会立刻炸测试；
 * 终局回调必须在 {@code start} 返回前同步发生（异步链意味着服务被调度过）。</p>
 */
class PurchaseFlowPreEventTest {

    /** 共享执行器（取消分支不应有任何任务入队；类结束统一关闭）。 */
    private static final DbExecutor DB_EXECUTOR = new DbExecutor();

    @AfterAll
    static void shutdownExecutor() {
        DB_EXECUTOR.shutdown(Duration.ofSeconds(3));
    }

    /** Pre 事件取消：闸门拦截后服务零调用、终局回调同步恰好一次。 */
    @Test
    void cancelledPrePurchaseSkipsServiceEntirely() {
        AtomicInteger gateCalls = new AtomicInteger();
        AtomicInteger settled = new AtomicInteger();
        PurchaseFlow flow = new PurchaseFlow(disabledPlugin(), stubPurchaseService(),
                new NoEconomyGateway(), new Lang(), stubRenderer(),
                (buyer, titleId) -> {
                    gateCalls.incrementAndGet();
                    assertEquals(7, titleId, "闸门应收到原样称号 id");
                    return false; // 模拟第三方插件取消 Pre 事件
                });

        flow.start(untouchablePlayer(), entryOf(title(7, "限定")), null, settled::incrementAndGet);

        assertEquals(1, gateCalls.get(), "Pre 闸门恰好询问一次");
        assertEquals(1, settled.get(), "取消分支应同步回调终局恰好一次（异步链 = 服务被调度过）");
    }

    // ---- 构造器与桩 ----

    /** 抛错桩购买服务：取消分支不应触碰任何仓储 / 连接。 */
    private static PurchaseService stubPurchaseService() {
        return new PurchaseService(throwingProxy(OrderRepository.class),
                throwingProxy(TitleRepository.class), throwingProxy(OwnershipRepository.class),
                throwingProxy(CoinRepository.class), throwingProxy(AuditRepository.class),
                new TransactionRunner(() -> {
                    throw new SQLException("取消分支不应触碰数据库连接");
                }),
                DB_EXECUTOR, new TitleCatalogCache(), new PlayerProfileCache(), null,
                RecoveryPolicy.GRANT, PluginConfig.CustomTitleSettings::new);
    }

    /** 渲染门面桩（取消分支不触碰；lore 模板函数直接回显键名）。 */
    private static TitleItemRenderer stubRenderer() {
        LoreComposer loreComposer = new LoreComposer(key -> key);
        ShopLoreComposer shopLoreComposer = new ShopLoreComposer(key -> key);
        return new TitleItemRenderer(loreComposer, shopLoreComposer,
                new AdminLoreComposer(key -> key, loreComposer, shopLoreComposer), () -> null);
    }

    /** 最小可购条目视图（免费称号：条件空集 + CanBuy 免单）。 */
    private static ShopEntryView entryOf(Title title) {
        return new ShopEntryView(title, new Eligibility.CanBuy(Map.of(), true), false,
                new PurchaseSnapshot(LocalDate.EPOCH, false, null, 0L, null, null, false),
                Map.of(), null, List.of());
    }

    /** 构造最小合法称号。 */
    private static Title title(int id, String name) {
        return new Title(id, name, name, TitleCategory.NORMAL, CombineLogic.AND, true, true,
                null, List.of(), "NAME_TAG", 0, LimitMode.NONE, null, null, null, 0,
                null, null, null, 1_000L, 1_000L, null,
                List.<PurchaseCondition>of(), Set.of());
    }

    /** 玩家桩：取消分支不应触碰任何玩家能力，未预期调用直接抛错。 */
    private static Player untouchablePlayer() {
        return throwingProxy(Player.class);
    }

    /** 插件桩：isEnabled 恒 false（异步回调静默丢弃，测试不触碰 Bukkit 调度器）。 */
    private static Plugin disabledPlugin() {
        return proxyOf(Plugin.class, method -> "isEnabled".equals(method) ? Boolean.FALSE : null);
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
