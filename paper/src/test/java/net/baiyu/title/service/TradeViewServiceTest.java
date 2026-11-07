package net.baiyu.title.service;

import net.baiyu.title.cache.PlayerProfileCache;
import net.baiyu.title.cache.TitleCatalogCache;
import net.baiyu.title.config.PluginConfig;
import net.baiyu.title.domain.condition.PurchaseCondition;
import net.baiyu.title.domain.ownership.GrantSource;
import net.baiyu.title.domain.ownership.Ownership;
import net.baiyu.title.domain.ownership.OwnershipStatus;
import net.baiyu.title.domain.title.CombineLogic;
import net.baiyu.title.domain.title.LimitMode;
import net.baiyu.title.domain.title.Title;
import net.baiyu.title.domain.title.TitleCategory;
import net.baiyu.title.domain.trade.Trade;
import net.baiyu.title.domain.trade.TradeItem;
import net.baiyu.title.domain.trade.TradeSide;
import net.baiyu.title.domain.trade.TradeStatus;
import net.baiyu.title.domain.wardrobe.SlotConfig;
import net.baiyu.title.domain.wardrobe.SlotMode;
import net.baiyu.title.repository.AuditRepository;
import net.baiyu.title.repository.OwnershipRepository;
import net.baiyu.title.repository.Page;
import net.baiyu.title.repository.SlotRepository;
import net.baiyu.title.repository.TradeRepository;
import net.baiyu.title.repository.TransactionRunner;
import net.baiyu.title.service.executor.DbExecutor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 交易视图门面单测：只驱动纯缓存读路径
 * {@code tradableTitles} 与纯映射函数（摘要 / 详情组装 / 状态键），
 * 交易服务用抛错桩兜底（这些路径不应触碰任何仓储 / 事务），不触碰 Bukkit
 * 运行时（Material 仅枚举常量）。
 */
class TradeViewServiceTest {

    /** 展示时区固定 UTC（时间文案可复现）。 */
    private static final ZoneId UTC = ZoneOffset.UTC;

    /** 观察者（发起方视角用）。 */
    private static final UUID VIEWER = new UUID(7L, 42L);

    /** 交易对手方。 */
    private static final UUID OTHER = new UUID(9L, 99L);

    /** 共享执行器（本测试不触碰它；类结束统一关闭）。 */
    private static final DbExecutor DB_EXECUTOR = new DbExecutor();

    /** 玩家档案缓存（逐测试播种）。 */
    private final PlayerProfileCache profileCache = new PlayerProfileCache();

    /** 称号目录缓存（逐测试播种）。 */
    private final TitleCatalogCache catalogCache = new TitleCatalogCache();

    /** 配置实例（默认括号段 §f[ / §f]；trade.enabled 可翻转）。 */
    private final PluginConfig config = new PluginConfig();

    /** 被测门面。 */
    private final TradeViewService service = new TradeViewService(
            stubTradeService(), profileCache, catalogCache, () -> config,
            TradeViewServiceTest::rawLang, UTC);

    @AfterAll
    static void shutdownExecutor() {
        DB_EXECUTOR.shutdown(Duration.ofSeconds(3));
    }

    /** enabled 直读 config trade.enabled（reload 后随 supplier 刷新）。 */
    @Test
    void enabledFollowsConfig() {
        assertTrue(service.enabled());
        config.trade.enabled = false;
        assertFalse(service.enabled());
    }

    /** 可交易清单：排除不可交易 / 已软删 / 到期所有权；档案缺失得空列表。 */
    @Test
    void tradableTitlesFiltersInvalidEntries() {
        assertEquals(List.of(), service.tradableTitles(VIEWER), "档案未挂载时为空");

        catalogCache.replaceAll(List.of(
                title(1, "Tradable", true, null),
                title(2, "NotTradable", false, null),
                title(3, "Deleted", true, 5_000L)));
        final long future = System.currentTimeMillis() + 86_400_000L;
        final long past = System.currentTimeMillis() - 1_000L;
        profileCache.put(new PlayerProfileCache.PlayerProfile(VIEWER, "Viewer",
                Map.of(1, ownership(11L, 1, future),
                        2, ownership(12L, 2, null),
                        3, ownership(13L, 3, null),
                        4, ownership(14L, 4, null),
                        5, ownership(15L, 1, past)),
                List.of(), Map.of(), 0L));

        final List<TradeViewService.TradableTitle> result = service.tradableTitles(VIEWER);
        assertEquals(1, result.size(), "仅可交易且有效的称号入选");
        assertEquals(11L, result.get(0).ownershipId());
        assertEquals(1, result.get(0).title().id());
        assertEquals(LocalDate.ofInstant(java.time.Instant.ofEpochMilli(future), UTC),
                result.get(0).expiresOn(), "到期毫秒按展示时区换算为日期");
    }

    /** 可交易清单：被栏位引用的称号 equipped=true。 */
    @Test
    void tradableTitlesMarksEquipped() {
        catalogCache.replaceAll(List.of(title(1, "A", true, null), title(2, "B", true, null)));
        profileCache.put(new PlayerProfileCache.PlayerProfile(VIEWER, "Viewer",
                Map.of(1, ownership(21L, 1, null), 2, ownership(22L, 2, null)),
                List.of(new SlotConfig(1, true, SlotMode.FIXED, 1)),
                Map.of(), 0L));
        final List<TradeViewService.TradableTitle> result = service.tradableTitles(VIEWER);
        assertTrue(result.get(0).equipped(), "固定栏位引用计为装扮中");
        assertFalse(result.get(1).equipped());
    }

    /** 状态键：玩家视角 EXPIRED 归入"已拒绝"，管理视角独立。 */
    @Test
    void statusKeyPerspectives() {
        assertEquals("trade.status.pending", TradeViewService.statusKey(TradeStatus.PENDING, false));
        assertEquals("trade.status.rejected", TradeViewService.statusKey(TradeStatus.EXPIRED, false));
        assertEquals("trade.status.expired", TradeViewService.statusKey(TradeStatus.EXPIRED, true));
        assertEquals("trade.status.completed", TradeViewService.statusKey(TradeStatus.COMPLETED, true));
        assertEquals("trade.status.destroyed", TradeViewService.statusKey(TradeStatus.DESTROYED, false));
    }

    /** 有效状态：PENDING 已过过期时刻按 EXPIRED 呈现，终态不变。 */
    @Test
    void effectiveStatusFoldsExpiredPending() {
        final Trade pending = trade(TradeStatus.PENDING, 1_000L, List.of());
        assertEquals(TradeStatus.EXPIRED, TradeViewService.effectiveStatus(pending, 2_000L));
        assertEquals(TradeStatus.PENDING, TradeViewService.effectiveStatus(pending, 500L));
        final Trade done = trade(TradeStatus.COMPLETED, 1_000L, List.of());
        assertEquals(TradeStatus.COMPLETED, TradeViewService.effectiveStatus(done, 2_000L));
    }

    /** 摘要：方向 / 对手方 / 双侧计数 / 时间文案按观察者视角折算。 */
    @Test
    void summarizeResolvesPerspective() {
        final Trade t = trade(TradeStatus.PENDING, Long.MAX_VALUE, List.of(
                item(1L, TradeSide.INITIATOR, 101L, 1),
                item(2L, TradeSide.INITIATOR, 102L, 2),
                item(3L, TradeSide.TARGET, 201L, 3)));
        final TradeOfferView asInitiator = service.summarizeFor(VIEWER, t, 0L);
        assertFalse(asInitiator.incoming());
        assertEquals("TargetName", asInitiator.counterpartName());
        assertEquals(2, asInitiator.mineCount());
        assertEquals(1, asInitiator.theirsCount());

        final TradeOfferView asTarget = service.summarizeFor(OTHER, t, 0L);
        assertTrue(asTarget.incoming());
        assertEquals("InitiatorName", asTarget.counterpartName());
        assertEquals(1, asTarget.mineCount());
        assertEquals(2, asTarget.theirsCount());
        assertEquals("1970-1-1 00:00", asTarget.createdText(), "创建时刻按模板与 UTC 时区插值");
    }

    /** 详情：目标方对 PENDING 报价是 RECEIVER，发起方是 VIEWER；
     * 名称取快照并套括号段，目录缺失的标的图标兜底 NAME_TAG。 */
    @Test
    void assembleDetailRolesAndSnapshots() {
        catalogCache.replaceAll(List.of(title(1, "Live", true, null)));
        final Trade t = trade(TradeStatus.PENDING, Long.MAX_VALUE, List.of(
                item(1L, TradeSide.INITIATOR, 101L, 1),
                item(2L, TradeSide.TARGET, 201L, 999)));

        final TradeDetailView receiver = service.assembleDetail(t, OTHER, 0L);
        assertEquals(TradeDetailView.Role.RECEIVER, receiver.role());
        final TradeDetailView viewer = service.assembleDetail(t, VIEWER, 0L);
        assertEquals(TradeDetailView.Role.VIEWER, viewer.role());

        assertEquals("[ Snap101 ]", PlainTextComponentSerializer.plainText()
                        .serialize(viewer.initiatorItems().get(0).name()),
                "名称 = 括号前段 + 快照 + 括号后段");
        assertEquals(Material.NAME_TAG, viewer.targetItems().get(0).icon(),
                "目录缺失的称号图标兜底 NAME_TAG");
        assertEquals("-", viewer.resolvedByText(), "未解决时处理人取占位文案");
    }

    /** 终态交易对任何观察者都是 VIEWER（含目标方）。 */
    @Test
    void resolvedTradeIsViewerForEveryone() {
        final Trade t = trade(TradeStatus.REJECTED, Long.MAX_VALUE, List.of());
        assertEquals(TradeDetailView.Role.VIEWER, service.assembleDetail(t, OTHER, 0L).role());
    }

    // ---- 构造器与桩 ----

    /** 语言原文桩（时间模板 / 占位文案）。 */
    private static String rawLang(String key) {
        return switch (key) {
            case "format.date-time" -> "<year>-<month>-<day> <hour>:<minute>";
            case "menu.trade-detail.status.none" -> "-";
            default -> key;
        };
    }

    /** 构造交易单（发起方 VIEWER、目标方 OTHER）。 */
    private static Trade trade(TradeStatus status, long expiresAt, List<TradeItem> items) {
        return new Trade(50L, VIEWER, "InitiatorName", OTHER, "TargetName",
                status, 0L, expiresAt, null, null, null, items);
    }

    /** 构造标的（名称快照 = Snap<所有权 id>）。 */
    private static TradeItem item(long id, TradeSide side, long ownershipId, int titleId) {
        return new TradeItem(id, 50L, side, ownershipId, titleId, "Snap" + ownershipId);
    }

    /** 构造 ACTIVE 所有权（expiresAt 可空 = 永久）。 */
    private static Ownership ownership(long id, int titleId, Long expiresAt) {
        return new Ownership(id, titleId, VIEWER, "Viewer", GrantSource.ADMIN, null,
                1_000L, expiresAt, OwnershipStatus.ACTIVE, null, null);
    }

    /** 构造最小合法称号（tradable / 软删可配）。 */
    private static Title title(int id, String name, boolean tradable, Long deletedAt) {
        return new Title(id, name, name, TitleCategory.NORMAL, CombineLogic.AND, true, tradable,
                null, List.of(), "NAME_TAG", 0, LimitMode.NONE, null, null, null, 0,
                null, null, null, 1_000L, 1_000L, deletedAt,
                List.<PurchaseCondition>of(), Set.of());
    }

    /** 抛错桩交易服务：本测试的路径不应触碰任何仓储 / 事务。 */
    private TradeService stubTradeService() {
        return new TradeService(new ThrowingTradeRepository(), new ThrowingOwnershipRepository(),
                new ThrowingSlotRepository(), (c, e) -> {
                    throw new UnsupportedOperationException("纯映射路径不应触碰审计仓储");
                },
                new TransactionRunner(() -> {
                    throw new SQLException("纯映射路径不应触碰数据库连接");
                }),
                DB_EXECUTOR, catalogCache, profileCache, () -> config.trade, null);
    }

    /** 交易仓储抛错桩。 */
    private static final class ThrowingTradeRepository implements TradeRepository {
        @Override
        public long insert(Connection c, Trade t, List<TradeItem> items) {
            throw new UnsupportedOperationException("纯映射路径不应触碰交易仓储");
        }

        @Override
        public boolean transition(Connection c, long tradeId, TradeStatus from, TradeStatus to,
                                  String by, String reason, long now) {
            throw new UnsupportedOperationException("纯映射路径不应触碰交易仓储");
        }

        @Override
        public Optional<Trade> findWithItems(Connection c, long tradeId) {
            throw new UnsupportedOperationException("纯映射路径不应触碰交易仓储");
        }

        @Override
        public List<Trade> findPendingFor(Connection c, UUID player) {
            throw new UnsupportedOperationException("纯映射路径不应触碰交易仓储");
        }

        @Override
        public List<Long> pickExpiredPending(Connection c, long now, int batch) {
            throw new UnsupportedOperationException("纯映射路径不应触碰交易仓储");
        }

        @Override
        public Page<Trade> personalHistory(Connection c, UUID player, int page, int size) {
            throw new UnsupportedOperationException("纯映射路径不应触碰交易仓储");
        }

        @Override
        public Page<Trade> globalHistory(Connection c, TradeStatus filter, String nameLike,
                                         int page, int size) {
            throw new UnsupportedOperationException("纯映射路径不应触碰交易仓储");
        }

        @Override
        public int countByPlayer(Connection c, UUID player, boolean initiatedOnly,
                                 boolean completedOnly) {
            throw new UnsupportedOperationException("纯映射路径不应触碰交易仓储");
        }
    }

    /** 所有权仓储抛错桩。 */
    private static final class ThrowingOwnershipRepository implements OwnershipRepository {
        @Override
        public long insertGrant(Connection c, Ownership o) {
            throw new UnsupportedOperationException("纯映射路径不应触碰所有权仓储");
        }

        @Override
        public boolean endActive(Connection c, long ownershipId, OwnershipStatus to,
                                 String reason, long now) {
            throw new UnsupportedOperationException("纯映射路径不应触碰所有权仓储");
        }

        @Override
        public List<Ownership> findActiveByOwner(Connection c, UUID owner) {
            throw new UnsupportedOperationException("纯映射路径不应触碰所有权仓储");
        }

        @Override
        public Optional<Ownership> findActive(Connection c, UUID owner, int titleId) {
            throw new UnsupportedOperationException("纯映射路径不应触碰所有权仓储");
        }

        @Override
        public List<Ownership> pickExpired(Connection c, long now, int batch) {
            throw new UnsupportedOperationException("纯映射路径不应触碰所有权仓储");
        }

        @Override
        public List<UUID> findOwnersHavingAll(Connection c, Set<Integer> titleIds) {
            throw new UnsupportedOperationException("纯映射路径不应触碰所有权仓储");
        }

        @Override
        public int countDistinctBuyers(Connection c, int titleId) {
            throw new UnsupportedOperationException("纯映射路径不应触碰所有权仓储");
        }
    }

    /** 装扮栏位仓储抛错桩。 */
    private static final class ThrowingSlotRepository implements SlotRepository {
        @Override
        public List<SlotConfig> load(Connection c, UUID player) {
            throw new UnsupportedOperationException("纯映射路径不应触碰栏位仓储");
        }

        @Override
        public void upsert(Connection c, UUID player, SlotConfig slot) {
            throw new UnsupportedOperationException("纯映射路径不应触碰栏位仓储");
        }

        @Override
        public void clearTitleEverywhere(Connection c, UUID player, int titleId) {
            throw new UnsupportedOperationException("纯映射路径不应触碰栏位仓储");
        }

        @Override
        public void clearTitleForAllPlayers(Connection c, int titleId) {
            throw new UnsupportedOperationException("纯映射路径不应触碰栏位仓储");
        }

        @Override
        public Set<Integer> loadPool(Connection c, UUID player, int slotIndex) {
            throw new UnsupportedOperationException("纯映射路径不应触碰栏位仓储");
        }

        @Override
        public void replacePool(Connection c, UUID player, int slotIndex, Set<Integer> titleIds) {
            throw new UnsupportedOperationException("纯映射路径不应触碰栏位仓储");
        }
    }
}
