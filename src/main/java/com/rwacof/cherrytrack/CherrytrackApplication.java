package com.rwacof.cherrytrack;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class CherrytrackApplication {

	public static void main(String[] args) {
		SpringApplication.run(CherrytrackApplication.class, args);
	}

}
