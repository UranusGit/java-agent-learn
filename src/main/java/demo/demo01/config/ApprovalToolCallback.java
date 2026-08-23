package demo.demo01.config;

import demo.demo01.service.ApprovalCenter;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class ApprovalToolCallback implements ToolCallback {
    @Autowired
    private ToolCallback delegate;

    @Autowired
    private ApprovalCenter approvalCenter;

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput, @Nullable ToolContext toolContext) {
        return ToolCallback.super.call(toolInput, toolContext);
    }

    @Override
    public String call(String toolInput) {
        return "";
    }
}
