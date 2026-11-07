package net.baiyu.title.service;

import net.baiyu.title.domain.group.PlayerGroup;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 组名快照缓存测试：补全零 DB IO 红线的数据源——
 * 软删过滤、组名字典序、双向查询与整体替换语义。
 */
class GroupNameCacheTest {

    /** 构造测试组。 */
    private static PlayerGroup group(int id, String name, Long deletedAt) {
        return new PlayerGroup(id, name, null, "console", 0L, deletedAt);
    }

    /** 空缓存：无组名、双向查询皆空。 */
    @Test
    void emptyCacheHasNoNames() {
        GroupNameCache cache = new GroupNameCache();
        assertArrayEquals(new String[0], cache.names());
        assertTrue(cache.byName("vip").isEmpty());
        assertTrue(cache.byId(1).isEmpty());
    }

    /** 装载后：软删组被过滤，组名按字典序，byName/byId 双向命中。 */
    @Test
    void replaceAllFiltersDeletedAndSortsNames() {
        GroupNameCache cache = new GroupNameCache();
        cache.replaceAll(List.of(
                group(3, "vip", null),
                group(1, "admin_team", null),
                group(2, "ghost", 123L)));
        assertArrayEquals(new String[]{"admin_team", "vip"}, cache.names());
        assertEquals(2, cache.all().size());
        assertEquals(3, cache.byName("vip").orElseThrow().id());
        assertEquals("admin_team", cache.byId(1).orElseThrow().name());
        assertTrue(cache.byName("ghost").isEmpty(), "软删组不参与补全与资格判定");
        assertTrue(cache.byId(2).isEmpty());
    }

    /** 再次装载：整体替换，旧组不再可见（删除组后的刷新语义）。 */
    @Test
    void replaceAllReplacesWholeSnapshot() {
        GroupNameCache cache = new GroupNameCache();
        cache.replaceAll(List.of(group(1, "old_guild", null)));
        cache.replaceAll(List.of(group(2, "new_guild", null)));
        assertArrayEquals(new String[]{"new_guild"}, cache.names());
        assertTrue(cache.byName("old_guild").isEmpty());
        assertTrue(cache.byId(1).isEmpty());
    }

    /** 权限节点视图：经组对象取节点（与登录快照收集口径一致）。 */
    @Test
    void permissionNodeResolvesFromCachedGroup() {
        GroupNameCache cache = new GroupNameCache();
        cache.replaceAll(List.of(group(7, "builders", null)));
        assertEquals("title.group.builders",
                cache.byId(7).orElseThrow().permissionNode());
        assertEquals("title.group.builders", PlayerGroup.permissionNodeOf("builders"));
    }
}
