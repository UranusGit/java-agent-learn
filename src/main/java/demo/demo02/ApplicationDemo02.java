package demo.demo02;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("demo.demo02.mapper")
public class ApplicationDemo02 {
    public static void main(String[] args) {
        SpringApplication.run(ApplicationDemo02.class, args);
    }
}
