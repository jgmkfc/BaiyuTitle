package net.baiyu.title.repository.jdbc;

import net.baiyu.title.domain.wardrobe.SlotConfig;
import net.baiyu.title.domain.wardrobe.SlotMode;
import net.baiyu.title.repository.SlotRepository;
import net.baiyu.title.repository.TableNames;
import net.baiyu.title.repository.codec.RowMappers;
import net.baiyu.title.repository.codec.StatementBinders;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 装扮栏位仓储 JDBC 实现（表 V2：player_slot / player_slot_pool）。
 * <p>upsert 用先 UPDATE 后 INSERT 两步式，规避四方言 upsert 语法差异
 * （写操作单线程串行，无并发插入竞态）。
 */
public final class JdbcSlotRepository implements SlotRepository {

    /** 表名拼接器。 */
    private final TableNames tables;

    /**
     * 构造栏位仓储。
     *
     * @param tables 表名拼接器
     */
    public JdbcSlotRepository(TableNames tables) {
        this.tables = Objects.requireNonNull(tables, "表名拼接器不能为空");
    }

    @Override
    public List<SlotConfig> load(Connection c, UUID player) throws SQLException {
        String sql = "SELECT \"slot_index\", \"enabled\", \"mode\", \"title_id\" FROM " + tables.playerSlot()
                + " WHERE \"player_uuid\"=? ORDER BY \"slot_index\"";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            StatementBinders.setUuid(ps, 1, player);
            List<SlotConfig> slots = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    slots.add(new SlotConfig(
                            rs.getInt("slot_index"),
                            rs.getBoolean("enabled"),
                            SlotMode.fromString(rs.getString("mode")),
                            RowMappers.getNullableInt(rs, "title_id")));
                }
            }
            return slots;
        }
    }

    @Override
    public void upsert(Connection c, UUID player, SlotConfig slot) throws SQLException {
        long now = System.currentTimeMillis();
        String updateSql = "UPDATE " + tables.playerSlot()
                + " SET \"enabled\"=?, \"mode\"=?, \"title_id\"=?, \"updated_at\"=?"
                + " WHERE \"player_uuid\"=? AND \"slot_index\"=?";
        try (PreparedStatement update = c.prepareStatement(updateSql)) {
            update.setBoolean(1, slot.enabled());
            update.setString(2, slot.mode().name());
            StatementBinders.setNullableInt(update, 3, slot.titleId());
            update.setLong(4, now);
            StatementBinders.setUuid(update, 5, player);
            update.setInt(6, slot.slotIndex());
            if (update.executeUpdate() > 0) {
                return;
            }
        }
        String insertSql = "INSERT INTO " + tables.playerSlot()
                + " (\"player_uuid\", \"slot_index\", \"enabled\", \"mode\", \"title_id\", \"updated_at\")"
                + " VALUES (?, ?, ?, ?, ?, ?)";
        try (PreparedStatement insert = c.prepareStatement(insertSql)) {
            StatementBinders.setUuid(insert, 1, player);
            insert.setInt(2, slot.slotIndex());
            insert.setBoolean(3, slot.enabled());
            insert.setString(4, slot.mode().name());
            StatementBinders.setNullableInt(insert, 5, slot.titleId());
            insert.setLong(6, now);
            insert.executeUpdate();
        } catch (SQLException e) {
            // 兜底 MySQL useAffectedRows=true：值完全相同的 UPDATE 报 0 行会误走
            // INSERT 撞主键——此时行已是目标状态，冲突静默忽略（对齐 coin 仓储的两步式边界）
            if (!JdbcSupport.isUniqueViolation(e)) {
                throw e;
            }
        }
    }

    @Override
    public void clearTitleForAllPlayers(Connection c, int titleId) throws SQLException {
        String clearSlotSql = "UPDATE " + tables.playerSlot()
                + " SET \"title_id\"=NULL, \"updated_at\"=? WHERE \"title_id\"=?";
        try (PreparedStatement clearSlot = c.prepareStatement(clearSlotSql)) {
            clearSlot.setLong(1, System.currentTimeMillis());
            clearSlot.setInt(2, titleId);
            clearSlot.executeUpdate();
        }
        String clearPoolSql = "DELETE FROM " + tables.playerSlotPool() + " WHERE \"title_id\"=?";
        try (PreparedStatement clearPool = c.prepareStatement(clearPoolSql)) {
            clearPool.setInt(1, titleId);
            clearPool.executeUpdate();
        }
    }

    @Override
    public void clearTitleEverywhere(Connection c, UUID player, int titleId) throws SQLException {
        String clearSlotSql = "UPDATE " + tables.playerSlot()
                + " SET \"title_id\"=NULL, \"updated_at\"=? WHERE \"player_uuid\"=? AND \"title_id\"=?";
        try (PreparedStatement clearSlot = c.prepareStatement(clearSlotSql)) {
            clearSlot.setLong(1, System.currentTimeMillis());
            StatementBinders.setUuid(clearSlot, 2, player);
            clearSlot.setInt(3, titleId);
            clearSlot.executeUpdate();
        }
        String clearPoolSql = "DELETE FROM " + tables.playerSlotPool()
                + " WHERE \"player_uuid\"=? AND \"title_id\"=?";
        try (PreparedStatement clearPool = c.prepareStatement(clearPoolSql)) {
            StatementBinders.setUuid(clearPool, 1, player);
            clearPool.setInt(2, titleId);
            clearPool.executeUpdate();
        }
    }

    @Override
    public Set<Integer> loadPool(Connection c, UUID player, int slotIndex) throws SQLException {
        String sql = "SELECT \"title_id\" FROM " + tables.playerSlotPool()
                + " WHERE \"player_uuid\"=? AND \"slot_index\"=?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            StatementBinders.setUuid(ps, 1, player);
            ps.setInt(2, slotIndex);
            Set<Integer> pool = new HashSet<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    pool.add(rs.getInt("title_id"));
                }
            }
            return pool;
        }
    }

    @Override
    public void replacePool(Connection c, UUID player, int slotIndex, Set<Integer> titleIds) throws SQLException {
        String deleteSql = "DELETE FROM " + tables.playerSlotPool()
                + " WHERE \"player_uuid\"=? AND \"slot_index\"=?";
        try (PreparedStatement delete = c.prepareStatement(deleteSql)) {
            StatementBinders.setUuid(delete, 1, player);
            delete.setInt(2, slotIndex);
            delete.executeUpdate();
        }
        if (titleIds.isEmpty()) {
            return;
        }
        String insertSql = "INSERT INTO " + tables.playerSlotPool()
                + " (\"player_uuid\", \"slot_index\", \"title_id\") VALUES (?, ?, ?)";
        try (PreparedStatement insert = c.prepareStatement(insertSql)) {
            for (int titleId : titleIds) {
                StatementBinders.setUuid(insert, 1, player);
                insert.setInt(2, slotIndex);
                insert.setInt(3, titleId);
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }
}
