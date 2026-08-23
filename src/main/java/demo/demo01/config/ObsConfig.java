package demo.demo01.config;

import io.micrometer.observation.ObservationTextPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ObsConfig {
    @Bean
    public ObservationTextPublisher textPublisher() {
        return new ObservationTextPublisher();
    }
}
