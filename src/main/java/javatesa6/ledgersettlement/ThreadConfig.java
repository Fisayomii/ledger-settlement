package javatesa6.ledgersettlement;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.support.TaskExecutorAdapter;

import java.util.concurrent.Executors;

/**
 * Replaces Spring Boot's pooled applicationTaskExecutor (a ThreadPoolTaskExecutor) with one virtual
 * thread per task. Request threads are switched by spring.threads.virtual.enabled in application.yaml.
 */
@Configuration(proxyBeanMethods = false)
public class ThreadConfig {

    @Bean
    public AsyncTaskExecutor applicationTaskExecutor() {
        return new TaskExecutorAdapter(Executors.newVirtualThreadPerTaskExecutor());
    }
}
