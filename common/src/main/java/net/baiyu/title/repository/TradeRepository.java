package net.baiyu.title.repository;

import net.baiyu.title.domain.trade.Trade;
import net.baiyu.title.domain.trade.TradeItem;
import net.baiyu.title.domain.trade.TradeStatus;
import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 交易仓储（trade / trade_item）。
 * <p>交易期间不锁称号，成交事务内全量复核；PENDING 是唯一可迁出状态。
 */
public interface TradeRepository {

    /**
     * 插入交易单及其标的（RETURN_GENERATED_KEYS 取回交易 id）。
     *
     * @param c     数据库连接
     * @param t     交易单（id 忽略）
     * @param items 标的列表（side 区分双方）
     * @return 生成的交易 id
     * @throws SQLException JDBC 失败时
     */
    long insert(Connection c, Trade t, List<TradeItem> items) throws SQLException;

    /**
     * 状态机条件迁移（{@code WHERE id=? AND status=from}；0 行 = 并发已解决，放弃）。
     *
     * @param c       数据库连接
     * @param tradeId 交易 id
     * @param from    期望的当前状态
     * @param to      目标状态
     * @param by      解决者名称（可空）
     * @param reason  失败原因（可空）
     * @param now     当前时刻（UTC epoch 毫秒）
     * @return 迁移成功返回 true
     * @throws SQLException JDBC 失败时
     */
    boolean transition(Connection c, long tradeId, TradeStatus from, TradeStatus to,
                       String by, String reason, long now) throws SQLException;

    /**
     * 按 id 查交易单（含标的装载）。
     *
     * @param c       数据库连接
     * @param tradeId 交易 id
     * @return 交易单；不存在时为空
     * @throws SQLException JDBC 失败时
     */
    Optional<Trade> findWithItems(Connection c, long tradeId) throws SQLException;

    /**
     * 查询与玩家相关（任一侧）的全部 PENDING 交易（含标的装载）。
     *
     * @param c      数据库连接
     * @param player 玩家 UUID
     * @return PENDING 交易列表
     * @throws SQLException JDBC 失败时
     */
    List<Trade> findPendingFor(Connection c, UUID player) throws SQLException;

    /**
     * 捞取一批已过期的 PENDING 交易 id（TradeService.sweepExpired 用）。
     *
     * @param c     数据库连接
     * @param now   当前时刻（UTC epoch 毫秒）
     * @param batch 批大小
     * @return 过期 PENDING 交易 id 列表（最多 batch 条）
     * @throws SQLException JDBC 失败时
     */
    List<Long> pickExpiredPending(Connection c, long now, int batch) throws SQLException;

    /**
     * 玩家个人交易历史分页（任一侧，按创建时刻倒序）。
     *
     * @param c      数据库连接
     * @param player 玩家 UUID
     * @param page   页码（从 1 起）
     * @param size   每页条数
     * @return 分页结果
     * @throws SQLException JDBC 失败时
     */
    Page<Trade> personalHistory(Connection c, UUID player, int page, int size) throws SQLException;

    /**
     * 全局交易历史分页（管理端；可按状态与玩家名模糊过滤，按创建时刻倒序）。
     *
     * @param c        数据库连接
     * @param filter   状态过滤（可空 = 全部）
     * @param nameLike 玩家名模糊串（可空 = 不过滤；匹配任一侧名称）
     * @param page     页码（从 1 起）
     * @param size     每页条数
     * @return 分页结果
     * @throws SQLException JDBC 失败时
     */
    Page<Trade> globalHistory(Connection c, @Nullable TradeStatus filter, @Nullable String nameLike,
                              int page, int size) throws SQLException;

    /**
     * 统计玩家交易数（PAPI 计数用）。
     *
     * @param c             数据库连接
     * @param player        玩家 UUID
     * @param initiatedOnly 仅统计其作为发起方的交易
     * @param completedOnly 仅统计已成交（COMPLETED）的交易
     * @return 交易数
     * @throws SQLException JDBC 失败时
     */
    int countByPlayer(Connection c, UUID player, boolean initiatedOnly, boolean completedOnly) throws SQLException;
}
