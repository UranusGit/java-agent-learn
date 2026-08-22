package demo.demo01.dto;

public record IntentResult(Intent intent, float confidence, String reason) {
    public enum Intent {
        CHITCHAT,
        FAQ,
        BUSINESS
    }
}
