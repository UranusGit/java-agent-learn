package demo.demo02.utils;

import tools.jackson.databind.ObjectMapper;

public class JsonUtil {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static String json(Object entity) {
        return MAPPER.writeValueAsString(entity);
    }

    public static Object entity(String json, Class clazz) {
        return MAPPER.readValue(json, clazz);
    }
}
