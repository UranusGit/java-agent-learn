package demo.demo01.dto;

import lombok.Builder;

@Builder
public record FqrItem(String question, String answer) {
}
