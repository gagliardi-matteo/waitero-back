package com.waitero.campaign;

import com.waitero.back.BackApplication;
import com.waitero.back.service.DemoStreamService;
import com.waitero.back.service.OrderStreamService;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.SpringApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import javax.sql.DataSource;
import java.lang.management.ManagementFactory;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.transaction.annotation.Transactional;
import com.waitero.back.repository.BackofficeUserRepository;
import com.waitero.back.entity.BackofficeUser;
import com.waitero.back.entity.BackofficeRole;
import com.waitero.back.service.JwtService;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Test-classpath only. Never packaged with the production application. */
public class CampaignApplication {
    public static void main(String[] args) {
        SpringApplication.run(new Class<?>[]{BackApplication.class, DiagnosticsConfiguration.class}, args);
    }

    @Configuration(proxyBeanMethods = false)
    @Profile("test")
    static class DiagnosticsConfiguration {
        @Bean FixtureController campaignFixtures(JdbcTemplate db, BackofficeUserRepository users, PasswordEncoder passwords, JwtService jwt) {
            return new FixtureController(db, users, passwords, jwt);
        }
        @Bean DiagnosticsController campaignDiagnostics(OrderStreamService orders, DemoStreamService demo, DataSource dataSource) {
            return new DiagnosticsController(orders, demo, dataSource);
        }
    }

    @RestController
    static class FixtureController {
        private final JdbcTemplate db;
        private final BackofficeUserRepository users;
        private final PasswordEncoder passwords;
        private final JwtService jwt;
        FixtureController(JdbcTemplate db, BackofficeUserRepository users, PasswordEncoder passwords, JwtService jwt) {
            this.db=db; this.users=users; this.passwords=passwords; this.jwt=jwt;
        }
        @PostMapping("/api/customer/campaign/fixtures")
        @Transactional
        public Map<String, Object> create() {
            String group=UUID.randomUUID().toString();
            Long category=db.queryForObject("insert into menu_category (business_type,code,label,sort_order,active) values ('RISTORANTE',?,'Fixture browser',1,true) returning id",Long.class,"E2E-"+group);
            return Map.of("tenants", List.of(seed(group,"A",category),seed(group,"B",category)));
        }
        private Map<String, Object> seed(String group,String label,Long category) {
            LocalDateTime now=LocalDateTime.now();
            String email="browser-"+label+"-"+group+"@test.local",password="Fixture-"+UUID.randomUUID();
            Long restaurant=db.queryForObject("insert into ristoratore (email,nome,password,provider,created_at,latitude,longitude,allowed_radius_meters) values (?,?,?,'LOCAL',?,45,9,100) returning id",Long.class,email,"Browser "+label,"unused",now);
            for(DayOfWeek day:DayOfWeek.values()) db.update("insert into service_hours (restaurant_id,day_of_week,start_time,end_time) values (?,?,?,?)",restaurant,day.name(),LocalTime.MIN,LocalTime.of(23,59,59));
            String publicId="b_"+UUID.randomUUID().toString().replace("-", "").substring(0,24),qr=jwt.generateQrToken(restaurant,1);
            db.update("insert into tavoli (ristoratore_id,table_public_id,numero,nome,coperti,attivo,qr_token,created_at,updated_at) values (?,?,1,'Browser table',4,true,?,?,?)",restaurant,publicId,qr,now,now);
            Long dish=db.queryForObject("insert into piatto (nome,descrizione,prezzo,disponibile,categoria_id,consigliato,ristoratore_id) values (?, 'Fixture',10,true,?,false,?) returning id",Long.class,"Piatto browser "+label,category,restaurant);
            users.save(BackofficeUser.builder().email(email).nome("Browser owner "+label).passwordHash(passwords.encode(password)).provider("LOCAL").role(BackofficeRole.RISTORATORE).restaurantId(restaurant).createdAt(now).build());
            return Map.of("restaurantId",restaurant,"dishId",dish,"email",email,"password",password,"publicId",publicId,"qrUrl","/menu/"+publicId+"/"+qr);
        }
    }

    @RestController
    static class DiagnosticsController {
        private final OrderStreamService orders;
        private final DemoStreamService demo;
        private final DataSource dataSource;
        DiagnosticsController(OrderStreamService orders, DemoStreamService demo, DataSource dataSource) {
            this.orders = orders; this.demo = demo; this.dataSource = dataSource;
        }
        @GetMapping("/api/customer/campaign/metrics")
        Map<String, Object> metrics() throws Exception {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("time", System.currentTimeMillis());
            data.put("heapUsed", ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
            data.put("threads", ManagementFactory.getThreadMXBean().getThreadCount());
            var os = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
            data.put("processCpuLoad", os.getProcessCpuLoad());
            data.put("processCpuTime", os.getProcessCpuTime());
            data.put("restaurantEmitters", count(orders, "emittersByRestaurant"));
            data.put("customerEmitters", count(orders, "emittersByCustomerTable"));
            data.put("demoEmitters", count(demo, "emitters"));
            HikariDataSource pool = dataSource.unwrap(HikariDataSource.class);
            if (pool.getHikariPoolMXBean() != null) {
                data.put("dbActive", pool.getHikariPoolMXBean().getActiveConnections());
                data.put("dbIdle", pool.getHikariPoolMXBean().getIdleConnections());
                data.put("dbWaiting", pool.getHikariPoolMXBean().getThreadsAwaitingConnection());
                data.put("dbTotal", pool.getHikariPoolMXBean().getTotalConnections());
            }
            try (var c = dataSource.getConnection(); var s = c.createStatement(); var r = s.executeQuery(
                    "select count(*) filter (where wait_event_type = 'Lock'), count(*) filter (where state = 'active'), coalesce(max(extract(epoch from (clock_timestamp()-query_start))) filter (where state = 'active'),0) from pg_stat_activity where datname=current_database() and pid<>pg_backend_pid()")) {
                r.next(); data.put("dbLockWaiters", r.getLong(1)); data.put("dbActiveQueries", r.getLong(2)); data.put("dbLongestActiveSeconds", r.getDouble(3));
            }
            return data;
        }
        @SuppressWarnings("unchecked")
        private long count(Object service, String field) {
            Map<Object, Collection<?>> map = (Map<Object, Collection<?>>) ReflectionTestUtils.getField(service, field);
            return map == null ? 0 : map.values().stream().mapToLong(Collection::size).sum();
        }
    }
}
