package org.openpaas.servicebroker.kubernetes.config;

import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.support.SpringBootServletInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * WebXml 설정 클래스
 *
 * @author Hyerin
 * @since 2018.07.24
 * @version 20180724
 */
public class WebXml extends SpringBootServletInitializer {
	@Override
	protected SpringApplicationBuilder configure(SpringApplicationBuilder application) {
		return application.sources(Application.class);
	}

}