package net.baiyu.title.service.group;

import org.jetbrains.annotations.Nullable;

/**
 * 删除玩家组的结果（{@link net.baiyu.title.service.GroupService#deleteGroup} 返回，
 * 密封类型，调用方 switch 穷举）。
 */
public sealed interface GroupDeleteResult {

    /**
     * 删除成功：组与组称号均软删，全部持有者的该称号 ACTIVE 所有权已收回
     * （历史行完整保留，红线"软删不回收组称号历史"）。
     *
     * @param groupId      组 id
     * @param titleId      组称号 id（组称号已先行单独软删时为空）
     * @param revokedCount 本次收回的 ACTIVE 所有权条数
     */
    record Deleted(int groupId, @Nullable Integer titleId, int revokedCount)
            implements GroupDeleteResult { }

    /**
     * 组不存在或已软删。
     *
     * @param groupId 组 id
     */
    record NotFound(int groupId) implements GroupDeleteResult { }
}
