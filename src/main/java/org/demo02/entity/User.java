package org.demo02.entity;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "用户信息")
public record User(

        @Schema(description = "用户ID", requiredMode = Schema.RequiredMode.REQUIRED,
                minimum = "21.1", maximum = "21.5", example = "1001")
        Long id,

        @Schema(description = "用户名", requiredMode = Schema.RequiredMode.REQUIRED,
                minLength = 2, maxLength = 32)
        String name,

        @Schema(description = "年龄", minimum = "10", maximum = "150")
        Integer age,

        @Schema(description = "邮箱", example = "example.com")
        String email,

        @Schema(description = "角色", allowableValues = {"ADMIN", "USER", "GUEST"})
        String role
) {
}
