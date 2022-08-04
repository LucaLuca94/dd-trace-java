package com.datadog.iast;

import com.datadog.iast.model.Evidence;
import com.datadog.iast.model.Location;
import com.datadog.iast.model.Vulnerability;
import com.datadog.iast.model.VulnerabilityType;
import datadog.trace.api.Config;
import datadog.trace.api.iast.IASTModule;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.util.Arrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class IASTModuleImpl implements IASTModule {
  private static final Logger log = LoggerFactory.getLogger(IASTModuleImpl.class);

  @Override
  public void onCipherAlgorithm(String algorithm) {}

  @Override
  public void onHashingAlgorithm(String algorithm) {
    log.info("onHashingAlgorithm, algorithm: " + algorithm);
    if (Config.get().getWeakHashingAlgorithms().contains(algorithm.toUpperCase())) {
      log.info("inside HashingAlgorithm");
      // get StackTraceElement for the callee of MessageDigest
      StackTraceElement stackTraceElement =
          Arrays.stream(new Throwable().getStackTrace())
              .filter(s -> !s.getClassName().equals("java.security.MessageDigest"))
              .findFirst()
              .get();

      Reporter.report(
          AgentTracer.activeSpan(),
          Vulnerability.builder()
              .type(VulnerabilityType.WEAK_HASH)
              .evidence(new Evidence(algorithm))
              .location(Location.forStack(stackTraceElement))
              .build());
    }
  }
}
