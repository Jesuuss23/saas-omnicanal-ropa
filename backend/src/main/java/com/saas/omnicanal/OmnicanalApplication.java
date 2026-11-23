package com.saas.omnicanal;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class OmnicanalApplication {

	public static void main(String[] args) {
		SpringApplication.run(OmnicanalApplication.class, args);
	}

}
