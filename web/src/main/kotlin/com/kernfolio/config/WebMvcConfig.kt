package com.kernfolio.config

import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration
class WebMvcConfig : WebMvcConfigurer {

    override fun addViewControllers(registry: ViewControllerRegistry) {
        registry.addViewController("/").setViewName("index")
        registry.addViewController("/login").setViewName("login")
        registry.addViewController("/register").setViewName("register")
        registry.addViewController("/about").setViewName("about")
    }
}
