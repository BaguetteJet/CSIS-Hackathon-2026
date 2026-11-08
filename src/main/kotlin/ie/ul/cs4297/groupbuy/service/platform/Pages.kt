package ie.ul.cs4297.groupbuy.service.platform

import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * Serves your pages (src/main/resources/pages) at /app/<service>/, where the gateway sends them.
 * The pages and the API then share one origin, so there are no cross-origin errors.
 */
@Configuration
class PagesConfig(private val settings: PlatformSettings) : WebMvcConfigurer {
    private val base get() = "/app/${settings.serviceName}"

    override fun addResourceHandlers(registry: ResourceHandlerRegistry) {
        registry.addResourceHandler("$base/**").addResourceLocations("classpath:/pages/")
    }

    override fun addViewControllers(registry: ViewControllerRegistry) {
        registry.addRedirectViewController(base, "$base/")
        registry.addViewController("$base/").setViewName("forward:$base/index.html")
    }
}
