package com.graduation.ragqa.config;

import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JsonConfig {
    // Snowflake IDs exceed JavaScript's safe integer range; keep them exact on the wire.
    @Bean
    Jackson2ObjectMapperBuilderCustomizer longIdentifiersAsStrings() {
        return builder -> builder.serializerByType(Long.class, ToStringSerializer.instance);
    }
}
