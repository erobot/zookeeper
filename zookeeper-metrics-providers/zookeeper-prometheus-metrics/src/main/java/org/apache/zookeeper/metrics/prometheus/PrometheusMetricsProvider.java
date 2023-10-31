/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.zookeeper.metrics.prometheus;

import io.prometheus.client.Collector;
import io.prometheus.client.CollectorRegistry;
import io.prometheus.client.SketchesSummary;
import io.prometheus.client.exporter.MetricsServlet;
import io.prometheus.client.hotspot.DefaultExports;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Enumeration;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.apache.zookeeper.metrics.Counter;
import org.apache.zookeeper.metrics.CounterSet;
import org.apache.zookeeper.metrics.Gauge;
import org.apache.zookeeper.metrics.GaugeSet;
import org.apache.zookeeper.metrics.MetricsContext;
import org.apache.zookeeper.metrics.MetricsProvider;
import org.apache.zookeeper.metrics.MetricsProviderLifeCycleException;
import org.apache.zookeeper.metrics.Summary;
import org.apache.zookeeper.metrics.SummarySet;
import org.eclipse.jetty.security.ConstraintMapping;
import org.eclipse.jetty.security.ConstraintSecurityHandler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.security.Constraint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A Metrics Provider implementation based on https://prometheus.io.
 *
 * @since 3.6.0
 */
public class PrometheusMetricsProvider implements MetricsProvider {

    private static final Logger LOG = LoggerFactory.getLogger(PrometheusMetricsProvider.class);
    private static final String LABEL = "key";
    private static final String[] LABELS = {LABEL};

    /**
     * Number of worker threads for reporting Prometheus summary metrics.
     * Default value is 1.
     * If the number is less than 1, the main thread will be used.
     */
    @Deprecated
    static final String NUM_WORKER_THREADS = "numWorkerThreads";

    /**
     * The max queue size for Prometheus summary metrics reporting task.
     * Default value is 1000000.
     */
    @Deprecated
    static final String MAX_QUEUE_SIZE = "maxQueueSize";

    /**
     * The timeout in ms for Prometheus worker threads shutdown.
     * Default value is 1000ms.
     */
    static final String WORKER_SHUTDOWN_TIMEOUT_MS = "workerShutdownTimeoutMs";

    /**
     * The interval in seconds for Prometheus summary metrics rotation. Default value is 60.
     */
    static final String PROMETHEUS_SUMMARY_ROTATE_SECONDS = "prometheusMetricsSummaryRotateSeconds";

    /**
     * We are using the 'defaultRegistry'.
     * <p>
     * When you are running ZooKeeper (server or client) together with other
     * libraries every metrics will be expected as a single view.
     * </p>
     */
    private final CollectorRegistry collectorRegistry = CollectorRegistry.defaultRegistry;
    private String host = "0.0.0.0";
    private int port = 7000;
    private boolean exportJvmInfo = true;
    private Server server;
    private final MetricsServletImpl servlet = new MetricsServletImpl();
    private final Context rootContext = new Context();
    private long workerShutdownTimeoutMs = 1000;
    private int summaryRotateSeconds = 60;
    private ScheduledExecutorService executorService;

    @Override
    public void configure(Properties configuration) throws MetricsProviderLifeCycleException {
        LOG.info("Initializing metrics, configuration: {}", configuration);
        this.host = configuration.getProperty("httpHost", "0.0.0.0");
        this.port = Integer.parseInt(configuration.getProperty("httpPort", "7000"));
        this.exportJvmInfo = Boolean.parseBoolean(configuration.getProperty("exportJvmInfo", "true"));
        if (configuration.containsKey(NUM_WORKER_THREADS) || configuration.containsKey(MAX_QUEUE_SIZE)) {
            LOG.warn("The configuration {} and {} are deprecated, it is ignored. See details in ZOOKEEPER-4741",
                    NUM_WORKER_THREADS, MAX_QUEUE_SIZE);
        }
        this.workerShutdownTimeoutMs = Long.parseLong(
                configuration.getProperty(WORKER_SHUTDOWN_TIMEOUT_MS, "1000"));
        this.summaryRotateSeconds = Integer.parseInt(
                configuration.getProperty(PROMETHEUS_SUMMARY_ROTATE_SECONDS, "60"));
    }

    @Override
    public void start() throws MetricsProviderLifeCycleException {
        try {
            LOG.info("Starting /metrics HTTP endpoint at host: {}, port: {}, exportJvmInfo: {}",
                    host, port, exportJvmInfo);
            if (exportJvmInfo) {
                DefaultExports.initialize();
            }
            server = new Server(new InetSocketAddress(host, port));
            ServletContextHandler context = new ServletContextHandler();
            context.setContextPath("/");
            constrainTraceMethod(context);
            server.setHandler(context);
            context.addServlet(new ServletHolder(servlet), "/metrics");
            server.start();
            executorService = new ScheduledThreadPoolExecutor(1, new PrometheusWorkerThreadFactory());
            startSummaryRotateScheduleTask();
        } catch (Exception err) {
            LOG.error("Cannot start /metrics server", err);
            if (server != null) {
                try {
                    server.stop();
                } catch (Exception suppressed) {
                    err.addSuppressed(suppressed);
                } finally {
                    server = null;
                }
            }
            throw new MetricsProviderLifeCycleException(err);
        }
    }

    // for tests
    MetricsServletImpl getServlet() {
        return servlet;
    }

    @Override
    public MetricsContext getRootContext() {
        return rootContext;
    }

    @Override
    public void stop() {
        shutdownExecutor();
        if (server != null) {
            try {
                server.stop();
            } catch (Exception err) {
                LOG.error("Cannot safely stop Jetty server", err);
            } finally {
                server = null;
            }
        }
    }

    /**
     * Dump all values to the 4lw interface and to the Admin server.
     * <p>
     * This method is not expected to be used to serve metrics to Prometheus. We
     * are using the MetricsServlet provided by Prometheus for that, leaving the
     * real representation to the Prometheus Java client.
     * </p>
     *
     * @param sink the receiver of data (4lw interface, Admin server or tests)
     */
    @Override
    public void dump(BiConsumer<String, Object> sink) {
        sampleGauges();
        Enumeration<Collector.MetricFamilySamples> samplesFamilies = collectorRegistry.metricFamilySamples();
        while (samplesFamilies.hasMoreElements()) {
            Collector.MetricFamilySamples samples = samplesFamilies.nextElement();
            samples.samples.forEach(sample -> {
                String key = buildKeyForDump(sample);
                sink.accept(key, sample.value);
            });
        }
    }

    private static String buildKeyForDump(Collector.MetricFamilySamples.Sample sample) {
        StringBuilder keyBuilder = new StringBuilder();
        keyBuilder.append(sample.name);
        if (sample.labelNames.size() > 0) {
            keyBuilder.append('{');
            for (int i = 0; i < sample.labelNames.size(); ++i) {
                if (i > 0) {
                    keyBuilder.append(',');
                }
                keyBuilder.append(sample.labelNames.get(i));
                keyBuilder.append("=\"");
                keyBuilder.append(sample.labelValues.get(i));
                keyBuilder.append('"');
            }
            keyBuilder.append('}');
        }
        return keyBuilder.toString();
    }

    /**
     * Update Gauges. In ZooKeeper Metrics API Gauges are callbacks served by
     * internal components and the value is not held by Prometheus structures.
     */
    private void sampleGauges() {
        rootContext.gauges.values()
                .forEach(PrometheusGaugeWrapper::sample);

        rootContext.gaugeSets.values()
                .forEach(PrometheusLabelledGaugeWrapper::sample);
    }

    @Override
    public void resetAllValues() {
        // not supported on Prometheus
    }

    /**
     * Add constraint to a given context to disallow TRACE method.
     * @param ctxHandler the context to modify
     */
    private void constrainTraceMethod(ServletContextHandler ctxHandler) {
        Constraint c = new Constraint();
        c.setAuthenticate(true);

        ConstraintMapping cmt = new ConstraintMapping();
        cmt.setConstraint(c);
        cmt.setMethod("TRACE");
        cmt.setPathSpec("/*");

        ConstraintSecurityHandler securityHandler = new ConstraintSecurityHandler();
        securityHandler.setConstraintMappings(new ConstraintMapping[] {cmt});

        ctxHandler.setSecurityHandler(securityHandler);
    }

    private class Context implements MetricsContext {

        private final ConcurrentMap<String, PrometheusGaugeWrapper> gauges = new ConcurrentHashMap<>();
        private final ConcurrentMap<String, PrometheusLabelledGaugeWrapper> gaugeSets = new ConcurrentHashMap<>();
        private final ConcurrentMap<String, PrometheusCounter> counters = new ConcurrentHashMap<>();
        private final ConcurrentMap<String, PrometheusLabelledCounter> counterSets = new ConcurrentHashMap<>();
        private final ConcurrentMap<String, PrometheusSummary> basicSummaries = new ConcurrentHashMap<>();
        private final ConcurrentMap<String, PrometheusSummary> summaries = new ConcurrentHashMap<>();
        private final ConcurrentMap<String, PrometheusLabelledSummary> basicSummarySets = new ConcurrentHashMap<>();
        private final ConcurrentMap<String, PrometheusLabelledSummary> summarySets = new ConcurrentHashMap<>();

        @Override
        public MetricsContext getContext(String name) {
            // no hierarchy yet
            return this;
        }

        @Override
        public Counter getCounter(String name) {
            return counters.computeIfAbsent(name, PrometheusCounter::new);
        }

        @Override
        public CounterSet getCounterSet(final String name) {
            Objects.requireNonNull(name, "Cannot register a CounterSet with null name");
            return counterSets.computeIfAbsent(name, PrometheusLabelledCounter::new);
        }

        /**
         * Gauges may go up and down, in ZooKeeper they are a way to export
         * internal values with a callback.
         *
         * @param name  the name of the gauge
         * @param gauge the callback
         */
        @Override
        public void registerGauge(String name, Gauge gauge) {
            Objects.requireNonNull(name);
            gauges.compute(name, (id, prev) ->
                    new PrometheusGaugeWrapper(id, gauge, prev != null ? prev.inner : null));
        }

        @Override
        public void unregisterGauge(String name) {
            PrometheusGaugeWrapper existing = gauges.remove(name);
            if (existing != null) {
                existing.unregister();
            }
        }

        @Override
        public void registerGaugeSet(final String name, final GaugeSet gaugeSet) {
            Objects.requireNonNull(name, "Cannot register a GaugeSet with null name");
            Objects.requireNonNull(gaugeSet, "Cannot register a null GaugeSet for " + name);

            gaugeSets.compute(name, (id, prev) ->
                new PrometheusLabelledGaugeWrapper(name, gaugeSet, prev != null ? prev.inner : null));
        }

        @Override
        public void unregisterGaugeSet(final String name) {
            Objects.requireNonNull(name, "Cannot unregister GaugeSet with null name");

            final PrometheusLabelledGaugeWrapper existing = gaugeSets.remove(name);
            if (existing != null) {
                existing.unregister();
            }
        }

        @Override
        public Summary getSummary(String name, DetailLevel detailLevel) {
            if (detailLevel == DetailLevel.BASIC) {
                return basicSummaries.computeIfAbsent(name, (n) -> {
                    if (summaries.containsKey(n)) {
                        throw new IllegalArgumentException("Already registered a non basic summary as " + n);
                    }
                    return new PrometheusSummary(name, detailLevel);
                });
            } else {
                return summaries.computeIfAbsent(name, (n) -> {
                    if (basicSummaries.containsKey(n)) {
                        throw new IllegalArgumentException("Already registered a basic summary as " + n);
                    }
                    return new PrometheusSummary(name, detailLevel);
                });
            }
        }

        @Override
        public SummarySet getSummarySet(String name, DetailLevel detailLevel) {
            if (detailLevel == DetailLevel.BASIC) {
                return basicSummarySets.computeIfAbsent(name, (n) -> {
                    if (summarySets.containsKey(n)) {
                        throw new IllegalArgumentException("Already registered a non basic summary set as " + n);
                    }
                    return new PrometheusLabelledSummary(name, detailLevel);
                });
            } else {
                return summarySets.computeIfAbsent(name, (n) -> {
                    if (basicSummarySets.containsKey(n)) {
                        throw new IllegalArgumentException("Already registered a basic summary set as " + n);
                    }
                    return new PrometheusLabelledSummary(name, detailLevel);
                });
            }
        }

    }

    private class PrometheusCounter implements Counter {

        private final io.prometheus.client.Counter inner;
        private final String name;

        public PrometheusCounter(String name) {
            this.name = name;
            this.inner = io.prometheus.client.Counter
                    .build(name, name)
                    .register(collectorRegistry);
        }

        @Override
        public void add(long delta) {
            try {
                inner.inc(delta);
            } catch (IllegalArgumentException err) {
                LOG.error("invalid delta {} for metric {}", delta, name, err);
            }
        }

        @Override
        public long get() {
            // this method is used only for tests
            // Prometheus returns a "double"
            // it is safe to fine to a long
            // we are never setting non-integer values
            return (long) inner.get();
        }

    }

    private class PrometheusLabelledCounter implements CounterSet {
        private final String name;
        private final io.prometheus.client.Counter inner;

        public PrometheusLabelledCounter(final String name) {
            this.name = name;
            this.inner = io.prometheus.client.Counter
                    .build(name, name)
                    .labelNames(LABELS)
                    .register(collectorRegistry);
        }

        @Override
        public void add(final String key, final long delta) {
            try {
                inner.labels(key).inc(delta);
            } catch (final IllegalArgumentException e) {
                LOG.error("invalid delta {} for metric {} with key {}", delta, name, key, e);
            }
        }
    }

    private class PrometheusGaugeWrapper {

        private final io.prometheus.client.Gauge inner;
        private final Gauge gauge;
        private final String name;

        public PrometheusGaugeWrapper(String name, Gauge gauge, io.prometheus.client.Gauge prev) {
            this.name = name;
            this.gauge = gauge;
            this.inner = prev != null ? prev
                    : io.prometheus.client.Gauge
                    .build(name, name)
                    .register(collectorRegistry);
        }

        /**
         * Call the callack and update Prometheus Gauge. This method is called
         * when the server is polling for a value.
         */
        private void sample() {
            Number value = gauge.get();
            this.inner.set(value != null ? value.doubleValue() : 0);
        }

        private void unregister() {
            collectorRegistry.unregister(inner);
        }
    }

    /**
     * Prometheus implementation of GaugeSet interface. It wraps the GaugeSet object and
     * uses the callback API to update the Prometheus Gauge.
     */
    private class PrometheusLabelledGaugeWrapper {
        private final GaugeSet gaugeSet;
        private final io.prometheus.client.Gauge inner;

        private PrometheusLabelledGaugeWrapper(final String name,
                                               final GaugeSet gaugeSet,
                                               final io.prometheus.client.Gauge prev) {
            this.gaugeSet = gaugeSet;
            this.inner = prev != null ? prev :
                    io.prometheus.client.Gauge
                            .build(name, name)
                            .labelNames(LABELS)
                            .register(collectorRegistry);
        }

        /**
         * Call the callback provided by the GaugeSet and update Prometheus Gauge.
         * This method is called when the server is polling for a value.
         */
        private void sample() {
            gaugeSet.values().forEach((key, value) ->
                this.inner.labels(key).set(value != null ? value.doubleValue() : 0));
        }

        private void unregister() {
            collectorRegistry.unregister(inner);
        }
    }

    // VisibleForTesting
    class PrometheusSummary implements Summary {

        // VisibleForTesting
        SketchesSummary inner;
        private final String name;

        public PrometheusSummary(String name, MetricsContext.DetailLevel level) {
            this.name = name;
            if (level == MetricsContext.DetailLevel.ADVANCED) {
                this.inner = SketchesSummary.build(name, name)
                        .quantile(0.5) // Add 50th percentile (= median)
                        .quantile(0.9) // Add 90th percentile
                        .quantile(0.99) // Add 99th percentile
                        .register(collectorRegistry);
            } else {
                this.inner = SketchesSummary.build(name, name)
                        .quantile(0.5) // Add 50th percentile (= median) with 5% tolerated error
                        .register(collectorRegistry);
            }
        }

        @Override
        public void add(long delta) {
            try {
                inner.observe(delta);
            } catch (IllegalArgumentException err) {
                LOG.error("invalid delta {} for metric {}", delta, name, err);
            }
        }
    }

    // VisibleForTesting
    class PrometheusLabelledSummary implements SummarySet {

        // VisibleForTesting
        final SketchesSummary inner;
        private final String name;

        public PrometheusLabelledSummary(String name, MetricsContext.DetailLevel level) {
            this.name = name;
            if (level == MetricsContext.DetailLevel.ADVANCED) {
                this.inner = SketchesSummary.build(name, name)
                        .labelNames(LABELS)
                        .quantile(0.5) // Add 50th percentile (= median)
                        .quantile(0.9) // Add 90th percentile
                        .quantile(0.99) // Add 99th percentile
                        .register(collectorRegistry);
            } else {
                this.inner = SketchesSummary.build(name, name)
                        .labelNames(LABELS)
                        .quantile(0.5) // Add 50th percentile (= median)
                        .register(collectorRegistry);
            }
        }

        @Override
        public void add(String key, long value) {
            try {
                inner.labels(key).observe(value);
            } catch (IllegalArgumentException err) {
                LOG.error("invalid value {} for metric {} with key {}", value, name, key, err);
            }
        }
    }

    class MetricsServletImpl extends MetricsServlet {

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
            // little trick: update the Gauges before serving data
            // from Prometheus CollectorRegistry
            sampleGauges();
            // serve data using Prometheus built in client.
            super.doGet(req, resp);
        }
    }

    private void startSummaryRotateScheduleTask() {
        executorService.scheduleAtFixedRate(() -> {
            try {
                rootContext.summaries.values().forEach(s -> s.inner.rotate());
                rootContext.summarySets.values().forEach(s -> s.inner.rotate());
                rootContext.basicSummaries.values().forEach(s -> s.inner.rotate());
                rootContext.basicSummarySets.values().forEach(s -> s.inner.rotate());
            } catch (Exception err) {
                LOG.error("Cannot rotate summaries", err);
            }
        }, summaryRotateSeconds, summaryRotateSeconds, TimeUnit.SECONDS);
    }

    private void shutdownExecutor() {
        LOG.info("Shutdown executor service with timeout {}", workerShutdownTimeoutMs);
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(workerShutdownTimeoutMs, TimeUnit.MILLISECONDS)) {
                LOG.error("Not all the Prometheus worker threads terminated properly after {} timeout",
                        workerShutdownTimeoutMs);
                executorService.shutdownNow();
            }
        } catch (final Exception e) {
            LOG.error("Error occurred while terminating Prometheus worker threads", e);
            executorService.shutdownNow();
        }
    }

    private static class PrometheusWorkerThreadFactory implements ThreadFactory {
        private static final AtomicInteger workerCounter = new AtomicInteger(1);

        @Override
        public Thread newThread(final Runnable runnable) {
            final String threadName = "PrometheusMetricsProviderWorker-" + workerCounter.getAndIncrement();
            final Thread thread = new Thread(runnable, threadName);
            thread.setDaemon(true);
            return thread;
        }
    }
}
