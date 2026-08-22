package demo.demo01.dto;


public record ExchangeSlotState(String sessionId, String orderId, String newSize, String status) {
    public boolean isComplete() {
        return orderId != null && newSize != null;
    }
}
