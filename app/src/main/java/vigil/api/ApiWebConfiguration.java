package vigil.api;

import java.time.Duration;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import vigil.shared.TenantId;
import vigil.shared.VigilError;
import vigil.shared.VigilError.Code;

/**
 * Registers the access interceptor for {@code /api/**}, the {@code TenantId} and {@code ApiCaller}
 * handler parameters, the message converters without YAML (AR-I-01) and the live stream timings
 * (AR-TM-04).
 */
@Configuration
@Profile("!migrate")
class ApiWebConfiguration implements WebMvcConfigurer {

  private final AccessInterceptor accessInterceptor;

  ApiWebConfiguration(AccessInterceptor accessInterceptor) {
    this.accessInterceptor = accessInterceptor;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(accessInterceptor).addPathPatterns("/api/**");
  }

  @Override
  public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
    resolvers.add(new TenantIdResolver());
    resolvers.add(new CallerResolver());
  }

  /** {@code vigil-api} reads and writes JSON only, although YAML is on the classpath (AR-I-01). */
  @Override
  public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
    converters.removeIf(
        converter ->
            converter.getSupportedMediaTypes().stream()
                .anyMatch(MediaType.APPLICATION_YAML::equalsTypeAndSubtype));
  }

  /** A heartbeat every 15 s and the end of a stream after 300 s (AR-TM-04). */
  @Bean
  LiveTimings liveTimings() {
    return new LiveTimings(Duration.ofSeconds(15), Duration.ofSeconds(300));
  }

  /** The tenant of the request's host name (AR-I-02). */
  private static final class TenantIdResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
      return parameter.getParameterType() == TenantId.class;
    }

    @Override
    public TenantId resolveArgument(
        MethodParameter parameter,
        ModelAndViewContainer container,
        NativeWebRequest request,
        WebDataBinderFactory binderFactory) {
      if (request.getAttribute(HostTenantFilter.TENANT_ATTRIBUTE, NativeWebRequest.SCOPE_REQUEST)
          instanceof TenantId tenant) {
        return tenant;
      }
      throw new VigilError(Code.UNKNOWN_HOST);
    }
  }

  /** The logged-in caller of the request. */
  private static final class CallerResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
      return parameter.getParameterType() == ApiCaller.class;
    }

    @Override
    public ApiCaller resolveArgument(
        MethodParameter parameter,
        ModelAndViewContainer container,
        NativeWebRequest request,
        WebDataBinderFactory binderFactory) {
      return AccessInterceptor.caller(
          request.getNativeRequest(jakarta.servlet.http.HttpServletRequest.class));
    }
  }
}
