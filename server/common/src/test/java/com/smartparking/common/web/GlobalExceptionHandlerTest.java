package com.smartparking.common.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smartparking.common.error.BizException;
import com.smartparking.common.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

class GlobalExceptionHandlerTest {

    @RestController
    static class ProbeController {
        @GetMapping("/biz")
        String biz() {
            throw new BizException(ErrorCode.CONFLICT, "设备正在执行上一条指令");
        }

        @GetMapping("/boom")
        String boom() {
            throw new IllegalStateException("unexpected");
        }
    }

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ProbeController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    void bizExceptionKeepsCodeAndMessage() throws Exception {
        mvc.perform(get("/biz"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(10009))
                .andExpect(jsonPath("$.message").value("设备正在执行上一条指令"));
    }

    @Test
    void unexpectedExceptionHidesDetails() throws Exception {
        mvc.perform(get("/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(19999))
                .andExpect(jsonPath("$.message").value("系统繁忙，请稍后重试"));
    }
}
