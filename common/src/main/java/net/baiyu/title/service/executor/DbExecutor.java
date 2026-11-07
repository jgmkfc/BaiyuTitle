package net.baiyu.title.service.executor;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 数据库执行器。
 * <p>线程纪律：所有 JDBC 只在本执行器线程上运行——
 * 写操作走单线程池（串行化全部写事务，天然免锁），读操作走 2~4 线程小池；
 * Bukkit API 仅主线程，两边通过 {@link CompletableFuture} 衔接。
 * <p>事务只允许在写线程展开，{@link #assertWriterThread()} 供
 * {@code TransactionRunner} 运行时断言线程名。
 */
public final class DbExecutor {

    /** 写线程名（单线程池唯一线程；事务线程断言依据）。 */
    public static final String WRITER_THREAD_NAME = "BaiyuTitle-DB-Writer";

    /** 读线程名前缀（后接从 1 起的序号）。 */
    public static final String READER_THREAD_PREFIX = "BaiyuTitle-DB-Reader-";

    /** 读线程数下限。 */
    private static final int MIN_READER_THREADS = 2;

    /** 读线程数上限。 */
    private static final int MAX_READER_THREADS = 4;

    /** 写线程池（单线程，串行执行全部写事务）。 */
    private final ExecutorService writerPool;

    /** 读线程池（固定 2~4 线程，只跑无事务只读查询）。 */
    private final ExecutorService readerPool;

    /**
     * 以默认读线程数创建执行器（按 CPU 核数一半夹在 2~4 之间）。
     */
    public DbExecutor() {
        this(Runtime.getRuntime().availableProcessors() / 2);
    }

    /**
     * 以指定读线程数创建执行器（自动夹到 2~4 区间）。
     *
     * @param requestedReaderThreads 期望的读线程数
     */
    public DbExecutor(int requestedReaderThreads) {
        int readerThreads = Math.clamp(requestedReaderThreads, MIN_READER_THREADS, MAX_READER_THREADS);
        this.writerPool = Executors.newSingleThreadExecutor(
                runnable -> newDaemonThread(runnable, WRITER_THREAD_NAME));
        AtomicInteger readerIndex = new AtomicInteger(1);
        ThreadFactory readerFactory = runnable ->
                newDaemonThread(runnable, READER_THREAD_PREFIX + readerIndex.getAndIncrement());
        this.readerPool = Executors.newFixedThreadPool(readerThreads, readerFactory);
    }

    /**
     * 创建守护线程（插件停服时不阻塞 JVM 退出；正常关闭仍走 {@link #shutdown(Duration)}）。
     */
    private static Thread newDaemonThread(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }

    /**
     * 提交写任务到写线程（单线程串行；事务必须在这里展开）。
     *
     * @param task 写任务
     * @param <T>  任务返回类型
     * @return 任务完成 future（任务抛出的异常以 exceptionally 形式传递）
     */
    public <T> CompletableFuture<T> submitWrite(Callable<T> task) {
        return submit(writerPool, task);
    }

    /**
     * 提交只读任务到读线程池（不开事务、不做线程断言）。
     *
     * @param task 只读任务
     * @param <T>  任务返回类型
     * @return 任务完成 future（任务抛出的异常以 exceptionally 形式传递）
     */
    public <T> CompletableFuture<T> submitRead(Callable<T> task) {
        return submit(readerPool, task);
    }

    /**
     * 通用提交：任务异常与线程池拒绝均转为 future 的异常完成，不吞异常；
     * 关闭时被 {@code shutdownNow()} 丢弃的排队任务同样异常完成（见 {@link PendingTask}）。
     */
    private static <T> CompletableFuture<T> submit(ExecutorService pool, Callable<T> task) {
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            pool.execute(new PendingTask<>(task, future));
        } catch (RejectedExecutionException e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    /**
     * 优雅关闭：停止接收新任务，等待已提交任务完成；超时后强制中断，
     * 被丢弃的排队任务逐个以异常完成其 future（调用方的失败回调/join 不会静默悬挂）。
     * <p>写池先等（保证在途事务收尾），剩余预算再等读池。
     *
     * @param timeout 总等待预算
     */
    public void shutdown(Duration timeout) {
        writerPool.shutdown();
        readerPool.shutdown();
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        try {
            if (!writerPool.awaitTermination(remainingNanos(deadlineNanos), TimeUnit.NANOSECONDS)) {
                abandonAll(writerPool.shutdownNow());
            }
            if (!readerPool.awaitTermination(remainingNanos(deadlineNanos), TimeUnit.NANOSECONDS)) {
                abandonAll(readerPool.shutdownNow());
            }
        } catch (InterruptedException e) {
            abandonAll(writerPool.shutdownNow());
            abandonAll(readerPool.shutdownNow());
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 将 {@code shutdownNow()} 返回的未执行任务逐个标记为"被丢弃"，
     * 使其 future 异常完成而非永久悬挂。
     */
    private static void abandonAll(List<Runnable> dropped) {
        for (Runnable runnable : dropped) {
            if (runnable instanceof PendingTask<?> pending) {
                pending.abandon();
            }
        }
    }

    /**
     * 距截止时刻的剩余纳秒（不小于 0）。
     */
    private static long remainingNanos(long deadlineNanos) {
        return Math.max(0L, deadlineNanos - System.nanoTime());
    }

    /**
     * 断言当前线程是数据库写线程（事务入口的运行时防线，不变量 §十 11）。
     *
     * @throws IllegalStateException 当前线程不是写线程时
     */
    public static void assertWriterThread() {
        String currentName = Thread.currentThread().getName();
        if (!WRITER_THREAD_NAME.equals(currentName)) {
            throw new IllegalStateException(
                    "数据库事务只允许在写线程 [" + WRITER_THREAD_NAME + "] 上执行，当前线程: " + currentName);
        }
    }

    /**
     * 断言当前线程属于本执行器（写线程或读线程；无事务读路径的运行时防线，
     * 红线"所有 JDBC 只在 DbExecutor 线程"）。
     *
     * @throws IllegalStateException 当前线程既非写线程也非读线程时
     */
    public static void assertDbThread() {
        String currentName = Thread.currentThread().getName();
        if (!WRITER_THREAD_NAME.equals(currentName) && !currentName.startsWith(READER_THREAD_PREFIX)) {
            throw new IllegalStateException(
                    "数据库操作只允许在 DbExecutor 线程上执行，当前线程: " + currentName);
        }
    }

    /**
     * 排队任务包装：执行时完成 future；执行器关闭被丢弃时以异常完成 future，
     * 保证任何提交过的任务其 future 终会完成（不静默悬挂）。
     */
    private record PendingTask<T>(Callable<T> task, CompletableFuture<T> future) implements Runnable {

        @Override
        public void run() {
            try {
                future.complete(task.call());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        }

        /**
         * 标记任务被执行器关闭丢弃。
         */
        void abandon() {
            future.completeExceptionally(
                    new RejectedExecutionException("数据库执行器已关闭，排队任务被丢弃"));
        }
    }
}
