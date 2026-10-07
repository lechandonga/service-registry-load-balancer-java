package com.github.lechandonga.serviceregistryloadbalancerjava.health;

import com.github.lechandonga.serviceregistryloadbalancerjava.registry.InstanceRecord;
import com.github.lechandonga.serviceregistryloadbalancerjava.registry.ServiceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 后台周期性健康探测。
 * <p>故障判定采用「连续次数 + 迟滞」：连续失败达到 failureThreshold 才摘除，
 * 摘除后只有连续成功达到 successThreshold 才恢复，任何一次反方向结果都会清零计数，
 * 因此单次或少量抖动不会让实例反复上下线。探测结果与快照重建在注册中心同一把锁内完成，
 * 探测线程与选路线程看到的实例状态始终一致。</p>
 */
public class HealthProbeService {

    private static final Logger log = LoggerFactory.getLogger(HealthProbeService.class);

    private final ServiceRegistry registry;
    private final ProbeChecker checker;
    private final int failureThreshold;
    private final int successThreshold;
    private final long intervalMillis;
    private final ScheduledExecutorService executor;
    private final AtomicBoolean started = new AtomicBoolean(false);

    public HealthProbeService(ServiceRegistry registry, ProbeChecker checker,
                              int failureThreshold, int successThreshold, long intervalMillis) {
        if (failureThreshold < 1 || successThreshold < 1) {
            throw new IllegalArgumentException("thresholds must be >= 1");
        }
        this.registry = registry;
        this.checker = checker;
        this.failureThreshold = failureThreshold;
        this.successThreshold = successThreshold;
        this.intervalMillis = intervalMillis;
        this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "slb-health-probe");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** 启动后台探测任务，重复调用安全。 */
    public void start() {
        if (started.compareAndSet(false, true)) {
            executor.scheduleWithFixedDelay(this::probeOnceSafely,
                    intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
            log.info("health probe started interval={}ms failureThreshold={} successThreshold={}",
                    intervalMillis, failureThreshold, successThreshold);
        }
    }

    /** 关闭后台探测任务。 */
    public void shutdown() {
        executor.shutdownNow();
        started.set(false);
    }

    /** 立即执行一轮探测（测试也直接调用它，避免依赖真实定时等待）。 */
    public void probeOnceSafely() {
        try {
            probeOnce();
        } catch (Throwable t) {
            log.error("probe round failed: {}", t.toString(), t);
        }
    }

    /**
     * 一轮探测：枚举所有 ONLINE 实例（含被摘除等待恢复的），逐个探测并落账。
     *
     * @return 本轮发生摘除 / 恢复翻转的实例数
     */
    public int probeOnce() {
        List<InstanceRecord> targets = registry.listOnlineRecords();
        int flips = 0;
        for (InstanceRecord record : targets) {
            boolean healthy;
            try {
                healthy = checker.isHealthy(record);
            } catch (Exception e) {
                log.warn("checker threw for instance={}: {}", record.getInstanceId(), e.toString());
                healthy = false;
            }
            boolean flipped = registry.recordProbeResult(record.getServiceName(),
                    record.getInstanceId(), healthy, failureThreshold, successThreshold);
            if (flipped) {
                flips++;
            }
            log.debug("probe instance={} healthy={} consecutiveFailures={} consecutiveSuccesses={} serving={}",
                    record.getInstanceId(), healthy,
                    record.getConsecutiveFailures(), record.getConsecutiveSuccesses(),
                    record.isServing());
        }
        return flips;
    }

    public int getFailureThreshold() {
        return failureThreshold;
    }

    public int getSuccessThreshold() {
        return successThreshold;
    }

    public long getIntervalMillis() {
        return intervalMillis;
    }
}
