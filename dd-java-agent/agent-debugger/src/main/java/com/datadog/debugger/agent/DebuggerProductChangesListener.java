package com.datadog.debugger.agent;

import com.datadog.debugger.probe.LogProbe;
import com.datadog.debugger.probe.MetricProbe;
import com.datadog.debugger.probe.SnapshotProbe;
import com.datadog.debugger.probe.SpanProbe;
import com.datadog.debugger.util.MoshiHelper;
import com.squareup.moshi.JsonAdapter;
import datadog.remoteconfig.state.ParsedConfigKey;
import datadog.remoteconfig.state.ProductListener;
import datadog.trace.api.Config;
import datadog.trace.util.TagsHelper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import okio.Okio;

public class DebuggerProductChangesListener implements ProductListener {
  public interface ConfigurationAcceptor {
    void accept(Configuration configuration);
  }

  interface ConfigChunkBuilder {
    void buildWith(Configuration.Builder builder);
  }

  static class Adapter {
    static final JsonAdapter<Configuration> CONFIGURATION_JSON_ADAPTER =
        MoshiHelper.createMoshiConfig().adapter(Configuration.class);

    static final JsonAdapter<SnapshotProbe> SNAPSHOT_PROBE_JSON_ADAPTER =
        MoshiHelper.createMoshiConfig().adapter(SnapshotProbe.class);

    static final JsonAdapter<MetricProbe> METRIC_PROBE_JSON_ADAPTER =
        MoshiHelper.createMoshiConfig().adapter(MetricProbe.class);

    static final JsonAdapter<LogProbe> LOG_PROBE_JSON_ADAPTER =
        MoshiHelper.createMoshiConfig().adapter(LogProbe.class);

    static final JsonAdapter<SpanProbe> SPAN_PROBE_JSON_ADAPTER =
        MoshiHelper.createMoshiConfig().adapter(SpanProbe.class);

    static Configuration deserializeConfiguration(byte[] content) throws IOException {
      return CONFIGURATION_JSON_ADAPTER.fromJson(
          Okio.buffer(Okio.source(new ByteArrayInputStream(content))));
    }

    static SnapshotProbe deserializeSnapshotProbe(byte[] content) throws IOException {
      return SNAPSHOT_PROBE_JSON_ADAPTER.fromJson(
          Okio.buffer(Okio.source(new ByteArrayInputStream(content))));
    }

    static MetricProbe deserializeMetricProbe(byte[] content) throws IOException {
      return METRIC_PROBE_JSON_ADAPTER.fromJson(
          Okio.buffer(Okio.source(new ByteArrayInputStream(content))));
    }

    static LogProbe deserializeLogProbe(byte[] content) throws IOException {
      return LOG_PROBE_JSON_ADAPTER.fromJson(
          Okio.buffer(Okio.source(new ByteArrayInputStream(content))));
    }

    static SpanProbe deserializeSpanProbe(byte[] content) throws IOException {
      return SPAN_PROBE_JSON_ADAPTER.fromJson(
          Okio.buffer(Okio.source(new ByteArrayInputStream(content))));
    }
  }

  private static final Predicate<String> IS_UUID =
      Pattern.compile(
              "^[0-9a-fA-F]{8}\\-[0-9a-fA-F]{4}\\-[0-9a-fA-F]{4}\\-[0-9a-fA-F]{4}\\-[0-9a-fA-F]{12}$")
          .asPredicate();

  private final String serviceName;
  private final ConfigurationAcceptor configurationAcceptor;
  private final Map<String, ConfigChunkBuilder> configChunks = new HashMap<>();

  DebuggerProductChangesListener(Config config, ConfigurationAcceptor configurationAcceptor) {
    this.serviceName = TagsHelper.sanitize(config.getServiceName());
    this.configurationAcceptor = configurationAcceptor;
  }

  @Override
  public void accept(
      ParsedConfigKey configKey,
      byte[] content,
      long version,
      datadog.remoteconfig.ConfigurationChangesListener.PollingRateHinter pollingRateHinter)
      throws IOException {

    String configId = configKey.getConfigId();

    if (configId.startsWith("snapshotProbe_")) {
      SnapshotProbe snapshotProbe = Adapter.deserializeSnapshotProbe(content);
      snapshotProbe.setVersion(version);
      configChunks.put(configId, (builder) -> builder.add(snapshotProbe));
    } else if (configId.startsWith("metricProbe_")) {
      MetricProbe metricProbe = Adapter.deserializeMetricProbe(content);
      metricProbe.setVersion(version);
      configChunks.put(configId, (builder) -> builder.add(metricProbe));
    } else if (configId.startsWith("logProbe_")) {
      LogProbe logProbe = Adapter.deserializeLogProbe(content);
      logProbe.setVersion(version);
      configChunks.put(configId, (builder) -> builder.add(logProbe));
    } else if (configId.startsWith("spanProbe_")) {
      SpanProbe spanProbe = Adapter.deserializeSpanProbe(content);
      spanProbe.setVersion(version);
      configChunks.put(configId, (builder) -> builder.add(spanProbe));
    } else if (IS_UUID.test(configId)) {
      // TODO: ignore this configuration type once migration is over.
      Configuration newConfig = Adapter.deserializeConfiguration(content);
      newConfig.getDefinitions().forEach((probe) -> probe.setVersion(version));
      if (newConfig.getService().equals(serviceName)) {
        configChunks.put(configId, (builder) -> builder.add(newConfig));
      } else {
        throw new IOException(
            "got config.serviceName = " + newConfig.getService() + ", ignoring configuration");
      }
    } else {
      throw new IOException("unsupported configuration id " + configId);
    }
  }

  @Override
  public void remove(
      ParsedConfigKey configKey,
      datadog.remoteconfig.ConfigurationChangesListener.PollingRateHinter pollingRateHinter)
      throws IOException {
    configChunks.remove(configKey.getConfigId());
  }

  @Override
  public void commit(
      datadog.remoteconfig.ConfigurationChangesListener.PollingRateHinter pollingRateHinter) {

    Configuration.Builder builder = Configuration.builder().setService(serviceName);

    for (ConfigChunkBuilder chunk : configChunks.values()) {
      chunk.buildWith(builder);
    }

    Configuration config = builder.build();

    configurationAcceptor.accept(config);
  }
}
