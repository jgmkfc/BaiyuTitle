package net.baiyu.title.repository;

import net.baiyu.title.domain.wardrobe.SlotConfig;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 装扮栏位仓储（player_slot / player_slot_pool）。
 * <p>栏位上限来自 config 运行时解析不入库；上限缩水时数据保留、渲染截断。
 */
public interface SlotRepository {

    /**
     * 装载玩家全部栏位配置（按栏位下标升序）。
     *
     * @param c      数据库连接
     * @param player 玩家 UUID
     * @return 栏位配置列表
     * @throws SQLException JDBC 失败时
     */
    List<SlotConfig> load(Connection c, UUID player) throws SQLException;

    /**
     * 插入或更新一条栏位配置（先 UPDATE 后 INSERT 两步式，规避方言 upsert 差异）。
     *
     * @param c      数据库连接
     * @param player 玩家 UUID
     * @param slot   栏位配置
     * @throws SQLException JDBC 失败时
     */
    void upsert(Connection c, UUID player, SlotConfig slot) throws SQLException;

    /**
     * 清除玩家对某称号的一切栏位引用（固定栏位置 NULL + 随机池删行；
     * 失去所有权时同事务调用，不变量 §十 10）。
     *
     * @param c       数据库连接
     * @param player  玩家 UUID
     * @param titleId 称号 id
     * @throws SQLException JDBC 失败时
     */
    void clearTitleEverywhere(Connection c, UUID player, int titleId) throws SQLException;

    /**
     * 清除所有玩家对某称号的一切栏位引用（固定栏位置 NULL + 随机池删行；
     * 称号软删时同事务调用，设计文档 §五 delete 契约"卸下装扮引用"；
     * V2 已预建 idx_player_slot_title / idx_player_slot_pool_title 索引支撑）。
     *
     * @param c       数据库连接
     * @param titleId 称号 id
     * @throws SQLException JDBC 失败时
     */
    void clearTitleForAllPlayers(Connection c, int titleId) throws SQLException;

    /**
     * 装载某栏位的随机池称号 id 集合。
     *
     * @param c         数据库连接
     * @param player    玩家 UUID
     * @param slotIndex 栏位下标
     * @return 称号 id 集合
     * @throws SQLException JDBC 失败时
     */
    Set<Integer> loadPool(Connection c, UUID player, int slotIndex) throws SQLException;

    /**
     * 全量替换某栏位的随机池（先删后插）。
     *
     * @param c         数据库连接
     * @param player    玩家 UUID
     * @param slotIndex 栏位下标
     * @param titleIds  新的称号 id 集合
     * @throws SQLException JDBC 失败时
     */
    void replacePool(Connection c, UUID player, int slotIndex, Set<Integer> titleIds) throws SQLException;
}
