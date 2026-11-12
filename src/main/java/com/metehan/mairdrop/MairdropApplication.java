package com.metehan.mairdrop;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class MairdropApplication {

	public static void main(String[] args) {
		SpringApplication.run(MairdropApplication.class, args);
	}
}
