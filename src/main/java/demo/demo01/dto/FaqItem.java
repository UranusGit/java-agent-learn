package demo.demo01.dto;

import lombok.Builder;

@Builder
public record FaqItem(String question, String answer) {
}
