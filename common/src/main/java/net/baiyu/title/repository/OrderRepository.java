package net.baiyu.title.repository;

import net.baiyu.title.domain.order.OrderState;
import net.baiyu.title.domain.order.PurchaseOrder;
import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 购买订单仓储（purchase_order）。
 * <p>订单只沿状态机边迁移（条件 UPDATE from-state，不变量 §十 8）。
 */
public interface OrderRepository {

    /**
     * 插入订单（RETURN_GENERATED_KEYS 取回主键；order_uuid 为幂等键）。
     *
     * @param c 数据库连接
     * @param o 订单（id 忽略）
     * @return 生成的主键
     * @throws SQLException JDBC 失败时
     */
    long insert(Connection c, PurchaseOrder o) throws SQLException;

    /**
     * 状态机条件迁移（{@code WHERE id=? AND state=from}；0 行 = 并发已迁移，放弃）。
     *
     * @param c          数据库连接
     * @param orderId    订单 id
     * @param from       期望的当前状态
     * @param to         目标状态
     * @param failReason 失败原因（可空）
     * @param now        当前时刻（UTC epoch 毫秒）
     * @return 迁移成功返回 true
     * @throws SQLException JDBC 失败时
     */
    boolean transition(Connection c, long orderId, OrderState from, OrderState to,
                       @Nullable String failReason, long now) throws SQLException;

    /**
     * 查询全部非终态订单（启动孤儿订单恢复用；含 MANUAL）。
     *
     * @param c 数据库连接
     * @return 非终态订单列表
     * @throws SQLException JDBC 失败时
     */
    List<PurchaseOrder> findNonTerminal(Connection c) throws SQLException;

    /**
     * 统计某称号的已履约订单数（sold_count 不变量对账用）。
     *
     * @param c       数据库连接
     * @param titleId 称号 id
     * @return FULFILLED 订单数
     * @throws SQLException JDBC 失败时
     */
    int countFulfilled(Connection c, int titleId) throws SQLException;

    /**
     * 按 id 查订单（管理员 resolve 用）。
     *
     * @param c       数据库连接
     * @param orderId 订单 id
     * @return 订单；不存在时为空
     * @throws SQLException JDBC 失败时
     */
    Optional<PurchaseOrder> findById(Connection c, long orderId) throws SQLException;

    /**
     * 判断玩家对某称号是否存在非终态订单（幂等防重：存在则拒绝新订单，§六）。
     *
     * @param c       数据库连接
     * @param player  玩家 UUID
     * @param titleId 称号 id
     * @return 存在返回 true
     * @throws SQLException JDBC 失败时
     */
    boolean existsNonTerminal(Connection c, UUID player, int titleId) throws SQLException;

    /**
     * 回填订单的称号 id（CUSTOM 订单履约后回填，§六）。
     *
     * @param c       数据库连接
     * @param orderId 订单 id
     * @param titleId 称号 id
     * @param now     当前时刻（UTC epoch 毫秒）
     * @throws SQLException JDBC 失败时
     */
    void fillTitleId(Connection c, long orderId, int titleId, long now) throws SQLException;

    /**
     * 查询某称号的全部已履约买家（购买者列表菜单数据源），按履约时刻倒序。
     * <p>整表按称号过滤后全量返回，分页由菜单侧完成（与其余列表菜单同口径）。
     *
     * @param c       数据库连接
     * @param titleId 称号 id
     * @return 已履约买家行列表（同一玩家多次购买产生多行）
     * @throws SQLException JDBC 失败时
     */
    List<FulfilledBuyer> findFulfilledBuyers(Connection c, int titleId) throws SQLException;

    /**
     * 已履约买家行（购买者列表条目；只取展示所需三列，不载全订单）。
     *
     * @param playerUuid  买家 UUID
     * @param playerName  买家名称（下单时缓存列）
     * @param fulfilledAt 履约时刻（UTC epoch 毫秒；FULFILLED 为终态，
     *                    {@code updated_at} 即终态迁移时刻）
     */
    record FulfilledBuyer(UUID playerUuid, String playerName, long fulfilledAt) {
    }
}
