package denny.ai.agent;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {"denny.ai.agent", "denny.ai.agent.config"})
@EnableScheduling
@ConfigurationPropertiesScan
@MapperScan({"denny.ai.agent.infrastructure.dao"})
public class Application {

    public static void main(String[] args){
        SpringApplication.run(Application.class, args);
    }

}
