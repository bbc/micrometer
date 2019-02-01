/**
 * Copyright 2017 Pivotal Software, Inc.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micrometer.cloudwatch;

import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.config.NamingConvention;
import io.micrometer.core.instrument.step.StepMeterRegistry;
import io.micrometer.core.instrument.util.NamedThreadFactory;
import io.micrometer.core.instrument.util.TimeUtils;
import io.micrometer.core.lang.Nullable;
import software.amazon.awssdk.core.exception.AbortedException;
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient;
import software.amazon.awssdk.services.cloudwatch.model.Dimension;
import software.amazon.awssdk.services.cloudwatch.model.MetricDatum;
import software.amazon.awssdk.services.cloudwatch.model.PutMetricDataRequest;
import software.amazon.awssdk.services.cloudwatch.model.StandardUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toList;
import static java.util.stream.StreamSupport.stream;

/**
 * {@link StepMeterRegistry} for Amazon CloudWatch.
 *
 * @author Dawid Kublik
 * @author Jon Schneider
 * @author Johnny Lim
 * @author Pierre-Yves B.
 */
public class CloudWatchMeterRegistry extends StepMeterRegistry {
    private final CloudWatchConfig config;
    private final CloudWatchAsyncClient cloudWatchAsyncClient;
    private final Logger logger = LoggerFactory.getLogger(CloudWatchMeterRegistry.class);

    public CloudWatchMeterRegistry(CloudWatchConfig config, Clock clock,
                                   CloudWatchAsyncClient cloudWatchAsyncClient) {
        this(config, clock, cloudWatchAsyncClient, new NamedThreadFactory("cloudwatch-metrics-publisher"));
    }

    public CloudWatchMeterRegistry(CloudWatchConfig config, Clock clock,
                                   CloudWatchAsyncClient cloudWatchAsyncClient, ThreadFactory threadFactory) {
        super(config, clock);
        requireNonNull(config.namespace());

        this.cloudWatchAsyncClient = cloudWatchAsyncClient;
        this.config = config;
        config().namingConvention(NamingConvention.identity);
        start(threadFactory);
    }

    @Override
    public void start(ThreadFactory threadFactory) {
        if (config.enabled()) {
            logger.info("publishing metrics to cloudwatch every " + TimeUtils.format(config.step()));
        }
        super.start(threadFactory);
    }

    @Override
    protected void publish() {
        for (List<MetricDatum> batch : MetricDatumPartition.partition(metricData(), config.batchSize())) {
            sendMetricData(batch);
        }
    }

    private void sendMetricData(List<MetricDatum> metricData) {
        PutMetricDataRequest putMetricDataRequest = PutMetricDataRequest.builder()
                .namespace(config.namespace())
                .metricData(metricData)
                .build();
        CountDownLatch latch = new CountDownLatch(1);
        cloudWatchAsyncClient.putMetricData(putMetricDataRequest).whenCompleteAsync((response, t) -> {
            if (t != null) {
                if (t instanceof AbortedException) {
                    logger.warn("sending metric data was aborted: {}", t.getMessage());
                } else {
                    logger.error("error sending metric data.", t);
                }
            } else {
                logger.debug("published metric with namespace:{}", putMetricDataRequest.namespace());
            }
            latch.countDown();
        });
        try {
            latch.await(config.readTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            logger.warn("metrics push to cloudwatch took longer than expected");
        }
    }

    //VisibleForTesting
    List<MetricDatum> metricData() {
        Batch batch = new Batch();
        return getMeters().stream().flatMap(m -> m.match(
                batch::gaugeData,
                batch::counterData,
                batch::timerData,
                batch::summaryData,
                batch::longTaskTimerData,
                batch::timeGaugeData,
                batch::functionCounterData,
                batch::functionTimerData,
                batch::metricData)
        ).collect(toList());
    }

    // VisibleForTesting
    class Batch {
        private long wallTime = clock.wallTime();

        private Stream<MetricDatum> gaugeData(Gauge gauge) {
            double value = gauge.value();
            if (!Double.isFinite(value))
                return Stream.empty();
            return Stream.of(metricDatum(gauge.getId(), "value", value));
        }

        private Stream<MetricDatum> counterData(Counter counter) {
            return Stream.of(metricDatum(counter.getId(), "count", counter.count()));
        }

        private Stream<MetricDatum> timerData(Timer timer) {
            final Stream.Builder<MetricDatum> metrics = Stream.builder();

            metrics.add(metricDatum(timer.getId(), "sum", getBaseTimeUnit().name(), timer.totalTime(getBaseTimeUnit())));
            metrics.add(metricDatum(timer.getId(), "count", "count", timer.count()));
            metrics.add(metricDatum(timer.getId(), "avg", getBaseTimeUnit().name(), timer.mean(getBaseTimeUnit())));
            metrics.add(metricDatum(timer.getId(), "max", getBaseTimeUnit().name(), timer.max(getBaseTimeUnit())));

            return metrics.build();
        }

        private Stream<MetricDatum> summaryData(DistributionSummary summary) {
            final Stream.Builder<MetricDatum> metrics = Stream.builder();

            metrics.add(metricDatum(summary.getId(), "sum", summary.totalAmount()));
            metrics.add(metricDatum(summary.getId(), "count", summary.count()));
            metrics.add(metricDatum(summary.getId(), "avg", summary.mean()));
            metrics.add(metricDatum(summary.getId(), "max", summary.max()));

            return metrics.build();
        }

        private Stream<MetricDatum> longTaskTimerData(LongTaskTimer longTaskTimer) {
            return Stream.of(
                    metricDatum(longTaskTimer.getId(), "activeTasks", longTaskTimer.activeTasks()),
                    metricDatum(longTaskTimer.getId(), "duration", longTaskTimer.duration(getBaseTimeUnit())));
        }

        private Stream<MetricDatum> timeGaugeData(TimeGauge gauge) {
            double value = gauge.value(getBaseTimeUnit());
            if (!Double.isFinite(value))
                return Stream.empty();
            return Stream.of(metricDatum(gauge.getId(), "value", value));
        }

        // VisibleForTesting
        Stream<MetricDatum> functionCounterData(FunctionCounter counter) {
            double count = counter.count();
            if (Double.isFinite(count)) {
                return Stream.of(metricDatum(counter.getId(), "count", count));
            }
            return Stream.empty();
        }

        private Stream<MetricDatum> functionTimerData(FunctionTimer timer) {
            // we can't know anything about max and percentiles originating from a function timer
            return Stream.of(
                    metricDatum(timer.getId(), "count", timer.count()),
                    metricDatum(timer.getId(), "avg", timer.mean(getBaseTimeUnit())));
        }

        private Stream<MetricDatum> metricData(Meter m) {
            return stream(m.measure().spliterator(), false)
                    .map(ms -> metricDatum(m.getId().withTag(ms.getStatistic()), ms.getValue()))
                    .filter(Objects::nonNull);
        }

        @Nullable
        private MetricDatum metricDatum(Meter.Id id, double value) {
            return metricDatum(id, null, null, value);
        }

        @Nullable
        private MetricDatum metricDatum(Meter.Id id, @Nullable String suffix, double value) {
            return metricDatum(id, suffix, null, value);
        }

        @Nullable
        private MetricDatum metricDatum(Meter.Id id, @Nullable String suffix, @Nullable String unit, double value) {
            if (Double.isNaN(value)) {
                return null;
            }

            List<Tag> tags = id.getConventionTags(config().namingConvention());
            return MetricDatum.builder()
                    .metricName(getMetricName(id, suffix))
                    .dimensions(toDimensions(tags))
                    .timestamp(Instant.ofEpochMilli(wallTime))
                    .value(CloudWatchUtils.clampMetricValue(value))
                    .unit(toStandardUnit(unit))
                    .build();
        }

        // VisibleForTesting
        String getMetricName(Meter.Id id, @Nullable String suffix) {
            String name = suffix != null ? id.getName() + "." + suffix : id.getName();
            return config().namingConvention().name(name, id.getType(), id.getBaseUnit());
        }

        private StandardUnit toStandardUnit(@Nullable String unit) {
            if (unit == null) {
                return StandardUnit.NONE;
            }
            switch (unit.toLowerCase()) {
                case "bytes":
                    return StandardUnit.BYTES;
                case "milliseconds":
                    return StandardUnit.MILLISECONDS;
                case "count":
                    return StandardUnit.COUNT;
            }
            return StandardUnit.NONE;
        }


        private List<Dimension> toDimensions(List<Tag> tags) {
            return tags.stream()
                    .map(tag -> Dimension.builder().name(tag.getKey()).value(tag.getValue()).build())
                    .collect(toList());
        }
    }

    @Override
    protected TimeUnit getBaseTimeUnit() {
        return TimeUnit.MILLISECONDS;
    }
}