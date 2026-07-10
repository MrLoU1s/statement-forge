package com.muiyurocodes.cincom_jd_project;

import org.springframework.boot.SpringApplication;

public class TestCincomJdProjectApplication {

    public static void main(String[] args) {
        SpringApplication.from(CincomJdProjectApplication::main).with(TestcontainersConfiguration.class).run(args);
    }

}
