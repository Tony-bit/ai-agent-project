package denny.ai.agent;

import org.springframework.ai.model.openai.autoconfigure.OpenAiAudioSpeechAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiAudioTranscriptionAutoConfiguration;
import org.springframework.beans.factory.annotation.Configurable;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(exclude = {OpenAiAudioSpeechAutoConfiguration.class, OpenAiAudioTranscriptionAutoConfiguration.class})
@Configurable
public class Application {

    public static void main(String[] args){
        SpringApplication.run(Application.class);
    }

}
