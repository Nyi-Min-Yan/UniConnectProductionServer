package com.unicconnect.config;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Keeps the (serverless) database connection warm.
 *
 * The Neon pooler suspends its compute after a few minutes of idle time, and
 * waking it up again costs tens of seconds — every request issued during that
 * wake-up (including the very first page loads after lunch, or the first
 * request after the app has been idle) stalls on the pooler instead of the
 * application itself. Pinging the database every two minutes keeps the compute
 * awake so all normal requests stay fast. Failures are ignored on purpose: the
 * connection pool and the next ping recover automatically.
 */
@Component
public class DatabaseKeepAlive {

    private static final Logger log = LoggerFactory.getLogger(DatabaseKeepAlive.class);

    private final JdbcTemplate jdbcTemplate;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "db-keepalive");
        t.setDaemon(true);
        return t;
    });

    public DatabaseKeepAlive(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    void start() {
        scheduler.scheduleWithFixedDelay(this::ping, 1, 2, TimeUnit.MINUTES);
    }

    private void ping() {
        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
        } catch (Exception e) {
            log.debug("Database keep-alive ping failed (will retry): {}", e.getMessage());
        }
    }

    @PreDestroy
    void stop() {
        scheduler.shutdownNow();
    }
}