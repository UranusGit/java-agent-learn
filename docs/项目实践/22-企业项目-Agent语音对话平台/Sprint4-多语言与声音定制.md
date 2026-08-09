# Sprint 4 · 多语言与声音定制

> P22 VoiceAgent · 第 4 周

---

## 目标

支持中英文双语语音对话，提供多种声音选择和参数定制。

## 任务清单

- [ ] 语言自动检测（从 ASR 结果判断语言）
- [ ] 多语言 ASR 支持（中英文混合）
- [ ] 多语言 TTS 支持（按回复语言匹配声音）
- [ ] 多种声音选择（男声/女声/不同风格）
- [ ] 语速/音调参数可配置
- [ ] 声纹克隆接口（可选）

## 语言检测

```java
public class LanguageDetector {
    public String detect(String text) {
        int chineseChars = 0, asciiChars = 0;
        for (char c : text.toCharArray()) {
            if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN) chineseChars++;
            else if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z') asciiChars++;
        }
        if (chineseChars > asciiChars) return "zh-CN";
        if (asciiChars > 0) return "en-US";
        return "zh-CN"; // 默认中文
    }
}
```

## 声音配置

```java
public record VoiceConfig(
    String voiceId,     // 音色 ID（如 "zh-XiaoxiaoNeural"）
    double speed,       // 语速 0.5-2.0
    double pitch,       // 音调 -50 到 +50
    String style,       // 风格：cheerful/sad/professional
    String format       // 音频格式：pcm_16000/mp3
) {
    public static VoiceConfig defaults(String language) {
        return switch (language) {
            case "zh-CN" -> new VoiceConfig("zh-CN-XiaoxiaoNeural", 1.0, 0, "friendly", "pcm_16000");
            case "en-US" -> new VoiceConfig("en-US-JennyNeural", 1.0, 0, "friendly", "pcm_16000");
            default -> new VoiceConfig("zh-CN-XiaoxiaoNeural", 1.0, 0, "friendly", "pcm_16000");
        };
    }
}
```

## 声音目录 API

```java
@GetMapping("/api/voice/catalog")
public List<VoiceProfile> catalog() {
    return List.of(
        new VoiceProfile("zh-CN-XiaoxiaoNeural", "晓晓", "女声", "温暖亲切", "zh-CN"),
        new VoiceProfile("zh-CN-YunxiNeural", "云希", "男声", "沉稳专业", "zh-CN"),
        new VoiceProfile("en-US-JennyNeural", "Jenny", "Female", "Friendly", "en-US"),
        new VoiceProfile("en-US-GuyNeural", "Guy", "Male", "Professional", "en-US")
    );
}

@PostMapping("/api/voice/config")
public Map<String, Object> updateConfig(@RequestBody VoiceConfig config) {
    sessionManager.updateVoiceConfig(config);
    return Map.of("status", "updated");
}
```

## 验收

- [ ] 能自动检测用户语言并路由到对应 LLM 提示词
- [ ] 中文语音清晰自然
- [ ] 可切换不同声音（男/女/风格）
- [ ] 语速和音调可调
- [ ] 多轮对话中语言切换正常
