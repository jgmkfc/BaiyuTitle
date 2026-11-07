package net.baiyu.title.repository;

import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;

/**
 * 审计日志仓储（audit_log，只追加）。
 * <p>审计写入与业务变更同事务，保证审计与事实一致。
 */
public interface AuditRepository {

    /**
     * 插入一条审计日志。
     *
     * @param c 数据库连接
     * @param e 审计条目
     * @throws SQLException JDBC 失败时
     */
    void insert(Connection c, AuditEntry e) throws SQLException;

    /**
     * 审计条目（对齐 V5 表 {@code audit_log} 全列；主键自增不入模型）。
     *
     * @param eventType   事件类型（如 GRANT / REVOKE / PURCHASE）
     * @param actorUuid   操作者 UUID（可空；控制台为空）
     * @param actorName   操作者名称（管理员名或 SYSTEM）
     * @param targetUuid  目标玩家 UUID（可空）
     * @param titleId     相关称号 id（可空）
     * @param ownershipId 相关所有权 id（可空）
     * @param refTable    引用表名（可空；如 purchase_order）
     * @param refId       引用行 id（可空）
     * @param detail      细节 JSON 文本（可空）
     * @param createdAt   记录时刻（UTC epoch 毫秒）
     */
    record AuditEntry(
            String eventType,
            @Nullable UUID actorUuid,
            String actorName,
            @Nullable UUID targetUuid,
            @Nullable Integer titleId,
            @Nullable Long ownershipId,
            @Nullable String refTable,
            @Nullable Long refId,
            @Nullable String detail,
            long createdAt) {

        /**
         * 紧凑构造器：必填字段判空。
         */
        public AuditEntry {
            Objects.requireNonNull(eventType, "事件类型不能为空");
            Objects.requireNonNull(actorName, "操作者名称不能为空");
        }
    }
}
