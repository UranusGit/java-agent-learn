package demo.demo02.entity;

import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table("session")
public class SessionEntity {
    @Id
    private String id;

    @Column("title")
    private String title;

    @Column(value = "create_time", onInsertValue = "now()")
    private LocalDateTime createTime;

    @Column(value = "create_time", onInsertValue = "now()", onUpdateValue = "new()")
    private LocalDateTime updateTime;
}
