package com.muiyurocodes.statementforge;

import org.springframework.boot.SpringApplication;

public class TestStatementForgeApplication {

    public static void main(String[] args) {
        SpringApplication.from(StatementForgeApplication::main).with(TestcontainersConfiguration.class).run(args);
    }

}
