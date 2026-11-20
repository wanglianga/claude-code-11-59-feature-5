package com.citydrain.capacity;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class DrainCapacityApplication {

    public static void main(String[] args) {
        SpringApplication.run(DrainCapacityApplication.class, args);
    }
}
