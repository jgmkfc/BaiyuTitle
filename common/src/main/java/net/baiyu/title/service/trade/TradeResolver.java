package net.baiyu.title.service.trade;

import net.baiyu.title.domain.trade.Trade;
import net.baiyu.title.domain.trade.TradeStatus;
import net.baiyu.title.repository.AuditRepository;
import net.baiyu.title.repository.TradeRepository;
import net.baiyu.title.repository.TransactionRunner;
import net.baiyu.title.service.Actor;
import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

/**
 * 交易解决器（TradeService 的写线程协作件）：拒绝/撤回/管理员销毁与过期扫描。
 * <p>拒绝 → REJECTED（限目标方）；发起方撤回与管理员销毁 → DESTROYED。
 * 全部走条件迁移（PENDING 唯一可迁出，不变量 §十 8），不动任何所有权。
 */
public final class TradeResolver {

    /** 过期扫描单轮批大小。 */
    private static final int SWEEP_BATCH_SIZE = 200;

    /** 交易仓储。 */
    private final TradeRepository tradeRepository;

    /** 审计仓储。 */
    private final AuditRepository auditRepository;

    /** 事务执行器。 */
    private final TransactionRunner txRunner;

    /**
     * 构造交易解决器。
     *
     * @param tradeRepository 交易仓储
     * @param auditRepository 审计仓储
     * @param txRunner        事务执行器
     */
    public TradeResolver(TradeRepository tradeRepository, AuditRepository auditRepository,
                         TransactionRunner txRunner) {
        this.tradeRepository = Objects.requireNonNull(tradeRepository, "交易仓储不能为空");
        this.auditRepository = Objects.requireNonNull(auditRepository, "审计仓储不能为空");
        this.txRunner = Objects.requireNonNull(txRunner, "事务执行器不能为空");
    }

    /** 解决操作要求的操作者角色。 */
    public enum Role {

        /** 仅目标方（拒绝）。 */
        TARGET,

        /** 仅发起方（撤回）。 */
        INITIATOR,

        /** 不限（管理员销毁）。 */
        ANY
    }

    /**
     * 把一笔 PENDING 交易解决为终态（写线程调用；单事务迁移 + 审计）。
     *
     * @param tradeId    交易 id
     * @param actor      操作者
     * @param role       操作者必须扮演的交易方角色
     * @param to         目标终态（REJECTED / DESTROYED）
     * @param eventType  审计事件类型
     * @param failReason 失败原因（写入 trade.fail_reason；可空）
     * @return 解决结果
     */
    public ResolveResult resolve(long tradeId, Actor actor, Role role, TradeStatus to,
                                 String eventType, @Nullable String failReason) {
        long now = System.currentTimeMillis();
        return txRunner.inTransaction(c -> {
            Trade trade = tradeRepository.findWithItems(c, tradeId).orElse(null);
            if (trade == null) {
                return new ResolveResult.UnknownTrade(tradeId);
            }
            if (!trade.isPending()) {
                return new ResolveResult.AlreadyResolved(tradeId, trade.status());
            }
            if (!hasRole(trade, actor, role)) {
                return new ResolveResult.NotParticipant(tradeId);
            }
            if (!tradeRepository.transition(c, tradeId, TradeStatus.PENDING, to,
                    actor.name(), failReason, now)) {
                return new ResolveResult.AlreadyResolved(tradeId, statusOf(c, tradeId));
            }
            auditRepository.insert(c, new AuditRepository.AuditEntry(eventType, actor.uuid(),
                    actor.name(), trade.initiatorUuid(), null, null, "trade", tradeId, failReason, now));
            Trade resolved = tradeRepository.findWithItems(c, tradeId).orElseThrow(
                    () -> new IllegalStateException("解决后交易单不可见: " + tradeId));
            return new ResolveResult.Resolved(resolved);
        });
    }

    /**
     * 过期扫描（写线程调用）：批量把已过期的 PENDING 迁为 EXPIRED
     * （独立存储、展示归入"已拒绝"）。逐笔独立事务，单笔失败不拖垮整批。
     *
     * @param now 当前时刻（UTC epoch 毫秒）
     * @return 本轮成功收敛的交易笔数
     */
    public int sweepExpired(long now) {
        List<Long> expired = txRunner.withConnection(c ->
                tradeRepository.pickExpiredPending(c, now, SWEEP_BATCH_SIZE));
        int converged = 0;
        for (long tradeId : expired) {
            boolean done = txRunner.inTransaction(c -> {
                if (!tradeRepository.transition(c, tradeId, TradeStatus.PENDING,
                        TradeStatus.EXPIRED, Actor.system().name(), "EXPIRED", now)) {
                    return false;
                }
                auditRepository.insert(c, new AuditRepository.AuditEntry("TRADE_EXPIRE",
                        null, Actor.system().name(), null, null, null, "trade", tradeId,
                        "EXPIRED", now));
                return true;
            });
            if (done) {
                converged++;
            }
        }
        return converged;
    }

    /**
     * 判断操作者是否扮演要求的交易方角色。
     */
    private static boolean hasRole(Trade trade, Actor actor, Role role) {
        return switch (role) {
            case TARGET -> trade.targetUuid().equals(actor.uuid());
            case INITIATOR -> trade.initiatorUuid().equals(actor.uuid());
            case ANY -> true;
        };
    }

    /**
     * 读取交易当前状态（条件迁移 0 行命中后的回执用）。
     */
    private TradeStatus statusOf(Connection c, long tradeId) throws SQLException {
        return tradeRepository.findWithItems(c, tradeId).map(Trade::status)
                .orElseThrow(() -> new IllegalStateException("交易单不可见: " + tradeId));
    }
}
