package vn.smartquiz.analytics;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;

// ClickHouse JDBC được cấu hình thủ công (application.yml không có
// spring.datasource để tránh Spring Boot autowire HikariCP vào CH URL).
@SpringBootApplication(exclude = {DataSourceAutoConfiguration.class})
public class AnalyticsServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AnalyticsServiceApplication.class, args);
    }
}
