package demo.demo02.entity;

import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@NoArgsConstructor
@AllArgsConstructor
@Builder
@Data
@Table("history")
public class HistoryEntity {
    @Id
    private String id;

    @Column("role")
    private String role;

    @Column("content")
    private String content;

    @Column("session_id")
    private String sessionId;

    @Column("run_id")
    private String runId;

    @Column(value = "create_time", onInsertValue = "now()")
    private LocalDateTime createTime;

    @Column(value = "create_time", onInsertValue = "now()", onUpdateValue = "new()")
    private LocalDateTime updateTime;
}
