package com.datadog.appsec.powerwaf;

import com.datadog.appsec.config.TraceSegmentPostProcessor;
import com.datadog.appsec.gateway.AppSecRequestContext;
import com.datadog.appsec.report.AppSecEvent;
import datadog.trace.api.internal.TraceSegment;
import io.sqreen.powerwaf.PowerwafMetrics;
import java.util.Collection;

public class PowerWAFStatsReporter implements TraceSegmentPostProcessor {
  private static final String TOTAL_DURATION_US_TAG = "_dd.appsec.waf.duration_ext";
  private static final String TOTAL_DDWAF_RUN_DURATION_US_TAG = "_dd.appsec.waf.duration";
  private static final String RULE_FILE_VERSION = "_dd.appsec.event_rules.version";
  public static final String TIMEOUTS_TAG = "_dd.appsec.waf.timeouts";

  // XXX: if config is updated, this may not match the actual version run during this request
  // However, as of this point, we don't update rules at runtime.
  volatile String rulesVersion;

  @Override
  public void processTraceSegment(
      TraceSegment segment, AppSecRequestContext ctx, Collection<AppSecEvent> collectedEvents) {
    PowerwafMetrics metrics = ctx.getWafMetrics();
    if (metrics != null) {
      segment.setTagTop(TOTAL_DURATION_US_TAG, metrics.getTotalRunTimeNs() / 1000L);
      segment.setTagTop(TOTAL_DDWAF_RUN_DURATION_US_TAG, metrics.getTotalDdwafRunTimeNs() / 1000L);

      String rulesVersion = this.rulesVersion;
      if (rulesVersion != null) {
        segment.setTagTop(RULE_FILE_VERSION, rulesVersion);
      }
    }
    if (ctx.getTimeouts() > 0) {
      segment.setTagTop(TIMEOUTS_TAG, ctx.getTimeouts());
    }
  }
}
