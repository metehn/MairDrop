package com.metehan.mairdrop;

import jakarta.servlet.MultipartConfigElement;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.servlet.MultipartConfigFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.util.unit.DataSize;

@SpringBootApplication
public class MairdropApplication {

	public static void main(String[] args) {
		SpringApplication.run(MairdropApplication.class, args);
	}
	@Bean
	public MultipartConfigElement multipartConfigElement() {
		MultipartConfigFactory factory = new MultipartConfigFactory();
		factory.setMaxFileSize(DataSize.ofBytes(-1)); // Sınırsız
		factory.setMaxRequestSize(DataSize.ofBytes(-1)); // Sınırsız
		return factory.createMultipartConfig();
	}
}
