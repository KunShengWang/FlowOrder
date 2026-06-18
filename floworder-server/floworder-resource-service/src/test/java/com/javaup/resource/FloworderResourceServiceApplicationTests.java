package com.javaup.resource;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

class FloworderResourceServiceApplicationTests {

    @Test
    void contextLoads() {
        String format = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS"));
        System.out.println(format);
    }

}
