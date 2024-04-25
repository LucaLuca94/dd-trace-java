import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.sink.XssModule

import javax.servlet.jsp.JspWriter

class JspWriterInstrumentationTest extends AgentTestRunner{

  @Override
  protected void configurePreAgent() {
    injectSysConfig("dd.iast.enabled", "true")
  }

  void 'test String'() {
    setup:
    final iastModule = Mock(XssModule)
    InstrumentationBridge.registerIastModule(iastModule)
    final writer = Mock(JspWriter)
    final string = "Hello World"

    when:
    writer.print(string)
    writer.println(string)

    then:
    2 * iastModule.onXss(string)
    0 * _
  }

  void 'test charArray'() {
    setup:
    final iastModule = Mock(XssModule)
    InstrumentationBridge.registerIastModule(iastModule)
    final writer = Mock(JspWriter)
    final array = "Hello World".toCharArray()

    when:
    writer.print(array)
    writer.println(array)

    then:
    2 * iastModule.onXss(array)
    0 * _
  }
}
