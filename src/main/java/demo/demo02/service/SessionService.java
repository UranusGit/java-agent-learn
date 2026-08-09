package demo.demo02.service;

import demo.demo02.content.SessionContent;
import demo.demo02.entity.SessionEntity;
import demo.demo02.mapper.SessionMapper;
import demo.demo02.utils.IdUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class SessionService {
    @Autowired
    private SessionMapper mapper;

    @Autowired
    private StatusService status;

    /**
     * 创建Session
     */
    public String createSession(String title) {
        String sessionId = IdUtil.getId();
        SessionEntity sessionEntity = SessionEntity.builder()
                .id(sessionId)
                .title(title)
                .build();

        mapper.insert(sessionEntity);

        // 设置redis状态
        status.session(sessionId, SessionContent.start.name());
        return sessionId;
    }

    /**
     * 获取会话列表
     */
    public List<SessionEntity> sessions() {
        return mapper.selectAll();
    }
}
