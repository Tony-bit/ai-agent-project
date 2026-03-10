package denny.ai.agent;

import org.springframework.beans.factory.annotation.Configurable;
import org.springframework.boot.SpringApplication;

@Configurable
public class Application {

    public static void main(String[] args){
        SpringApplication.run(Application.class);
    }

}
