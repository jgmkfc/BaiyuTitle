package net.baiyu.title.service;

import net.baiyu.title.cache.PlayerProfileCache;
import net.baiyu.title.domain.coin.CoinChangeReason;
import net.baiyu.title.domain.coin.CoinLogEntry;
import net.baiyu.title.repository.CoinRepository;
import net.baiyu.title.repository.TransactionRunner;
import net.baiyu.title.service.executor.DbExecutor;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 称号币服务：余额查询与管理员 give/take/set。
 * <p>不变量 §十 9：余额恒 ≥ 0（tryAdd 条件 UPDATE 禁透支）；
 * 每次变动必有一条 coin_log（同事务）。
 * <p>take 余额不足返回失败结果（{@link CoinResult#success()} = false），不抛异常。
 */
public final class CoinService {

    /** 称号币仓储。 */
    private final CoinRepository coinRepository;

    /** 事务执行器。 */
    private final TransactionRunner txRunner;

    /** 数据库执行器。 */
    private final DbExecutor dbExecutor;

    /** 玩家档案缓存（余额读缓存优先 + 提交后更新）。 */
    private final PlayerProfileCache profileCache;

    /**
     * 构造称号币服务。
     *
     * @param coinRepository 称号币仓储
     * @param txRunner       事务执行器
     * @param dbExecutor     数据库执行器
     * @param profileCache   玩家档案缓存
     */
    public CoinService(CoinRepository coinRepository, TransactionRunner txRunner,
                       DbExecutor dbExecutor, PlayerProfileCache profileCache) {
        this.coinRepository = Objects.requireNonNull(coinRepository, "称号币仓储不能为空");
        this.txRunner = Objects.requireNonNull(txRunner, "事务执行器不能为空");
        this.dbExecutor = Objects.requireNonNull(dbExecutor, "数据库执行器不能为空");
        this.profileCache = Objects.requireNonNull(profileCache, "玩家档案缓存不能为空");
    }

    /**
     * 查询余额：在线玩家直读档案缓存（零 IO）；离线玩家走读池查库。
     *
     * @param player 玩家 UUID
     * @return 余额
     */
    public CompletableFuture<Long> getBalance(UUID player) {
        Objects.requireNonNull(player, "玩家 UUID 不能为空");
        Optional<PlayerProfileCache.PlayerProfile> profile = profileCache.get(player);
        if (profile.isPresent()) {
            return CompletableFuture.completedFuture(profile.get().coinBalance());
        }
        return dbExecutor.submitRead(() ->
                txRunner.withConnection(c -> coinRepository.getBalance(c, player)));
    }

    /**
     * 管理员增发（amount &gt; 0）。
     *
     * @param player     玩家 UUID
     * @param playerName 玩家名（缓存列）
     * @param amount     增发数量（正数）
     * @param actor      操作者
     * @return 恒成功的结果（含新余额）
     */
    public CompletableFuture<CoinResult> give(UUID player, String playerName, long amount, Actor actor) {
        requirePositive(amount);
        return mutate(player, playerName, actor, amount, CoinChangeReason.ADMIN_GIVE);
    }

    /**
     * 管理员扣减（amount &gt; 0）；余额不足返回失败结果（余额不变），不抛异常。
     *
     * @param player     玩家 UUID
     * @param playerName 玩家名（缓存列）
     * @param amount     扣减数量（正数）
     * @param actor      操作者
     * @return 结果（success=false 表示余额不足，balance 为当前余额）
     */
    public CompletableFuture<CoinResult> take(UUID player, String playerName, long amount, Actor actor) {
        requirePositive(amount);
        return mutate(player, playerName, actor, -amount, CoinChangeReason.ADMIN_TAKE);
    }

    /**
     * 管理员直接设置余额（value ≥ 0）；流水 delta = 新值 − 旧值。
     *
     * @param player     玩家 UUID
     * @param playerName 玩家名（缓存列）
     * @param value      目标余额（≥ 0）
     * @param actor      操作者
     * @return 恒成功的结果（含新余额）
     */
    public CompletableFuture<CoinResult> set(UUID player, String playerName, long value, Actor actor) {
        Objects.requireNonNull(player, "玩家 UUID 不能为空");
        Objects.requireNonNull(playerName, "玩家名不能为空");
        Objects.requireNonNull(actor, "操作者不能为空");
        if (value < 0) {
            throw new IllegalArgumentException("目标余额不能为负: " + value);
        }
        return dbExecutor.submitWrite(() -> {
            long now = System.currentTimeMillis();
            long balance = txRunner.inTransaction(c -> {
                long previous = coinRepository.getBalance(c, player);
                coinRepository.set(c, player, playerName, value);
                coinRepository.log(c, new CoinLogEntry(0, player, value - previous, value,
                        CoinChangeReason.ADMIN_SET, actor.name(), null, now));
                return value;
            });
            updateCachedBalance(player, balance);
            return CoinResult.ok(balance);
        });
    }

    /**
     * 变动骨架：写线程事务 ensureRow + tryAdd + coin_log → 提交后更新缓存。
     * <p>tryAdd 失败（余额不足）时事务只留下种子行，返回失败结果与当前余额。
     */
    private CompletableFuture<CoinResult> mutate(UUID player, String playerName, Actor actor,
                                                 long delta, CoinChangeReason reason) {
        Objects.requireNonNull(player, "玩家 UUID 不能为空");
        Objects.requireNonNull(playerName, "玩家名不能为空");
        Objects.requireNonNull(actor, "操作者不能为空");
        return dbExecutor.submitWrite(() -> {
            long now = System.currentTimeMillis();
            CoinResult result = txRunner.inTransaction(c -> {
                coinRepository.ensureRow(c, player, playerName, now);
                if (!coinRepository.tryAdd(c, player, delta)) {
                    return CoinResult.insufficient(coinRepository.getBalance(c, player));
                }
                long after = coinRepository.getBalance(c, player);
                coinRepository.log(c, new CoinLogEntry(0, player, delta, after,
                        reason, actor.name(), null, now));
                return CoinResult.ok(after);
            });
            if (result.success()) {
                updateCachedBalance(player, result.balance());
            }
            return result;
        });
    }

    /**
     * 提交后更新档案缓存中的余额（离线玩家静默跳过）。
     */
    private void updateCachedBalance(UUID player, long balance) {
        profileCache.updateIfPresent(player, profile -> profile.withCoinBalance(balance));
    }

    /**
     * 正数校验（give/take 的数量参数）。
     */
    private static void requirePositive(long amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("变动数量必须为正数: " + amount);
        }
    }

    /**
     * 称号币操作结果。
     *
     * @param success 是否成功（false = 余额不足）
     * @param balance 操作后（失败时为当前）余额
     */
    public record CoinResult(boolean success, long balance) {

        /**
         * 成功结果。
         *
         * @param balance 新余额
         * @return 成功结果
         */
        public static CoinResult ok(long balance) {
            return new CoinResult(true, balance);
        }

        /**
         * 余额不足的失败结果。
         *
         * @param balance 当前余额
         * @return 失败结果
         */
        public static CoinResult insufficient(long balance) {
            return new CoinResult(false, balance);
        }
    }
}
