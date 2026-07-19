package org.demo06.util;

import java.util.UUID;

public class IdUtils {
    public static String getId() {
        return UUID.randomUUID().toString();
    }
}
