package demo.demo01.dto;

import lombok.Builder;

@Builder
public record OrderInfo(String orderId, String status, String trackingNumber) {
}
