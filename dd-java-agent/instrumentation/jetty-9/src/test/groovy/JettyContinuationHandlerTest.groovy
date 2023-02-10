import org.eclipse.jetty.continuation.Continuation
import org.eclipse.jetty.continuation.ContinuationSupport
import org.eclipse.jetty.server.Request
import org.eclipse.jetty.server.handler.AbstractHandler

import javax.servlet.ServletException
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class JettyContinuationHandlerTest extends Jetty9Test {

  @Override
  AbstractHandler handler() {
    ContinuationTestHandler.INSTANCE
  }

  static class ContinuationTestHandler extends AbstractHandler {
    static final ContinuationTestHandler INSTANCE = new ContinuationTestHandler()
    final ExecutorService executorService = Executors.newSingleThreadExecutor()

    @Override
    void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
      final Continuation continuation = ContinuationSupport.getContinuation(request)
      // some versions of jetty (like 9.4.15.v20190215) get into a loop:
      // after an exception from handleRequest, the error will be handled here again;
      // calling handleRequest will cause a new exception, and the process will repeat.
      // this happens in the /exception endpoint
      if (!request.getAttribute('javax.servlet.error.status_code')) {
        if (continuation.initial) {
          continuation.suspend()
          executorService.execute {
            continuation.resume()
          }
        } else {
          handleRequest(baseRequest, response)
        }
      }
      baseRequest.handled = true
    }
  }
}