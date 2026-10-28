package com.kairui.tools.sqlverify.query;

import com.kairui.tools.sqlverify.config.VerificationConfig;

import com.kairui.tools.sqlquery.QueryCase;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;

/** 固定工作任务领取查询，调用线程逐条写盘并确认；所有连接只在其所有者线程使用。 */
final class ConcurrentQueryRunner {
    private static final long CHECK_MILLIS = 100;
    private final SessionFactory sessionFactory;
    private final Duration cleanupTimeout;

    // 结果接收接口：由调用线程执行写盘及统计；定义或传递回调不会触发 SQL。
    @FunctionalInterface interface ResultSink {
        void accept(ComparedQuery entry) throws IOException;
    }

    // 会话创建接口：默认委托 JdbcQuerySession.open 建连，测试可替换为受控会话。
    @FunctionalInterface interface SessionFactory {
        JdbcQuerySession open(VerificationConfig.DataSource dataSource, boolean required);
    }

    ConcurrentQueryRunner() { this(JdbcQuerySession::open, Duration.ofSeconds(30)); }

    ConcurrentQueryRunner(SessionFactory sessionFactory, Duration cleanupTimeout) {
        if (cleanupTimeout.isNegative() || cleanupTimeout.isZero()) {
            throw new IllegalArgumentException("清理等待期限必须为正数");
        }
        this.sessionFactory = sessionFactory;
        this.cleanupTimeout = cleanupTimeout;
    }

    /** 正常返回前确认全部条目已交付、线程已退出；失败保留已交付证据并有限等待资源清理。 */
    Map<String, Object> run(List<QueryCase> queries, int sequenceOffset, VerificationConfig config,
            ResultSink sink) throws IOException {
        if (queries.isEmpty()) throw new IllegalArgumentException("并发入口要求至少一条有效查询");
        // 调度步骤 1（调用线程）：实际并发数不超过查询数；每个工作任务持有一对连接，最多 2 × count 条。
        int count = Math.min(config.concurrency(), queries.size());
        State state = new State(List.copyOf(queries), sequenceOffset, config, count);
        AtomicInteger threadNumber = new AtomicInteger();
        ThreadPoolExecutor pool = new ThreadPoolExecutor(count, count, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(count), task -> {
                    Thread thread = new Thread(task, "sql-verifier-worker-" + threadNumber.incrementAndGet());
                    // 阻塞且不响应中断的第三方驱动不阻止独立 CLI 结束进程。
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        List<Future<?>> workers = new ArrayList<>(count);
        IOException failure = null;
        boolean interrupted = false;
        long cleanupDeadline = 0;
        try {
            // 调度步骤 2：仅提交 count 个长期工作任务，由任务循环领查询，不为每条 SQL 创建 Future。
            for (int workerId = 1; workerId <= count; workerId++) {
                int owner = workerId;
                workers.add(pool.submit(() -> work(state, owner)));
            }
            // 停止接收新任务，已提交的工作任务继续运行；此调用不会等待它们完成。
            pool.shutdown();
            // 调度步骤 3：等所有工作任务报告建连状态；定时检查异常，防止某个任务提前退出后永久等待。
            while (!state.initialized.await(CHECK_MILLIS, TimeUnit.MILLISECONDS)) {
                checkFailure(state);
                if (pool.isTerminated()) {
                    checkFailure(state);
                    throw new IOException("工作任务在初始化完成前退出");
                }
            }
            checkFailure(state);
            // 所有初始化结果可见后统一放行；任一连接初始化失败会使对应数据源侧整体停用。
            state.release.countDown();
            int delivered = 0;
            // 调度步骤 4：调用线程持续消费结果；不能先等待工作任务结束，因为工作任务正在等待落盘确认。
            while (delivered < queries.size()) {
                checkFailure(state);
                Delivery delivery = state.results.poll(CHECK_MILLIS, TimeUnit.MILLISECONDS);
                if (delivery == null) {
                    if (pool.isTerminated()) {
                        checkFailure(state);
                        throw new IOException("工作任务提前退出，查询证据不完整");
                    }
                    continue;
                }
                // 统计与确认由调用线程完成；写盘异常不能被转成一条正常查询错误。
                // 此时 SQL 执行与比较已经结束；accept 只同步调用外部回调，写盘失败会直接终止调度。
                sink.accept(delivery.entry());
                delivered++;
                // 落盘与统计成功后发送确认，解除对应工作任务的 get 等待，使其可领取下一条查询。
                delivery.acknowledgement().complete(null);
            }
            // 调度步骤 5：收齐结果后还需等待连接关闭及工作任务退出，再汇总环境快照。
            cleanupDeadline = System.nanoTime() + cleanupTimeout.toNanos();
            if (!pool.awaitTermination(cleanupTimeout.toNanos(), TimeUnit.NANOSECONDS)
                    || state.active.get() != 0) {
                throw new IOException("工作线程清理未完成：连接关闭超过等待期限");
            }
            checkFailure(state);
            if (!state.results.isEmpty()) throw new IOException("执行完成后仍有未确认的查询证据");
            return environments(state);
        } catch (InterruptedException exception) {
            interrupted = true;
            failure = new IOException("并发查询已中断", exception);
        } catch (IOException exception) {
            failure = exception;
        } catch (RuntimeException | Error exception) {
            failure = new IOException("并发查询调度失败", exception);
        } finally {
            if (failure != null) {
                // 异常收尾：同时释放初始化屏障和落盘等待，再中断任务；连接仍由各工作线程自行关闭。
                state.stop.set(true);
                state.release.countDown();
                IOException cancellation = failure;
                state.pending.forEach(ack -> ack.completeExceptionally(cancellation));
                workers.forEach(worker -> worker.cancel(true));
                pool.shutdownNow();
                // 清理等待本身可被中断；临时清除调用线程标记，最后恢复。
                interrupted |= Thread.interrupted();
                if (cleanupDeadline == 0) cleanupDeadline = System.nanoTime() + cleanupTimeout.toNanos();
                try {
                    if (!pool.awaitTermination(Math.max(0, cleanupDeadline - System.nanoTime()), TimeUnit.NANOSECONDS)
                            || state.active.get() != 0) {
                        failure = new IOException(failure.getMessage() + "；工作线程清理未完成", failure);
                    }
                } catch (InterruptedException exception) {
                    interrupted = true;
                    failure.addSuppressed(exception);
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
        throw failure;
    }

    /** 只有实际进入的任务计入存活数，连接关闭完成后才减少；预启动取消不会造成虚假等待。 */
    private void work(State state, int workerId) {
        state.active.incrementAndGet();
        JdbcQuerySession source = null;
        JdbcQuerySession target = null;
        try {
            if (state.stop.get()) return;
            // 工作步骤 1（工作线程）：在本线程创建源侧会话；默认工厂在这里实际调用 JDBC 建连。
            source = sessionFactory.open(state.config.source(), true);
            if (state.stop.get()) return;
            // 同一工作线程再创建目标侧会话；两条连接只由本线程执行和关闭，不跨线程共享。
            target = sessionFactory.open(state.config.target(), true);
            publishUnavailable(state.sourceUnavailable, source, workerId, null);
            publishUnavailable(state.targetUnavailable, target, workerId, null);
            // 工作步骤 2：先发布双侧初始化故障，再报告初始化完成，等待调用线程统一放行。
            state.initialized.countDown();
            state.release.await();
            if (state.stop.get()) return;
            if (state.sourceUnavailable.get() != null) source.disable(state.sourceUnavailable.get());
            if (state.targetUnavailable.get() != null) target.disable(state.targetUnavailable.get());
            while (!state.stop.get()) {
                // 工作步骤 3：原子领取下一个下标，保证同一查询只被一个任务领取。
                int index = state.cursor.getAndIncrement();
                if (index >= state.queries.size()) break;
                QueryCase query = state.queries.get(index);
                // 工作步骤 4：先执行源库，再执行目标库；并发发生在不同查询之间，同一条的两侧在此顺序执行。
                QuerySideResult sourceResult = execute(state, source, state.sourceUnavailable, query, workerId);
                if (state.stop.get()) break;
                QuerySideResult targetResult = execute(state, target, state.targetUnavailable, query, workerId);
                if (state.stop.get()) break;
                // 工作步骤 5：在连接所属线程比较行值并组装证据；逻辑序号按输入下标确定，不按完成时间确定。
                ComparedQuery entry = ComparedQuery.compare(state.sequenceOffset + index + 1, workerId,
                        query, sourceResult, targetResult, state.config);
                // 工作步骤 6：创建本条结果的落盘确认信号；此 Future 不执行任务，仅用于等待调用线程确认。
                CompletableFuture<Void> acknowledgement = new CompletableFuture<>();
                state.pending.add(acknowledgement);
                try {
                    Delivery delivery = new Delivery(entry, acknowledgement);
                    while (!state.stop.get() && !state.results.offer(delivery, CHECK_MILLIS, TimeUnit.MILLISECONDS)) {
                        // 有界交付，同时给停止状态一个可检查的位置。
                    }
                    if (state.stop.get()) break;
                    // 必须等调用线程写盘成功才继续领取；每个工作任务最多保留一条未确认查询，限制在途结果数量。
                    acknowledgement.get();
                } finally {
                    state.pending.remove(acknowledgement);
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            failWorker(state, exception);
        } catch (ExecutionException | RuntimeException | Error exception) {
            failWorker(state, exception);
        } finally {
            try {
                // 一个关闭失败不能跳过另一个连接的清理。
                try { if (source != null) source.close(); }
                finally { if (target != null) target.close(); }
                // 工作步骤 7：连接关闭后发布小型环境快照，保留关闭告警；不把连接对象交回调用线程。
                Map<String, Object> environment = new LinkedHashMap<>();
                environment.put("workerId", workerId);
                if (source != null) environment.put("source", source.environment());
                if (target != null) environment.put("target", target.environment());
                state.environments.set(workerId - 1, environment);
            } catch (RuntimeException | Error exception) {
                failWorker(state, exception);
            } finally {
                state.active.decrementAndGet();
            }
        }
    }

    // 单侧执行入口：先检查全局停用原因，再调用 JDBC；失效信息供同侧其他工作任务复用。
    private QuerySideResult execute(State state, JdbcQuerySession session,
            AtomicReference<Map<String, Object>> unavailable, QueryCase query, int workerId) {
        if (state.stop.get()) return QuerySideResult.notExecuted(null);
        Map<String, Object> reason = unavailable.get();
        if (reason != null) {
            session.disable(reason);
            return QuerySideResult.notExecuted(reason);
        }
        // 检查停用标记与 JDBC 调用之间可能有其他线程发布失效；已进入执行的调用保留真实结果，不重试。
        QuerySideResult result = session.execute(query, state.config);
        publishUnavailable(unavailable, session, workerId, query.id());
        return result;
    }

    // 仅发布该侧首次连接失效原因和触发位置；普通 SQL 错误不会自动停用整个数据源。
    private static void publishUnavailable(AtomicReference<Map<String, Object>> unavailable,
            JdbcQuerySession session, int workerId, String queryId) {
        Map<String, Object> cause = session.unavailableReason();
        if (cause == null) return;
        Map<String, Object> reason = new LinkedHashMap<>(cause);
        reason.put("workerId", workerId);
        if (queryId != null) reason.put("queryId", queryId);
        unavailable.compareAndSet(null, reason);
    }

    private static void checkFailure(State state) throws IOException {
        Throwable failure = state.failure.get();
        if (failure != null) throw new IOException("并发查询工作任务失败", failure);
    }

    // 工作任务异常与普通查询失败分开处理：前者通知调用线程停止整轮，后者作为结果继续交付。
    private static void failWorker(State state, Throwable failure) {
        if (!state.stop.get() && state.failure.compareAndSet(null, failure)) {
            state.stop.set(true);
            state.release.countDown();
        }
    }

    /** 只保留每条连接的小型环境快照；连接元数据差异逐项列出，不累计查询结果。 */
    private static Map<String, Object> environments(State state) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String name : List.of("source", "target")) {
            List<Map<String, Object>> workers = new ArrayList<>();
            Map<String, Object> representative = null;
            List<Map<String, Object>> differences = new ArrayList<>();
            for (int index = 0; index < state.environments.length(); index++) {
                Map<String, Object> worker = state.environments.get(index);
                @SuppressWarnings("unchecked")
                Map<String, Object> snapshot = (Map<String, Object>) worker.get(name);
                Map<String, Object> item = new LinkedHashMap<>(snapshot);
                item.put("workerId", worker.get("workerId"));
                workers.add(item);
                if (representative == null) representative = snapshot;
                else {
                    for (String key : List.of("databaseProduct", "databaseVersion", "driverName", "driverVersion", "catalog")) {
                        if (!Objects.equals(representative.get(key), snapshot.get(key))) {
                            Map<String, Object> difference = new LinkedHashMap<>();
                            difference.put("workerId", worker.get("workerId"));
                            difference.put("field", key);
                            difference.put("representative", representative.get(key));
                            difference.put("actual", snapshot.get(key));
                            differences.add(difference);
                        }
                    }
                }
            }
            Map<String, Object> side = new LinkedHashMap<>(representative);
            Map<String, Object> reason = (name.equals("source") ? state.sourceUnavailable : state.targetUnavailable).get();
            side.put("status", reason == null ? "CONNECTED" : "UNAVAILABLE");
            if (reason != null) side.put("error", reason);
            side.put("workers", workers);
            if (!differences.isEmpty()) side.put("metadataDifferences", differences);
            result.put(name, side);
        }
        return result;
    }

    // 结果与其专属确认信号一起传递；队列只传证据，不传数据库连接。
    private record Delivery(ComparedQuery entry, CompletableFuture<Void> acknowledgement) { }

    // 单轮共享协调状态；连接保存在 work 的局部变量中，不放入共享状态。
    private static final class State {
        final List<QueryCase> queries;
        // 输入错误已先写入报告，正常查询的逻辑序号需从该偏移之后开始。
        final int sequenceOffset;
        final VerificationConfig config;
        // 容量等于工作任务数，调用线程消费；工作任务入队后仍需等待单条落盘确认。
        final ArrayBlockingQueue<Delivery> results;
        // 初始化屏障：每个任务报告一次；计数归零后调用线程才能统一放行。
        final CountDownLatch initialized;
        // 放行屏障：正常由调用线程释放，异常时也释放，避免工作任务滞留。
        final CountDownLatch release = new CountDownLatch(1);
        // 下一个待领取查询的下标，所有工作任务通过原子递增竞争领取。
        final AtomicInteger cursor = new AtomicInteger();
        // 已进入但尚未完成资源清理的工作任务数，用于验证连接所有者确实退出。
        final AtomicInteger active = new AtomicInteger();
        // 全轮停止信号；不是单条 SQL 执行失败标记。
        final AtomicBoolean stop = new AtomicBoolean();
        // 保存首次工作任务异常，由调用线程读取并转为整轮失败。
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        // 分别保存源侧和目标侧的首次连接失效原因；一侧停用不自动停用另一侧。
        final AtomicReference<Map<String, Object>> sourceUnavailable = new AtomicReference<>();
        final AtomicReference<Map<String, Object>> targetUnavailable = new AtomicReference<>();
        // 尚未完成交付流程的确认信号；整轮停止时统一异常完成，唤醒等待中的工作任务。
        final Set<CompletableFuture<Void>> pending = ConcurrentHashMap.newKeySet();
        // 按 workerId 保存关闭后的环境快照，供整轮报告汇总。
        final AtomicReferenceArray<Map<String, Object>> environments;

        State(List<QueryCase> queries, int sequenceOffset, VerificationConfig config, int count) {
            this.queries = queries;
            this.sequenceOffset = sequenceOffset;
            this.config = config;
            results = new ArrayBlockingQueue<>(count);
            initialized = new CountDownLatch(count);
            environments = new AtomicReferenceArray<>(count);
        }
    }
}
