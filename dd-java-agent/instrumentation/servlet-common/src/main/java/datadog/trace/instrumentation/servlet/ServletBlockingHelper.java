package datadog.trace.instrumentation.servlet;

import datadog.appsec.api.blocking.BlockingContentType;
import datadog.trace.api.gateway.Flow;
import datadog.trace.bootstrap.blocking.BlockingActionHelper;
import java.io.IOException;
import java.io.OutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ServletBlockingHelper {
  private static final Logger log = LoggerFactory.getLogger(ServletBlockingHelper.class);

  public static void commitBlockingResponse(
      HttpServletRequest httpServletRequest,
      HttpServletResponse resp,
      int statusCode_,
      BlockingContentType bct) {
    int statusCode = BlockingActionHelper.getHttpCode(statusCode_);
    if (!start(resp, statusCode)) {
      return;
    }

    String acceptHeader = httpServletRequest.getHeader("Accept");
    BlockingActionHelper.TemplateType type =
        BlockingActionHelper.determineTemplateType(bct, acceptHeader);
    byte[] template = BlockingActionHelper.getTemplate(type);
    String contentType = BlockingActionHelper.getContentType(type);

    resp.setHeader("Content-length", Integer.toString(template.length));
    resp.setHeader("Content-type", contentType);
    try {
      OutputStream os = resp.getOutputStream();
      os.write(template);
      os.close();
    } catch (IOException e) {
      log.warn("Error sending error page", e);
    }
  }

  public static void commitBlockingResponse(
      HttpServletRequest httpServletRequest,
      HttpServletResponse resp,
      Flow.Action.RequestBlockingAction rba) {

    commitBlockingResponse(
        httpServletRequest, resp, rba.getStatusCode(), rba.getBlockingContentType());
  }

  private static boolean start(HttpServletResponse resp, int statusCode) {
    if (resp.isCommitted()) {
      log.warn("response already committed, we can't change it");
    }

    log.debug("Committing blocking response");

    resp.reset();
    resp.setStatus(statusCode);

    return true;
  }
}