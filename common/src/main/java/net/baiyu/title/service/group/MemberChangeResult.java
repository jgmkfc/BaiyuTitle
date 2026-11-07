package net.baiyu.title.service.group;

import org.jetbrains.annotations.Nullable;

/**
 * 组成员变更结果（{@link net.baiyu.title.service.GroupService#addMember} /
 * {@code removeMember} 返回，密封类型，调用方 switch 穷举）。
 */
public sealed interface MemberChangeResult {

    /**
     * 加入成功：member 行已插入，组称号已授予（恒 ACTIVE 直至移出）。
     *
     * @param groupId     组 id
     * @param ownershipId 新所有权 id（此前已持有组称号——如权限对账补授过——时为空）
     */
    record Added(int groupId, @Nullable Long ownershipId) implements MemberChangeResult { }

    /**
     * 已在组内（member 表已有记录，本次零副作用）。
     *
     * @param groupId 组 id
     */
    record AlreadyMember(int groupId) implements MemberChangeResult { }

    /**
     * 移出成功：member 行已删除；组称号已收回并卸下全部装扮引用。
     * <p>若玩家仍持有组权限节点，下次 reconcile 会按"有效成员 = member 表 ∪
     * 权限持有者"重新补授。
     *
     * @param groupId      组 id
     * @param titleRevoked 是否实际收回了 ACTIVE 组称号所有权
     */
    record Removed(int groupId, boolean titleRevoked) implements MemberChangeResult { }

    /**
     * 玩家本就不在 member 表中（零副作用）。
     *
     * @param groupId 组 id
     */
    record NotMember(int groupId) implements MemberChangeResult { }

    /**
     * 组不存在或已软删。
     *
     * @param groupId 组 id
     */
    record GroupNotFound(int groupId) implements MemberChangeResult { }
}
