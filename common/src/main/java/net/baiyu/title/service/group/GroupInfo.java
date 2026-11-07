package net.baiyu.title.service.group;

import net.baiyu.title.domain.group.GroupMember;
import net.baiyu.title.domain.group.PlayerGroup;
import net.baiyu.title.domain.title.Title;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * 玩家组详情视图（{@code /byt group info} 数据源）。
 *
 * @param group   组
 * @param title   组称号（可空——组称号被单独软删时为空）
 * @param members member 表成员列表（不含仅凭权限的有效成员——权限持有者
 *                需在线才能枚举，由 paper 侧按需补充展示）
 */
public record GroupInfo(PlayerGroup group, @Nullable Title title, List<GroupMember> members) {

    /**
     * 紧凑构造器：判空 + 防御性拷贝。
     */
    public GroupInfo {
        Objects.requireNonNull(group, "组不能为空");
        members = members == null ? List.of() : List.copyOf(members);
    }
}
