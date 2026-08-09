package demo.demo02.mapper;

import com.mybatisflex.core.BaseMapper;
import demo.demo02.entity.SessionEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SessionMapper extends BaseMapper<SessionEntity> {
}
