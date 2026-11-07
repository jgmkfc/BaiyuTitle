package net.baiyu.title.command.argument;

import dev.jorel.commandapi.arguments.Argument;
import dev.jorel.commandapi.arguments.ArgumentSuggestions;
import dev.jorel.commandapi.arguments.StringArgument;
import net.baiyu.title.service.GroupNameCache;

import java.util.Objects;

/**
 * 组名参数工厂：字符串参数 + 组名补全。
 *
 * <p>补全数据源是 {@link GroupNameCache} 的内存快照（补全零 DB IO 红线）；
 * 快照随组建删由命令层刷新，短暂陈旧只影响提示不影响执行——
 * 执行期解析同样走缓存，未知组名回发不存在。</p>
 */
public final class GroupNameArgument {

    /** 组名缓存。 */
    private final GroupNameCache groupNameCache;

    /**
     * 构造组名参数工厂。
     *
     * @param groupNameCache 组名缓存
     */
    public GroupNameArgument(GroupNameCache groupNameCache) {
        this.groupNameCache = Objects.requireNonNull(groupNameCache, "组名缓存不能为空");
    }

    /**
     * 创建一个带组名补全的字符串参数。
     *
     * @param nodeName 参数节点名
     * @return CommandAPI 字符串参数
     */
    public Argument<String> create(String nodeName) {
        return new StringArgument(nodeName).replaceSuggestions(
                ArgumentSuggestions.strings(info -> groupNameCache.names()));
    }
}
