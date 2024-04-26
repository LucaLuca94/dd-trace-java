package datadog.smoketest.springboot;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
public class ViewController {

  @GetMapping("/test")
  @ResponseBody
  public String hello() {
    return "world";
  }

  @GetMapping("/test_xss_in_jsp")
  public String test() {
    return "test_xss";
  }
}
