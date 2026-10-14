package vigil.api;

import java.io.IOException;
import java.time.Duration;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/** Serves the built web application from {@code classpath:/static/} (AR-W-01). */
@Configuration
class WebApplicationConfiguration implements WebMvcConfigurer {

  private static final String STATIC_LOCATION = "classpath:/static/";
  private static final String INDEX_HTML = "index.html";

  @Override
  public void addResourceHandlers(ResourceHandlerRegistry registry) {
    registry
        .addResourceHandler("/assets/**")
        .addResourceLocations(STATIC_LOCATION + "assets/")
        .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).immutable());
    registry
        .addResourceHandler("/**")
        .addResourceLocations(STATIC_LOCATION)
        .setCacheControl(CacheControl.noCache())
        .resourceChain(true)
        .addResolver(new IndexFallbackResolver());
  }

  /**
   * Answers a path that is no static file with {@code index.html}, except below /api/ and
   * /actuator/.
   */
  private static final class IndexFallbackResolver extends PathResourceResolver {

    @Override
    protected Resource getResource(String resourcePath, Resource location) throws IOException {
      Resource file = super.getResource(resourcePath, location);
      if (file != null || resourcePath.startsWith("api/") || resourcePath.startsWith("actuator/")) {
        return file;
      }
      return super.getResource(INDEX_HTML, location);
    }
  }
}
