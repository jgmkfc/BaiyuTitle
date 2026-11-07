package net.baiyu.title.bootstrap;

import net.baiyu.title.BaiYuTitle;
import net.baiyu.title.cache.PlayerProfileCache;
import net.baiyu.title.cache.TitleCatalogCache;
import net.baiyu.title.repository.TransactionRunner;
import net.baiyu.title.service.BondNotifyHook;
import net.baiyu.title.service.BondService;
import net.baiyu.title.service.GroupAccessBridge;
import net.baiyu.title.service.GroupNameCache;
import net.baiyu.title.service.GroupPermissionRegistrar;
import net.baiyu.title.service.GroupService;
import net.baiyu.title.service.OwnershipService;
import net.baiyu.title.service.executor.DbExecutor;
import net.baiyu.title.translation.Lang;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import org.bukkit.Bukkit;

import java.util.concurrent.CompletableFuture;

/**
 * 组/羁绊链装配切片（{@link PluginAssembly} 的内部协作者）。
 *
 * <p>集中构造：羁绊获得通知钩子 → {@link BondService}（全插件唯一实例）→
 * 授予钩子组合 {@link #grantHook}（Post 事件桥接 + 羁绊检测，各授予入口
 * 统一指向它；羁绊自动授予经 downstream 同样先发事件再通知）→
 * {@link GroupService} → 组名快照缓存（补全零 DB IO）→ 权限节点注册器 →
 * 装扮成员资格桥接。</p>
 *
 * @param bondService         羁绊服务（集齐检测唯一实例）
 * @param grantHook           授予提交后钩子组合（事件桥接 + 羁绊检测；各授予入口注入位）
 * @param groupService        玩家组服务
 * @param groupNameCache      组名快照缓存
 * @param permissionRegistrar 组权限节点注册器
 * @param accessBridge        组资格桥接（WardrobeService 的 MembershipVerifier）
 */
record GroupWiring(
        BondService bondService,
        OwnershipService.PostGrantHook grantHook,
        GroupService groupService,
        GroupNameCache groupNameCache,
        GroupPermissionRegistrar permissionRegistrar,
        GroupAccessBridge accessBridge) {

    /**
     * 构造整条组/羁绊链（依赖与 PluginAssembly 同源，主线程调用）。
     *
     * @param plugin            插件实例
     * @param lang              语言门面
     * @param repositories      仓储聚合
     * @param txRunner          事务执行器
     * @param dbExecutor        数据库执行器
     * @param titleCatalogCache 称号目录缓存
     * @param profileCache      玩家档案缓存
     * @param grantEvents       授予 Post 事件桥接（TitleGrantEvent 派发）
     * @return 装配好的组/羁绊链
     */
    static GroupWiring wire(BaiYuTitle plugin, Lang lang, Assembled.Repositories repositories,
                            TransactionRunner txRunner, DbExecutor dbExecutor,
                            TitleCatalogCache titleCatalogCache, PlayerProfileCache profileCache,
                            OwnershipService.PostGrantHook grantEvents) {
        // 羁绊自动授予的下游：先发 TitleGrantEvent（source=BOND）再做获得通知
        final BondService bondService = new BondService(
                repositories.ownerships(), repositories.audits(), txRunner, dbExecutor,
                titleCatalogCache, profileCache,
                OwnershipService.PostGrantHook.compose(grantEvents,
                        new BondNotifyHook(plugin, lang, titleCatalogCache)));
        // 各授予入口的统一钩子：先发 Post 事件，再入羁绊集齐检测
        final OwnershipService.PostGrantHook grantHook =
                OwnershipService.PostGrantHook.compose(grantEvents, bondService);
        final GroupService groupService = new GroupService(
                repositories.groups(), repositories.titles(), repositories.ownerships(),
                repositories.slots(), repositories.audits(), txRunner, dbExecutor,
                titleCatalogCache, profileCache, grantHook);
        final GroupNameCache groupNameCache = new GroupNameCache();
        return new GroupWiring(bondService, grantHook, groupService, groupNameCache,
                new GroupPermissionRegistrar(Bukkit.getPluginManager()),
                new GroupAccessBridge(groupService, titleCatalogCache, groupNameCache));
    }

    /**
     * 启动预热（主线程，同步等待）：组名快照全量装载 + 权限节点批量注册——
     * 组名补全与登录权限快照收集的零 IO 前提；失败上抛由主类停用插件。
     *
     * @param logger 控制台日志出口
     */
    void preheat(ComponentLogger logger) {
        groupNameCache.replaceAll(groupService.listGroups().join());
        permissionRegistrar.registerAll(groupNameCache.all());
        logger.info("玩家组快照预热完成，共 {} 个组（权限节点已注册）。", groupNameCache.all().size());
    }

    /**
     * 组名快照后台刷新（reload 链复用；读线程装载后整体替换）。
     *
     * @return 刷新完成 future
     */
    CompletableFuture<Void> refreshNames() {
        return groupService.listGroups().thenAccept(groupNameCache::replaceAll);
    }
}
