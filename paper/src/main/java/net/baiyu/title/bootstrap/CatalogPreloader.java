package net.baiyu.title.bootstrap;

import net.baiyu.title.cache.TitleCatalogCache;
import net.baiyu.title.repository.TitleRepository;
import net.baiyu.title.repository.TransactionRunner;
import net.baiyu.title.service.executor.DbExecutor;

import java.util.concurrent.CompletableFuture;

/**
 * 称号目录预热器（{@link PluginAssembly} 与 {@link ReloadCoordinator} 共用）。
 */
final class CatalogPreloader {

    private CatalogPreloader() {
    }

    /**
     * 称号目录全量装载（含软删行——历史所有权渲染需要取名）并整体替换缓存。
     * <p>装载走 DbExecutor 读线程（无事务读）；{@code replaceAll} 原子替换
     * volatile 快照，reload 复用本方法时读方无空窗。</p>
     *
     * @param dbExecutor      数据库执行器
     * @param txRunner        事务执行器（无事务读路径）
     * @param titleRepository 称号仓储
     * @param cache           称号目录缓存
     * @return 装载条数 future
     */
    static CompletableFuture<Integer> preload(DbExecutor dbExecutor, TransactionRunner txRunner,
                                              TitleRepository titleRepository,
                                              TitleCatalogCache cache) {
        return dbExecutor
                .submitRead(() -> txRunner.withConnection(c -> titleRepository.loadAll(c, true)))
                .thenApply(titles -> {
                    cache.replaceAll(titles);
                    return titles.size();
                });
    }
}
