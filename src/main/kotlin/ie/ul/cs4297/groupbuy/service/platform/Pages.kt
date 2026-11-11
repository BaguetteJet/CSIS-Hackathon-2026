package ie.ul.cs4297.groupbuy.service.platform

import org.springframework.context.annotation.Configuration
import org.springframework.http.CacheControl
import org.springframework.core.io.ClassPathResource
import org.springframework.core.io.Resource
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import org.springframework.web.servlet.resource.PathResourceResolver

/**
 * Serves your pages (src/main/resources/pages) at /app/<service>/, where the gateway sends them.
 * The pages and the API then share one origin, so there are no cross-origin errors.
 *
 * A path without an extension answers the page of that name if there is one: /app/support/complain
 * (the link Student experience's My orders uses) answers complain.html.
 *
 * A single-page app (Student experience's React build, copied into src/main/resources/pages) works
 * too: any other path that is not a file and has no file extension, such as /app/experience/deals/<id>,
 * answers index.html, so the app's own router shows the right screen after a refresh. A missing
 * file with an extension (.js, .css, .png) is still a 404.
 */
@Configuration
class PagesConfig(private val settings: PlatformSettings) : WebMvcConfigurer {
    private val base get() = "/app/${settings.serviceName}"

    override fun addResourceHandlers(registry: ResourceHandlerRegistry) {
        registry.addResourceHandler("$base/**")
            .addResourceLocations("classpath:/pages/")
            // Browsers re-check every page after each deploy (a cheap 304 when unchanged), so
            // nobody is left on the previous version's page or stylesheet.
            .setCacheControl(CacheControl.noCache())
            .resourceChain(true)
            .addResolver(object : PathResourceResolver() {
                override fun getResource(path: String, location: Resource): Resource? {
                    super.getResource(path, location)?.let { return it }
                    val lastPart = path.substringAfterLast('/')
                    if (lastPart.contains('.')) return null
                    // A plain page without its .html: /app/support/complain answers complain.html.
                    if (lastPart.isNotEmpty()) super.getResource("$path.html", location)?.let { return it }
                    return ClassPathResource("pages/index.html").takeIf { it.exists() }
                }
            })
    }

    override fun addViewControllers(registry: ViewControllerRegistry) {
        registry.addRedirectViewController(base, "$base/")
        registry.addViewController("$base/").setViewName("forward:$base/index.html")
    }
}
