package dev.treyer.sagapay.notification;

import org.springframework.boot.SpringApplication;

public class TestNotificationConSumerApplication {

    public static void main(String[] args) {
        SpringApplication.from(NotificationConSumerApplication::main)
                .with(TestcontainersConfiguration.class)
                .run(args);
    }
}
