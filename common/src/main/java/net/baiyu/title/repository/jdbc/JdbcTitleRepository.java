package net.baiyu.title.repository.jdbc;

import net.baiyu.title.domain.condition.PurchaseCondition;
import net.baiyu.title.domain.title.Title;
import net.baiyu.title.repository.TableNames;
import net.baiyu.title.repository.TitleRepository;
import net.baiyu.title.repository.codec.StatementBinders;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 称号仓储 JDBC 实现（表 V1：title / title_condition / title_bond_requirement）。
 * <p>{@link #loadAll} 用 3 条 SQL 聚合装载后内存组装；行映射细节见
 * {@link TitleRowMappers} 伴生类。
 */
public final class JdbcTitleRepository implements TitleRepository {

    /** 表名拼接器。 */
    private final TableNames tables;

    /**
     * 构造称号仓储。
     *
     * @param tables 表名拼接器
     */
    public JdbcTitleRepository(TableNames tables) {
        this.tables = Objects.requireNonNull(tables, "表名拼接器不能为空");
    }

    @Override
    public int insert(Connection c, Title t) throws SQLException {
        String sql = "INSERT INTO " + tables.title()
                + " (\"name\", \"name_plain\", \"category\", \"combine_logic\", \"visible\", \"tradable\","
                + " \"rarity\", \"description\", \"icon_material\", \"hold_duration_days\", \"limit_mode\","
                + " \"window_start\", \"window_end\", \"total_cap\", \"group_id\","
                + " \"sold_count\", \"created_by\", \"created_by_name\", \"created_at\", \"updated_at\", \"deleted_at\")"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        int titleId;
        try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            int i = TitleRowMappers.bindEditableColumns(ps, 1, t);
            ps.setInt(i++, t.soldCount());
            StatementBinders.setNullableUuid(ps, i++, t.createdBy());
            StatementBinders.setNullableString(ps, i++, t.createdByName());
            ps.setLong(i++, t.createdAt());
            ps.setLong(i++, t.updatedAt());
            StatementBinders.setNullableLong(ps, i, t.deletedAt());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("插入称号后未取得生成主键");
                }
                titleId = keys.getInt(1);
            }
        }
        // 聚合内的条件与羁绊需求随主行一并落库
        replaceConditions(c, titleId, t.conditions());
        replaceBondRequirements(c, titleId, t.bondRequirements());
        return titleId;
    }

    @Override
    public void update(Connection c, Title t) throws SQLException {
        String sql = "UPDATE " + tables.title() + " SET"
                + " \"name\"=?, \"name_plain\"=?, \"category\"=?, \"combine_logic\"=?, \"visible\"=?, \"tradable\"=?,"
                + " \"rarity\"=?, \"description\"=?, \"icon_material\"=?, \"hold_duration_days\"=?, \"limit_mode\"=?,"
                + " \"window_start\"=?, \"window_end\"=?, \"total_cap\"=?, \"group_id\"=?, \"updated_at\"=?"
                + " WHERE \"id\"=?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            int i = TitleRowMappers.bindEditableColumns(ps, 1, t);
            ps.setLong(i++, t.updatedAt());
            ps.setInt(i, t.id());
            ps.executeUpdate();
        }
    }

    @Override
    public void softDelete(Connection c, int titleId, long now) throws SQLException {
        String sql = "UPDATE " + tables.title()
                + " SET \"deleted_at\"=?, \"updated_at\"=? WHERE \"id\"=? AND \"deleted_at\" IS NULL";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, now);
            ps.setLong(2, now);
            ps.setInt(3, titleId);
            ps.executeUpdate();
        }
    }

    @Override
    public List<Title> loadAll(Connection c, boolean includeDeleted) throws SQLException {
        Map<Integer, List<PurchaseCondition>> conditionsByTitle = loadAllConditions(c);
        Map<Integer, Set<Integer>> bondsByTitle = loadAllBondRequirements(c);

        String sql = "SELECT * FROM " + tables.title()
                + (includeDeleted ? "" : " WHERE \"deleted_at\" IS NULL")
                + " ORDER BY \"id\"";
        List<Title> titles = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                int titleId = rs.getInt("id");
                titles.add(TitleRowMappers.mapTitle(rs,
                        conditionsByTitle.getOrDefault(titleId, List.of()),
                        bondsByTitle.getOrDefault(titleId, Set.of())));
            }
        }
        return titles;
    }

    /**
     * 一次性装载全部购买条件，按称号 id 分组（loadAll 的第 2 条 SQL）。
     */
    private Map<Integer, List<PurchaseCondition>> loadAllConditions(Connection c) throws SQLException {
        // 按主键排序保证条件列表顺序确定（落库顺序），SQLite 等方言不保证无序查询的返回顺序
        String sql = "SELECT * FROM " + tables.titleCondition() + " ORDER BY \"id\"";
        Map<Integer, List<PurchaseCondition>> byTitle = new HashMap<>();
        try (PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                byTitle.computeIfAbsent(rs.getInt("title_id"), key -> new ArrayList<>())
                        .add(TitleRowMappers.mapCondition(rs));
            }
        }
        return byTitle;
    }

    /**
     * 一次性装载全部羁绊需求，按羁绊称号 id 分组（loadAll 的第 3 条 SQL）。
     */
    private Map<Integer, Set<Integer>> loadAllBondRequirements(Connection c) throws SQLException {
        String sql = "SELECT \"bond_title_id\", \"required_title_id\" FROM " + tables.titleBondRequirement();
        Map<Integer, Set<Integer>> byBond = new HashMap<>();
        try (PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                byBond.computeIfAbsent(rs.getInt("bond_title_id"), key -> new HashSet<>())
                        .add(rs.getInt("required_title_id"));
            }
        }
        return byBond;
    }

    @Override
    public boolean tryReserveSoldCount(Connection c, int titleId) throws SQLException {
        String sql = "UPDATE " + tables.title() + " SET \"sold_count\" = \"sold_count\" + 1"
                + " WHERE \"id\"=? AND (\"limit_mode\" <> 'TOTAL' OR \"sold_count\" < \"total_cap\")";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, titleId);
            return ps.executeUpdate() == 1;
        }
    }

    @Override
    public void releaseSoldCount(Connection c, int titleId) throws SQLException {
        String sql = "UPDATE " + tables.title() + " SET \"sold_count\" = \"sold_count\" - 1"
                + " WHERE \"id\"=? AND \"sold_count\" > 0";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, titleId);
            ps.executeUpdate();
        }
    }

    @Override
    public void replaceConditions(Connection c, int titleId, List<PurchaseCondition> conds) throws SQLException {
        try (PreparedStatement delete = c.prepareStatement(
                "DELETE FROM " + tables.titleCondition() + " WHERE \"title_id\"=?")) {
            delete.setInt(1, titleId);
            delete.executeUpdate();
        }
        if (conds.isEmpty()) {
            return;
        }
        String insertSql = "INSERT INTO " + tables.titleCondition()
                + " (\"title_id\", \"cond_type\", \"vault_amount\", \"coin_amount\","
                + " \"item_data\", \"item_amount\", \"permission_node\")"
                + " VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement insert = c.prepareStatement(insertSql)) {
            for (PurchaseCondition condition : conds) {
                TitleRowMappers.bindConditionInsert(insert, titleId, condition);
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    @Override
    public void replaceBondRequirements(Connection c, int bondId, Set<Integer> required) throws SQLException {
        try (PreparedStatement delete = c.prepareStatement(
                "DELETE FROM " + tables.titleBondRequirement() + " WHERE \"bond_title_id\"=?")) {
            delete.setInt(1, bondId);
            delete.executeUpdate();
        }
        if (required.isEmpty()) {
            return;
        }
        String insertSql = "INSERT INTO " + tables.titleBondRequirement()
                + " (\"bond_title_id\", \"required_title_id\") VALUES (?, ?)";
        try (PreparedStatement insert = c.prepareStatement(insertSql)) {
            for (int requiredTitleId : required) {
                insert.setInt(1, bondId);
                insert.setInt(2, requiredTitleId);
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }
}
