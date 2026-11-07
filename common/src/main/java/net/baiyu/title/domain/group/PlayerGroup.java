package net.baiyu.title.domain.group;

import java.util.Objects;

/**
 * 玩家组（对应 {@code player_group} 表）。
 * <p>组名限 {@code [a-z0-9_]}（服务层校验）；创建组时自动建一个组称号并注册组权限节点；
 * 有效成员 = member 表 ∪ 权限持有者。
 *
 * @param id          主键（未落库时为 0）
 * @param name        组名（唯一，服务层限 [a-z0-9_]）
 * @param displayName 展示名（可空）
 * @param createdBy   创建者名称（可空；控制台创建为空）
 * @param createdAt   创建时刻（UTC epoch 毫秒）
 * @param deletedAt   软删时刻（可空；非空即已软删）
 */
public record PlayerGroup(
        int id,
        String name,
        String displayName,
        String createdBy,
        long createdAt,
        Long deletedAt) {

    /**
     * 紧凑构造器：组名判空。
     */
    public PlayerGroup {
        Objects.requireNonNull(name, "组名不能为空");
        if (name.isBlank()) {
            throw new IllegalArgumentException("组名不能为空白");
        }
    }

    /**
     * 组成员资格权限节点（持有该权限即视为有效成员）。
     *
     * @return 形如 {@code title.group.<name>} 的权限节点
     */
    public String permissionNode() {
        return permissionNodeOf(name);
    }

    /**
     * 组名 → 权限节点（节点格式唯一实现；供仅有组名、尚无组对象的调用点使用）。
     *
     * @param groupName 组名
     * @return 形如 {@code title.group.<name>} 的权限节点
     */
    public static String permissionNodeOf(String groupName) {
        return "title.group." + Objects.requireNonNull(groupName, "组名不能为空");
    }

    /**
     * 是否已软删。
     *
     * @return 已软删返回 true
     */
    public boolean isDeleted() {
        return deletedAt != null;
    }
}
