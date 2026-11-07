package net.baiyu.title.service;

import net.baiyu.title.cache.PlayerProfileCache;
import net.baiyu.title.cache.TitleCatalogCache;
import net.baiyu.title.config.PluginConfig;
import net.baiyu.title.domain.ownership.GrantSource;
import net.baiyu.title.domain.ownership.Ownership;
import net.baiyu.title.domain.ownership.OwnershipStatus;
import net.baiyu.title.domain.title.Title;
import net.baiyu.title.domain.trade.Trade;
import net.baiyu.title.domain.trade.TradeStatus;
import net.baiyu.title.service.trade.CreateResult;
import net.baiyu.title.service.trade.OfferItem;
import net.baiyu.title.testutil.BaseRepositoryTest;
import org.jetbrains.annotations.Nullable;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * 交易服务测试基座：在双方言仓储基座上叠加交易服务装配与种子数据工具。
 * <p>settings 供应器指向可变字段 {@link #tradeSettings}，测试可原地翻转
 * 总开关模拟 reload 生效。
 */
abstract class TradeTestSupport extends BaseRepositoryTest {

    /** 交易设置（可变；() -> tradeSettings 供应器读到最新值）。 */
    protected PluginConfig.TradeSettings tradeSettings;

    /** 称号目录缓存（当前服务实例所用）。 */
    protected TitleCatalogCache catalogCache;

    /** 玩家档案缓存（当前服务实例所用）。 */
    protected PlayerProfileCache profileCache;

    /**
     * 构造交易服务（默认设置 + 全新缓存）。
     */
    protected TradeService newTradeService() {
        tradeSettings = new PluginConfig.TradeSettings();
        catalogCache = new TitleCatalogCache();
        profileCache = new PlayerProfileCache();
        return buildService(catalogCache, profileCache);
    }

    /**
     * 全新服务实例直读数据库（等价模拟重启：新缓存从 DB 重载、新档案缓存为空，
     * 只共享同一个数据库文件）。
     */
    protected TradeService newIndependentService() {
        TitleCatalogCache freshCatalog = new TitleCatalogCache();
        freshCatalog.replaceAll(withConnection(c -> titleRepository.loadAll(c, true)));
        return buildService(freshCatalog, new PlayerProfileCache());
    }

    /**
     * 以给定缓存装配交易服务（钩子留空）。
     */
    private TradeService buildService(TitleCatalogCache catalog, PlayerProfileCache profiles) {
        return new TradeService(tradeRepository, ownershipRepository, slotRepository,
                auditRepository, txRunner, dbExecutor, catalog, profiles, () -> tradeSettings, null);
    }

    /**
     * 落库一条称号并刷入目录缓存。
     *
     * @return 称号 id
     */
    protected int seedTitle(Title title) {
        int id = runInTx(c -> titleRepository.insert(c, title));
        reloadCatalog();
        return id;
    }

    /**
     * 从数据库全量重载目录缓存（编辑称号后调用）。
     */
    protected void reloadCatalog() {
        catalogCache.replaceAll(withConnection(c -> titleRepository.loadAll(c, true)));
    }

    /**
     * 可交易 NORMAL 称号（免费、无限量）。
     */
    protected static Title tradableTitle(String plain) {
        return Title.builder().name("<white>" + plain).namePlain(plain)
                .createdAt(1L).updatedAt(1L).build();
    }

    /**
     * 管理员授予一条 ACTIVE 所有权。
     *
     * @param expiresAt 到期时刻（可空 = 永久）
     * @return 所有权 id
     */
    protected long grant(UUID owner, String name, int titleId, @Nullable Long expiresAt) {
        long now = System.currentTimeMillis();
        return runInTx(c -> ownershipRepository.insertGrant(c, new Ownership(0, titleId, owner,
                name, GrantSource.ADMIN, null, now, expiresAt, OwnershipStatus.ACTIVE, null, null)));
    }

    /**
     * 创建报价并断言成功，返回落库的 PENDING 交易单。
     */
    protected Trade createPending(TradeService service, UUID initiator, String initiatorName,
                                  UUID target, String targetName,
                                  List<OfferItem> mine, List<OfferItem> theirs) {
        CreateResult result = service.createOffer(
                initiator, initiatorName, target, targetName, mine, theirs).join();
        return assertInstanceOf(CreateResult.Created.class, result).trade();
    }

    /**
     * 直改所有权到期时刻（模拟"创建报价后标的到期"，绕过创建时校验）。
     */
    protected void forceOwnershipExpiry(long ownershipId, long expiresAt) {
        runInTx(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE " + tables.titleOwnership()
                    + " SET \"expires_at\"=? WHERE \"id\"=?")) {
                ps.setLong(1, expiresAt);
                ps.setLong(2, ownershipId);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /**
     * 读所有权当前状态列。
     */
    protected String ownershipStatus(long ownershipId) {
        return scalarString("SELECT \"status\" FROM " + tables.titleOwnership()
                + " WHERE \"id\"=" + ownershipId);
    }

    /**
     * 读交易当前状态。
     */
    protected TradeStatus tradeStatus(long tradeId) {
        return withConnection(c -> tradeRepository.findWithItems(c, tradeId)).orElseThrow().status();
    }

    /**
     * 按事件类型统计审计行数。
     */
    protected long auditCount(String eventType) {
        return scalarLong("SELECT COUNT(*) FROM " + tables.auditLog()
                + " WHERE \"event_type\"='" + eventType + "'");
    }

    /**
     * 执行返回单个字符串标量的查询。
     */
    protected String scalarString(String sql) {
        return withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException("标量查询无结果: " + sql);
                }
                return rs.getString(1);
            }
        });
    }
}
