package com.signal.global.config;

import java.io.IOException;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private static final Pattern SERVED_EXTENSIONS =
            Pattern.compile(".*\\.(jpe?g|png|pdf|mp4|mov)$", Pattern.CASE_INSENSITIVE);

    @Value("${file.upload-dir}")
    private String uploadDir;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String location = Path.of(uploadDir).toAbsolutePath().normalize().toUri().toString();
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations(location)
                // 캐시를 끈다. 켜면 탈퇴·기록 삭제로 지운 파일의 Resource를 계속 돌려줘 404 대신 500이 난다.
                .resourceChain(false)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String resourcePath, Resource loc) throws IOException {
                        // 허용 형식이 아닌 확장자(html, svg, js 등)는 서빙하지 않는다.
                        return SERVED_EXTENSIONS.matcher(resourcePath).matches()
                                ? super.getResource(resourcePath, loc) : null;
                    }
                });
    }
}
