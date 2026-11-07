package net.baiyu.title.domain.condition;

import java.util.Objects;

/**
 * 权限节点购买条件（对应 V1 表列 permission_node VARCHAR(255)）。
 * <p>OR 组合逻辑下玩家持有该权限即走免单分支。
 *
 * @param node 权限节点
 */
public record PermissionCondition(String node) implements PurchaseCondition {

    /**
     * 校验权限节点非空白。
     */
    public PermissionCondition {
        Objects.requireNonNull(node, "权限节点不能为空");
        if (node.isBlank()) {
            throw new IllegalArgumentException("权限节点不能为空白");
        }
    }

    @Override
    public ConditionType type() {
        return ConditionType.PERMISSION;
    }
}
