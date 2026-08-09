package demo.demo02.service;

import com.mybatisflex.core.query.QueryWrapper;
import demo.demo02.entity.HistoryEntity;
import demo.demo02.mapper.HistoryMapper;
import demo.demo02.utils.IdUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class HistoryService {
    @Autowired
    private HistoryMapper history;

    /**
     * 添加一条历史记录
     */
    public String history(String sessionId, String content, String role, String runId) {
        String historyId = IdUtil.getId();
        HistoryEntity historyEntity = HistoryEntity.builder()
                .sessionId(sessionId)
                .id(historyId)
                .content(content)
                .role(role)
                .runId(runId)
                .build();

        history.insert(historyEntity);
        return historyId;
    }

    /**
     * 获取对话的历史记录
     */
    public List<HistoryEntity> histories(String sessionId) {
        QueryWrapper wrapper = QueryWrapper.create()
                .eq(HistoryEntity::getSessionId, sessionId)
                .orderBy(HistoryEntity::getCreateTime)
                .asc();
        return history.selectListByQuery(wrapper);
    }
}
