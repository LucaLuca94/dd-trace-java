package datadog.trace.instrumentation.servlet.jsp;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.sink.XssModule;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumenterModule.class)
public final class IastJspWriterInstrumentation extends InstrumenterModule.Iast
    implements Instrumenter.ForTypeHierarchy {

  private static final String hierarchyType = "javax.servlet.jsp.JspWriter";

  public IastJspWriterInstrumentation() {
    super("servlet", "jsp");
  }

  @Override
  public String hierarchyMarkerType() {
    return hierarchyType;
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return extendsClass(named(hierarchyType));
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        namedOneOf("print", "println").and(takesArgument(0, String.class)).and(isPublic()),
        getClass().getName() + "$IastJspWriterStringAdvice");
    transformer.applyAdvice(
        namedOneOf("print", "println").and(takesArgument(0, char[].class)).and(isPublic()),
        getClass().getName() + "$IastJspWriterCharArrayAdvice");
  }

  public static class IastJspWriterStringAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(@Advice.Argument(0) final String s) {
      final XssModule module = InstrumentationBridge.XSS;
      if (module != null) {
        module.onXss(s);
      }
    }
  }

  public static class IastJspWriterCharArrayAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(@Advice.Argument(0) final char[] array) {
      final XssModule module = InstrumentationBridge.XSS;
      if (module != null) {
        module.onXss(array);
      }
    }
  }
}
