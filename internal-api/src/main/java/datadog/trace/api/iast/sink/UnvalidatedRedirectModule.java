package datadog.trace.api.iast.sink;

import javax.annotation.Nullable;

public interface UnvalidatedRedirectModule extends HttpHeaderModule {

  void onRedirect(@Nullable String value);
}