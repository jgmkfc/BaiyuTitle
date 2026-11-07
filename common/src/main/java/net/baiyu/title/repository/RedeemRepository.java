package net.baiyu.title.repository;

import net.baiyu.title.domain.redeem.RedeemCard;
import net.baiyu.title.domain.redeem.RedeemCode;
import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

/**
 * 兑换仓储（设计文档 §五 RedeemService 需求；表 V5：redeem_code /
 * redeem_code_use / redeem_card）。
 * <p>兑换码用量与称号卡核销均以条件 UPDATE 保证原子性（不变量 §十 14）。
 */
public interface RedeemRepository {

    /**
     * 插入兑换码（RETURN_GENERATED_KEYS 取回主键；码面由服务层大写归一化）。
     *
     * @param c    数据库连接
     * @param code 兑换码（id 忽略）
     * @return 生成的主键
     * @throws SQLException JDBC 失败时
     */
    long insertCode(Connection c, RedeemCode code) throws SQLException;

    /**
     * 按码面查兑换码（码面已大写归一化）。
     *
     * @param c    数据库连接
     * @param code 码面
     * @return 兑换码；不存在时为空
     * @throws SQLException JDBC 失败时
     */
    Optional<RedeemCode> findCodeByCode(Connection c, String code) throws SQLException;

    /**
     * 原子消耗一次兑换码用量（条件 UPDATE：{@code SET used_count = used_count + 1
     * WHERE id=? AND enabled=TRUE AND (max_uses = 0 OR used_count < max_uses)}）。
     *
     * @param c      数据库连接
     * @param codeId 兑换码 id
     * @return 消耗成功返回 true；已用尽/禁用返回 false
     * @throws SQLException JDBC 失败时
     */
    boolean tryConsumeUse(Connection c, long codeId) throws SQLException;

    /**
     * 设置兑换码启用状态（管理员 disable/enable）。
     *
     * @param c       数据库连接
     * @param codeId  兑换码 id
     * @param enabled 目标启用状态
     * @return 实际更新返回 true；兑换码不存在返回 false
     * @throws SQLException JDBC 失败时
     */
    boolean setCodeEnabled(Connection c, long codeId, boolean enabled) throws SQLException;

    /**
     * 记录一次兑换码使用（与用量消耗同事务；每玩家限次校验依据）。
     *
     * @param c           数据库连接
     * @param codeId      兑换码 id
     * @param player      玩家 UUID
     * @param ownershipId 产生的所有权 id（可空）
     * @param usedAt      使用时刻（UTC epoch 毫秒）
     * @throws SQLException JDBC 失败时
     */
    void recordUse(Connection c, long codeId, UUID player, @Nullable Long ownershipId, long usedAt) throws SQLException;

    /**
     * 统计玩家对某兑换码的使用次数（每玩家限次校验用）。
     *
     * @param c      数据库连接
     * @param codeId 兑换码 id
     * @param player 玩家 UUID
     * @return 使用次数
     * @throws SQLException JDBC 失败时
     */
    int countUsesByPlayer(Connection c, long codeId, UUID player) throws SQLException;

    /**
     * 插入称号卡（card_uuid 主键与物品 PDC 同值）。
     *
     * @param c    数据库连接
     * @param card 称号卡
     * @throws SQLException JDBC 失败时
     */
    void insertCard(Connection c, RedeemCard card) throws SQLException;

    /**
     * 按卡片 UUID 查称号卡。
     *
     * @param c        数据库连接
     * @param cardUuid 卡片 UUID
     * @return 称号卡；不存在时为空
     * @throws SQLException JDBC 失败时
     */
    Optional<RedeemCard> findCard(Connection c, UUID cardUuid) throws SQLException;

    /**
     * 原子核销称号卡（条件 UPDATE：{@code WHERE card_uuid=? AND redeemed_at IS NULL}，
     * 单次核销防刷）。
     *
     * @param c          数据库连接
     * @param cardUuid   卡片 UUID
     * @param redeemedBy 核销者 UUID
     * @param now        核销时刻（UTC epoch 毫秒）
     * @return 核销成功返回 true；已核销/不存在返回 false
     * @throws SQLException JDBC 失败时
     */
    boolean tryRedeemCard(Connection c, UUID cardUuid, UUID redeemedBy, long now) throws SQLException;

    /**
     * 回填称号卡核销产生的所有权 id（与核销同事务，grant 之后调用）。
     *
     * @param c           数据库连接
     * @param cardUuid    卡片 UUID
     * @param ownershipId 所有权 id
     * @throws SQLException JDBC 失败时
     */
    void bindCardOwnership(Connection c, UUID cardUuid, long ownershipId) throws SQLException;
}
