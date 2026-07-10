package com.muiyurocodes.cincom_jd_project;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class CincomJdProjectApplicationTests {

    @Test
    void contextLoads() {
    }

}
