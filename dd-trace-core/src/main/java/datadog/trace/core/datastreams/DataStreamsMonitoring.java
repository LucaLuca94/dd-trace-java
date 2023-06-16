package datadog.trace.core.datastreams;

import datadog.trace.api.experimental.DataStreamsContextCarrier;
import datadog.trace.bootstrap.instrumentation.api.AgentDataStreamsMonitoring;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan.Context;
import datadog.trace.bootstrap.instrumentation.api.InboxItem;
import datadog.trace.bootstrap.instrumentation.api.PathwayContext;
import datadog.trace.bootstrap.instrumentation.api.StatsPoint;
import datadog.trace.core.propagation.HttpCodec;
import java.util.LinkedHashMap;

public interface DataStreamsMonitoring extends AgentDataStreamsMonitoring, AutoCloseable {
  void start();

  PathwayContext newPathwayContext();

  /**
   * Adds DSM context extractor behavior.
   *
   * @param extractor The extractor to decorate with DSM extraction.
   * @return An extractor with DSM context extraction.
   */
  HttpCodec.Extractor decorate(HttpCodec.Extractor extractor);

  /**
   * Injects DSM {@link PathwayContext} into a span {@link Context}.
   *
   * @param span The span to update.
   * @param carrier The carrier of the {@link PathwayContext} to extract and inject.
   */
  void mergePathwayContextIntoSpan(AgentSpan span, DataStreamsContextCarrier carrier);

  PathwayContext setCheckpoint(PathwayContext pathwayContext, LinkedHashMap<String, String> sortedTags);

  void addInboxItem(InboxItem inboxItem);

  void clear();

  @Override
  void close();
}
