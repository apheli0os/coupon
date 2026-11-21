package com.mall.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mall.common.R;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JacksonConfigTest {

    @Test
    void longToStringCustomizer_shouldPreserve19DigitSnowflakeId() throws Exception {
        JacksonConfig config = new JacksonConfig();
        Jackson2ObjectMapperBuilder builder = new Jackson2ObjectMapperBuilder();
        config.longToStringCustomizer().customize(builder);
        ObjectMapper mapper = builder.build();

        long snowflakeId = 1923456789012345678L;
        String json = mapper.writeValueAsString(R.ok(snowflakeId));

        assertTrue(json.contains("\"1923456789012345678\""), "19位雪花ID应序列化为JSON字符串避免JS精度丢失: " + json);
        assertTrue(json.contains("\"code\":0"), "int类型的code仍应保持为JSON数字: " + json);
        assertEquals(snowflakeId, mapper.readValue("\"1923456789012345678\"", Long.class));
    }
}
