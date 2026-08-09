# Sprint 1 · WebSocket + VAD + ASR

> P22 VoiceAgent · 第 1 周

---

## 目标

建立 WebSocket 语音通道，实现 VAD 端点检测和流式 ASR 实时转写。

## 任务清单

- [ ] WebSocket 语音通道建立
- [ ] 客户端音频采集（16kHz PCM）
- [ ] VAD 端点检测（能量 + 静音时长）
- [ ] 流式 ASR 集成（实时部分转写）
- [ ] 最终转写结果获取

## 音频格式约定

| 参数 | 值 | 说明 |
|------|-----|------|
| 采样率 | 16000 Hz | ASR 推荐采样率 |
| 位深度 | 16-bit PCM | 无压缩 |
| 声道 | 单声道 | 语音场景 |
| 帧大小 | 320 samples (20ms) | VAD 处理粒度 |

## 核心代码

### WebSocket 处理器

```java
@Component
public class VoiceWebSocketHandler extends AbstractWebSocketHandler {

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        var ctx = getContext(session);
        byte[] audio = message.getByteArray();

        VadState vadState = ctx.vad.process(audio, 16000);

        switch (vadState) {
            case SPEAKING, SPEECH_START -> asrClient.sendChunk(ctx, audio);
            case SPEECH_END -> {
                String finalText = asrClient.finalize(ctx);
                sendJson(session, Map.of("type", "transcript", "text", finalText));
                orchestrator.process(ctx, finalText, session);
            }
            default -> {} // SILENCE / PAUSE 忽略
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        if (message.getPayload().contains("interrupt")) {
            getContext(session).cancelGeneration();
        }
    }
}
```

### VAD 实现

```java
public class EnergyVad {
    private final double thresholdDb;
    private final int silenceMs;
    private boolean inSpeech = false;
    private long speechStart = 0, silenceStart = 0;

    public VadState process(byte[] frame, int sampleRate) {
        double db = computeDb(frame);
        boolean voice = db > thresholdDb;
        long now = System.currentTimeMillis();

        if (voice && !inSpeech) { inSpeech = true; speechStart = now; return SPEECH_START; }
        if (!voice && inSpeech) {
            if (silenceStart == 0) silenceStart = now;
            if (now - silenceStart >= silenceMs && now - speechStart >= 300) {
                inSpeech = false; silenceStart = 0; return SPEECH_END;
            }
            return IN_SPEECH_PAUSE;
        }
        if (voice) { silenceStart = 0; return SPEAKING; }
        return SILENCE;
    }

    private double computeDb(byte[] frame) {
        long sum = 0;
        for (int i = 0; i < frame.length; i += 2) {
            short s = (short) ((frame[i+1] << 8) | (frame[i] & 0xFF));
            sum += (long)s * s;
        }
        double rms = Math.sqrt((double)sum / (frame.length / 2));
        return rms < 1 ? 0 : 20 * Math.log10(rms);
    }
}
```

## 验收

- [ ] 客户端能通过 WebSocket 发送音频帧
- [ ] VAD 能区分语音和静音
- [ ] 用户说完（静音 > 700ms）后触发 ASR 最终转写
- [ ] 转写结果发送给客户端显示
