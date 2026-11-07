package net.baiyu.title.service.trade;

import net.baiyu.title.cache.TitleCatalogCache;
import net.baiyu.title.domain.ownership.Ownership;
import net.baiyu.title.domain.title.Title;
import net.baiyu.title.domain.title.TitleCategory;
import net.baiyu.title.repository.OwnershipRepository;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 报价标的校验器（创建报价与成交复核共用同一套权威校验七）。
 * <p>逐标的校验：属该侧玩家的 ACTIVE 所有权且未到期、称号存在未软删、
 * tradable 且类别为 NORMAL（BOND/CUSTOM/GROUP 恒不可交易——domain 已强制
 * tradable=false，此处按类别再拒一次，双重防线）；再做对侧持有交叉校验
 * （接收方已 ACTIVE 持有转入称号且未在本单转出 → 成交必违唯一约束，提前拒绝）。
 * <p>任一失败抛 {@link TradeAbort}（结构校验在服务层，先于本类执行）。
 */
public final class OfferValidator {

    /** 所有权仓储。 */
    private final OwnershipRepository ownershipRepository;

    /** 称号目录缓存。 */
    private final TitleCatalogCache catalogCache;

    /**
     * 构造校验器。
     *
     * @param ownershipRepository 所有权仓储
     * @param catalogCache        称号目录缓存
     */
    public OfferValidator(OwnershipRepository ownershipRepository, TitleCatalogCache catalogCache) {
        this.ownershipRepository = Objects.requireNonNull(ownershipRepository, "所有权仓储不能为空");
        this.catalogCache = Objects.requireNonNull(catalogCache, "称号目录缓存不能为空");
    }

    /**
     * 校验整张报价（写事务内调用；创建时与成交复核时各跑一遍，口径一致）。
     *
     * @param c              数据库连接
     * @param initiator      发起方 UUID
     * @param target         目标方 UUID
     * @param initiatorItems 发起方标的引用
     * @param targetItems    目标方标的引用
     * @param now            当前时刻（UTC epoch 毫秒）
     * @return 校验通过的报价快照（交割与快照取材用）
     * @throws TradeAbort   任一校验失败时
     * @throws SQLException JDBC 失败时
     */
    public ValidatedOffer validate(Connection c, UUID initiator, UUID target,
                                   List<OfferItem> initiatorItems, List<OfferItem> targetItems,
                                   long now) throws SQLException {
        SideState initiatorSide = checkSide(c, initiator, initiatorItems, now);
        SideState targetSide = checkSide(c, target, targetItems, now);
        crossCheck(initiatorItems, targetSide);
        crossCheck(targetItems, initiatorSide);
        Map<Long, Ownership> ownerships = new HashMap<>(initiatorSide.picked());
        ownerships.putAll(targetSide.picked());
        Map<Long, Title> titles = new HashMap<>(initiatorSide.titles());
        titles.putAll(targetSide.titles());
        return new ValidatedOffer(Map.copyOf(ownerships), Map.copyOf(titles));
    }

    /**
     * 校验单侧标的：归属/ACTIVE/未到期/称号存在/可交易。
     */
    private SideState checkSide(Connection c, UUID owner, List<OfferItem> refs, long now)
            throws SQLException {
        Map<Long, Ownership> actives = new HashMap<>();
        for (Ownership ownership : ownershipRepository.findActiveByOwner(c, owner)) {
            actives.put(ownership.id(), ownership);
        }
        Map<Long, Ownership> picked = new LinkedHashMap<>();
        Map<Long, Title> titles = new LinkedHashMap<>();
        for (OfferItem ref : refs) {
            Ownership ownership = actives.get(ref.ownershipId());
            if (ownership == null || ownership.titleId() != ref.titleId()) {
                throw new TradeAbort(TradeBlockReason.ITEM_NOT_OWNED, ref.titleId());
            }
            if (ownership.isExpired(now)) {
                throw new TradeAbort(TradeBlockReason.ITEM_EXPIRED, ref.titleId());
            }
            Title title = catalogCache.byId(ref.titleId()).filter(t -> !t.isDeleted())
                    .orElseThrow(() -> new TradeAbort(TradeBlockReason.TITLE_NOT_FOUND, ref.titleId()));
            // 双重防线：domain 已把 BOND/CUSTOM/GROUP 的 tradable 强制为 false，类别再验一次
            if (title.category() != TitleCategory.NORMAL || !title.tradable()) {
                throw new TradeAbort(TradeBlockReason.NOT_TRADABLE, ref.titleId());
            }
            picked.put(ownership.id(), ownership);
            titles.put(ownership.id(), title);
        }
        Set<Integer> activeTitleIds = actives.values().stream()
                .map(Ownership::titleId).collect(Collectors.toSet());
        Set<Integer> offeredTitleIds = picked.values().stream()
                .map(Ownership::titleId).collect(Collectors.toSet());
        return new SideState(picked, titles, activeTitleIds, offeredTitleIds);
    }

    /**
     * 对侧持有交叉校验：接收方 ACTIVE 持有即将转入的称号、且未把自己那份列入
     * 本单转出（同款对倒放行）时拒绝——否则成交事务必撞 (owner, title) 唯一约束。
     */
    private static void crossCheck(List<OfferItem> incoming, SideState recipient) {
        Set<Integer> blocking = new HashSet<>(recipient.activeTitleIds());
        blocking.removeAll(recipient.offeredTitleIds());
        for (OfferItem ref : incoming) {
            if (blocking.contains(ref.titleId())) {
                throw new TradeAbort(TradeBlockReason.RECIPIENT_ALREADY_OWNS, ref.titleId());
            }
        }
    }

    /**
     * 校验通过的报价快照。
     *
     * @param ownershipsById 所有权实例索引（所有权 id → 实例；交割复制 expires_at 用）
     * @param titlesById     称号索引（所有权 id → 称号；名称快照取材用）
     */
    public record ValidatedOffer(Map<Long, Ownership> ownershipsById, Map<Long, Title> titlesById) {
    }

    /**
     * 单侧校验的中间状态（交叉校验取材用）。
     *
     * @param picked          选中的所有权实例（所有权 id → 实例）
     * @param titles          选中标的的称号（所有权 id → 称号）
     * @param activeTitleIds  该侧玩家全部 ACTIVE 称号 id
     * @param offeredTitleIds 该侧玩家本单转出的称号 id
     */
    private record SideState(Map<Long, Ownership> picked, Map<Long, Title> titles,
                             Set<Integer> activeTitleIds, Set<Integer> offeredTitleIds) {
    }
}
