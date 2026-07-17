package org.demo02.tools;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class EmailToos {
    @Tool(description = "给指定的用户发邮件")
    public String sendEmail(ToolContext context, @ToolParam(description = "邮件的内容") String content) {
        String id = context.getContext().get("id").toString();
        String email = "给 %s 发邮件成功，内容是: %s".formatted(id, content);
        System.out.println(email);
        return email;
    }
}
