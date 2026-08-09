package demo.demo02.mapper;

import com.mybatisflex.core.BaseMapper;
import demo.demo02.entity.HistoryEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface HistoryMapper extends BaseMapper<HistoryEntity> {
}
