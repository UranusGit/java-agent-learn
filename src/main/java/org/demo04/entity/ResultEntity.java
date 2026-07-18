package org.demo04.entity;

public record ResultEntity(Object result) {
    public static ResultEntity of(Object result) {
        return new ResultEntity(result);
    }
}
