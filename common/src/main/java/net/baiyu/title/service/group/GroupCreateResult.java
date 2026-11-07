package net.baiyu.title.service.group;

import net.baiyu.title.domain.group.PlayerGroup;
import net.baiyu.title.domain.title.Title;

import java.util.Objects;

/**
 * 创建玩家组的结果（{@link net.baiyu.title.service.GroupService#createGroup} 返回，
 * 密封类型，调用方 switch 穷举）。
 */
public sealed interface GroupCreateResult {

    /**
     * 创建成功：组与自动创建的 GROUP 称号（已绑定 groupId）均已落库。
     *
     * @param group 新组（含生成 id）
     * @param title 新组称号（含生成 id；visible=false、tradable=false）
     */
    record Created(PlayerGroup group, Title title) implements GroupCreateResult {

        /** 紧凑构造器：判空。 */
        public Created {
            Objects.requireNonNull(group, "组不能为空");
            Objects.requireNonNull(title, "组称号不能为空");
        }
    }

    /**
     * 组名或称号名非法（组名须匹配 {@code [a-z0-9_]{1,64}}；称号名剥色后非空且 ≤ 50 码点）。
     *
     * @param name 非法的名称
     */
    record InvalidName(String name) implements GroupCreateResult { }

    /**
     * 组名已存在（含软删组——组名列 DB UNIQUE，软删不释放名称）。
     *
     * @param name 冲突的组名
     */
    record DuplicateName(String name) implements GroupCreateResult { }
}
