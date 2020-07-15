package datadog.trace.bootstrap.instrumentation.decorator;

import datadog.trace.api.Config;
import datadog.trace.api.DDSpanTypes;
import datadog.trace.api.DDTags;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public abstract class TestDecorator extends BaseDecorator {
  public static final String TEST_PASS = "PASS";
  public static final String TEST_FAIL = "FAIL";
  public static final String TEST_SKIP = "SKIP";

  protected abstract String testFramework();

  protected String service() {
    return Config.get().getServiceName() + ".test";
  }

  protected String spanKind() {
    return Tags.SPAN_KIND_TEST;
  }

  @Override
  protected String spanType() {
    return DDSpanTypes.TEST;
  }

  @Override
  public AgentSpan afterStart(final AgentSpan span) {
    assert span != null;
    if (service() != null) {
      span.setTag(DDTags.SERVICE_NAME, service());
    } else {
      span.setTag(DDTags.SERVICE_NAME, Config.get().getServiceName() + ".test");
    }
    span.setTag(Tags.SPAN_KIND, spanKind());
    span.setTag(DDTags.SPAN_TYPE, spanType());
    span.setTag(DDTags.TEST_FRAMEWORK, testFramework());
    return super.afterStart(span);
  }

  public List<String> testNames(
      final String testClassName,
      final ClassLoader cl,
      final Class<? extends Annotation> testAnnotation) {
    try {
      final Class<?> testClass = cl.loadClass(testClassName);
      return testNames(testClass, testAnnotation);
    } catch (final ClassNotFoundException ex) {
      log.error("Unable to load {}", testClassName, ex);
      return new ArrayList<>();
    }
  }

  public List<String> testNames(
      final Class<?> testClass, final Class<? extends Annotation> testAnnotation) {
    final List<String> testNames = new ArrayList<>();

    final Method[] methods = testClass.getMethods();
    for (final Method method : methods) {
      if (method.getAnnotation(testAnnotation) != null) {
        testNames.add(method.getName());
      }
    }
    return testNames;
  }
}
