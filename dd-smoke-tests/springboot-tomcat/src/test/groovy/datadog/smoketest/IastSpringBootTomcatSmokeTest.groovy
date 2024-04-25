package datadog.smoketest

import okhttp3.Request
import okhttp3.Response
import spock.lang.Shared

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission

import static datadog.trace.api.config.IastConfig.IAST_DEBUG_ENABLED
import static datadog.trace.api.config.IastConfig.IAST_DETECTION_MODE
import static datadog.trace.api.config.IastConfig.IAST_ENABLED

class IastSpringBootTomcatSmokeTest extends AbstractIastServerSmokeTest {

  @Shared
  def tomcatDirectory = new File(System.getProperty("datadog.smoketest.tomcatDir")).toPath()

  @Shared
  def springBootShadowWar = new File(System.getProperty("datadog.smoketest.springboot.war.path")).toPath()

  @Override
  protected void beforeProcessBuilders() {
    try {
      def catalinaPath = tomcatDirectory.resolve("bin/catalina.sh")
      def permissions = new HashSet<>(Files.getPosixFilePermissions(catalinaPath))
      permissions.add(PosixFilePermission.OWNER_EXECUTE)
      Files.setPosixFilePermissions(catalinaPath, permissions)
    } catch (Exception e) {
      // not posix ... continue
    }
    Files.copy(springBootShadowWar, tomcatDirectory.resolve("webapps/smoke.war"), StandardCopyOption.REPLACE_EXISTING)
    def tomcatServerConfPath = tomcatDirectory.resolve("conf/server.xml")
    Files.write(tomcatServerConfPath, new String(Files.readAllBytes(tomcatServerConfPath), StandardCharsets.UTF_8)
      .replace("<Connector port=\"8080\" protocol=\"HTTP/1.1\"",
      "<Connector port=\"$httpPort\" protocol=\"HTTP/1.1\"")
      .getBytes(StandardCharsets.UTF_8))
  }

  @Override
  ProcessBuilder createProcessBuilder() {
    ProcessBuilder processBuilder =
      new ProcessBuilder("bin/catalina.sh", "run")
    processBuilder.directory(tomcatDirectory.toFile())
    defaultJavaProperties += withSystemProperty(IAST_ENABLED, true)
    defaultJavaProperties += withSystemProperty(IAST_DETECTION_MODE, 'FULL')
    defaultJavaProperties += withSystemProperty(IAST_DEBUG_ENABLED, true)
    processBuilder.environment().put("CATALINA_OPTS", defaultJavaProperties.join(" "))
    return processBuilder
  }

  @Override
  def cleanupSpec() {
    ProcessBuilder processBuilder =
      new ProcessBuilder("bin/catalina.sh", "stop")
    processBuilder.directory(tomcatDirectory.toFile())
    Process process = processBuilder.start()
    process.waitFor()
  }


  void 'find xss in jsp'() {
    given:
    String url = "http://localhost:${httpPort}/smoke/test_xss_in_jsp?test=thisCouldBeDangerous"

    when:
    Response response = client.newCall(new Request.Builder().url(url).get().build()).execute()

    then:
    response.successful
    hasVulnerability { vul ->
      vul.type == 'XSS'
    }
  }
}
