package com.waitero.back.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.waitero.back.BackApplication;
import com.waitero.back.entity.BackofficeRole;
import com.waitero.back.entity.BackofficeUser;
import com.waitero.back.service.JwtService;
import com.waitero.back.repository.BackofficeUserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real HTTP workflow: no authorization, device, schedule, service or database mocks. */
@SpringBootTest(classes = BackApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "server.shutdown=immediate")
@ActiveProfiles("test")
class RealRestaurantWorkflowPostgresTest {
    private static final long A=980001L, B=980002L, CATEGORY=980010L;
    @LocalServerPort int port;
    @Autowired TestRestTemplate http;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    @Autowired JwtService jwt;
    @Autowired BackofficeUserRepository users;
    @Autowired PasswordEncoder passwords;
    @Autowired javax.sql.DataSource dataSource;
    private Tenant a,b;
    record Tenant(long restaurant,long table,long dish,String qr,String owner,String publicId) {}

    @BeforeEach void seed() {
        cleanup();
        db.update("insert into menu_category (id,business_type,code,label,sort_order,active) values (?,?,?,?,?,?)",CATEGORY,"RISTORANTE","REAL_WORKFLOW","Workflow",99,true);
        a=seedTenant(A,980011L,980101L); b=seedTenant(B,980012L,980102L);
    }
    private Tenant seedTenant(long restaurant,long table,long dish) {
        db.update("insert into ristoratore (id,email,nome,password,provider,created_at,latitude,longitude,allowed_radius_meters) values (?,?,?,?,?,?,?,?,?)",restaurant,"workflow-"+restaurant+"@test.local","Workflow "+restaurant,"unused","LOCAL",LocalDateTime.now(),45.0,9.0,100);
        // Explicit legitimate opening schedule: every day, all times except the last nanosecond.
        for(DayOfWeek day:DayOfWeek.values()) db.update("insert into service_hours (restaurant_id,day_of_week,start_time,end_time) values (?,?,?,?)",restaurant,day.name(),LocalTime.MIN,LocalTime.of(23,59,59));
        String qr=jwt.generateQrToken(restaurant,1),publicId="real-workflow-"+restaurant;
        LocalDateTime now=LocalDateTime.now();
        db.update("insert into tavoli (id,ristoratore_id,table_public_id,numero,nome,coperti,attivo,qr_token,created_at,updated_at) values (?,?,?,?,?,?,?,?,?,?)",table,restaurant,publicId,1,"Workflow table",4,true,qr,now,now);
        db.update("insert into piatto (id,nome,descrizione,prezzo,disponibile,categoria_id,consigliato,ristoratore_id) values (?,?,?,?,?,?,?,?)",dish,"Workflow dish "+restaurant,"Fixture",10,true,CATEGORY,false,restaurant);
        String email="workflow-"+restaurant+"@test.local",password="Workflow-fixture-123";
        users.save(BackofficeUser.builder().email(email).nome("Workflow owner").provider("LOCAL").passwordHash(passwords.encode(password)).role(BackofficeRole.RISTORATORE).restaurantId(restaurant).createdAt(now).build());
        String owner=ok(HttpMethod.POST,"/api/auth/local-login",Map.of("email",email,"password",password),null).path("accessToken").asText();
        assertFalse(owner.isBlank(),"Actual login must return access JWT");
        return new Tenant(restaurant,table,dish,qr,owner,publicId);
    }

    @Test void twoRestaurantsWithTwoCustomersEachKeepOrdersRealtimePaymentAndNewIntentionsCoherent() throws Exception {
        for(Tenant tenant:List.of(a,b)) {
            for(int client=1;client<=2;client++) scan(tenant,client);
            assertEquals(2,deviceCount(tenant));
            JsonNode menu=ok(HttpMethod.GET,"/api/customer/menu/piatti/"+tenant.restaurant(),null,null);
            assertTrue(menu.toString().contains("Workflow dish "+tenant.restaurant()));
            assertFalse(menu.toString().contains("Workflow dish "+other(tenant).restaurant()));
            // Shared table cart: add three and reduce one before submission.
            mutate(tenant,1,3); JsonNode changed=mutate(tenant,1,-1);
            assertEquals(2,changed.path("items").get(0).path("quantity").asInt());
        }
        try(SseProbe streamA=new SseProbe(a); SseProbe streamB=new SseProbe(b)) {
            List<Map<String,Object>> submissions=new ArrayList<>();
            List<CompletableFuture<JsonNode>> inFlight=new ArrayList<>();
            for(Tenant tenant:List.of(a,b)) for(int client=1;client<=2;client++) {
                Map<String,Object> body=submitBody(tenant,client,client+1);
                submissions.add(body);
                inFlight.add(CompletableFuture.supplyAsync(()->ok(HttpMethod.POST,"/api/customer/orders",body,null)));
            }
            CompletableFuture.allOf(inFlight.toArray(CompletableFuture[]::new)).get(30,TimeUnit.SECONDS);
            long orderA=inFlight.get(0).join().path("id").asLong(),orderB=inFlight.get(2).join().path("id").asLong();
            assertEquals(orderA,inFlight.get(1).join().path("id").asLong());
            assertEquals(orderB,inFlight.get(3).join().path("id").asLong());
            assertNotEquals(orderA,orderB);
            streamA.awaitOrder(orderA,a.restaurant()); streamB.awaitOrder(orderB,b.restaurant());
            // Technical retries retain request-ID; two distinct customer intentions both survive.
            for(Map<String,Object> body:submissions) ok(HttpMethod.POST,"/api/customer/orders",body,null);
            assertOrder(a,orderA,5,"50.00"); assertOrder(b,orderB,5,"50.00");
            for(Tenant tenant:List.of(a,b)) {
                JsonNode dashboard=ok(HttpMethod.GET,"/api/orders/active",null,tenant.owner());
                assertEquals(1,dashboard.size());
                assertEquals(tenant.restaurant(),dashboard.get(0).path("restaurantId").asLong());
                JsonNode state=ok(HttpMethod.GET,"/api/customer/orders/state"+credentialsQuery(tenant,1),null,null);
                assertEquals(5,state.path("currentOrder").path("items").get(0).path("quantita").asInt());
            }
            assertEquals(HttpStatus.NOT_FOUND,request(HttpMethod.GET,"/api/orders/"+orderB,null,a.owner()).getStatusCode());
            assertEquals(HttpStatus.NOT_FOUND,request(HttpMethod.POST,"/api/orders/"+orderB+"/pay",null,a.owner()).getStatusCode());
            Map<String,Object> mixed=submitBody(a,1,1); mixed.put("restaurantId",String.valueOf(B));
            assertEquals(HttpStatus.FORBIDDEN,request(HttpMethod.POST,"/api/customer/orders",mixed,null).getStatusCode());
            for(Tenant tenant:List.of(a,b)) {
                long oldOrder=tenant==a?orderA:orderB;
                JsonNode paid=ok(HttpMethod.POST,"/api/orders/"+oldOrder+"/pay",Map.of("paymentMode","FULL"),tenant.owner());
                assertEquals("PAGATO",paid.path("status").asText());
                assertEquals(0,deviceCount(tenant));
                assertEquals(0,ok(HttpMethod.GET,"/api/orders/active",null,tenant.owner()).size());
                // Payment closes device associations. Both customers scan the printed QR again.
                scan(tenant,1);scan(tenant,2);
                JsonNode next=ok(HttpMethod.POST,"/api/customer/orders",submitBody(tenant,1,2),null);
                assertNotEquals(oldOrder,next.path("id").asLong());
                assertOrder(tenant,next.path("id").asLong(),2,"20.00");
                assertEquals(1,ok(HttpMethod.GET,"/api/orders/history",null,tenant.owner()).size());
                assertEquals(1,ok(HttpMethod.GET,"/api/orders/active",null,tenant.owner()).size());
            }
        }
    }

    @Test void concurrentFullPaymentsMustNotOverpayOrDuplicateFinancialRecords() throws Exception {
        scan(a,1);
        long order=ok(HttpMethod.POST,"/api/customer/orders",submitBody(a,1,5),null).path("id").asLong();
        assertConcurrentFullPayments(order);
    }

    @Test void concurrentFullPaymentsForManualOrderMustNotOverpay() throws Exception {
        long order=ok(HttpMethod.POST,"/api/orders/manual",Map.of("tableId",1,"items",List.of(Map.of("dishId",a.dish(),"quantity",5))),a.owner()).path("id").asLong();
        assertConcurrentFullPayments(order);
    }

    @Test void invalidPaymentAmountsMustReturnBadRequestWithoutMutations() throws Exception {
        long order=ok(HttpMethod.POST,"/api/orders/manual",Map.of("tableId",1,"items",List.of(Map.of("dishId",a.dish(),"quantity",5))),a.owner()).path("id").asLong();
        for (java.math.BigDecimal amount : List.of(new java.math.BigDecimal("-1"), new java.math.BigDecimal("100"))) {
            assertEquals(HttpStatus.BAD_REQUEST,request(HttpMethod.POST,"/api/orders/"+order+"/pay",Map.of("paymentMode","FULL","amount",amount),a.owner()).getStatusCode());
        }
        assertEquals(0,db.queryForObject("select count(*) from customer_order_payments where ordine_id=?",Integer.class,order));
        assertOrder(a,order,5,"50.00");
    }

    private void assertConcurrentFullPayments(long order) throws Exception {
        CompletableFuture<ResponseEntity<String>> first,second;
        try(var blocker=dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            try(var lock=blocker.prepareStatement("select id from customer_orders where id=? for no key update")) {
                lock.setLong(1,order);try(var row=lock.executeQuery()){assertTrue(row.next());}
            }
            first=CompletableFuture.supplyAsync(()->request(HttpMethod.POST,"/api/orders/"+order+"/pay",Map.of("paymentMode","FULL"),a.owner()));
            second=CompletableFuture.supplyAsync(()->request(HttpMethod.POST,"/api/orders/"+order+"/pay",Map.of("paymentMode","FULL"),a.owner()));
            try {
                // Force both real requests into the database before letting either commit.
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
                int waiting=0;
                while(System.nanoTime()<deadline) {
                    waiting=db.queryForObject("select count(*) from pg_stat_activity where datname=current_database() and pid<>pg_backend_pid() and wait_event_type='Lock' and (query like '%customer_orders%' or query like '%pg_advisory_xact_lock%')",Integer.class);
                    if(waiting>=2)break;
                    Thread.sleep(25);
                }
                assertTrue(waiting>=2,"Both payment requests must reach a database lock for deterministic fault injection");
            } finally { blocker.rollback(); }
        }
        assertEquals(HttpStatus.OK,first.get(20,TimeUnit.SECONDS).getStatusCode());
        assertEquals(HttpStatus.OK,second.get(20,TimeUnit.SECONDS).getStatusCode());
        var paid=db.queryForObject("select coalesce(sum(amount),0) from customer_order_payments where ordine_id=?",java.math.BigDecimal.class,order);
        assertEquals(0,new java.math.BigDecimal("50.00").compareTo(paid),"Concurrent FULL payment registered more than the order total");
        assertEquals(1,db.queryForObject("select count(*) from customer_order_payments where ordine_id=?",Integer.class,order));
        JsonNode finalOrder=ok(HttpMethod.GET,"/api/orders/"+order,null,a.owner());
        assertEquals("PAGATO",finalOrder.path("status").asText());
        assertEquals(0,deviceCount(a));
    }

    @Test void submitIntentConflictsAndMalformedIdsMustReturnControlledErrors() throws Exception {
        scan(a,1);
        Map<String,Object> original=submitBody(a,1,1);
        long order=ok(HttpMethod.POST,"/api/customer/orders",original,null).path("id").asLong();
        original.put("items",List.of(Map.of("dishId",a.dish(),"quantity",2)));
        assertEquals(HttpStatus.BAD_REQUEST,request(HttpMethod.POST,"/api/customer/orders",original,null).getStatusCode());
        original.put("requestId","invalid-uuid");
        assertEquals(HttpStatus.BAD_REQUEST,request(HttpMethod.POST,"/api/customer/orders",original,null).getStatusCode());
        assertOrder(a,order,1,"10.00");
        JsonNode demo=ok(HttpMethod.POST,"/api/customer/demo/sessions",Map.of(),null);
        String query="?token="+demo.path("token").asText();
        long dish=ok(HttpMethod.GET,"/api/customer/demo/menu"+query,null,null).get(0).path("id").asLong();
        Map<String,Object> demoIntent=new LinkedHashMap<>(Map.of("requestId",UUID.randomUUID().toString(),"items",List.of(Map.of("dishId",dish,"quantity",1))));
        ok(HttpMethod.POST,"/api/customer/demo/orders"+query,demoIntent,null);
        demoIntent.put("items",List.of(Map.of("dishId",dish,"quantity",2)));
        assertEquals(HttpStatus.BAD_REQUEST,request(HttpMethod.POST,"/api/customer/demo/orders"+query,demoIntent,null).getStatusCode());
        demoIntent.put("requestId","invalid-uuid");
        assertEquals(HttpStatus.BAD_REQUEST,request(HttpMethod.POST,"/api/customer/demo/orders"+query,demoIntent,null).getStatusCode());
        assertEquals(1,ok(HttpMethod.GET,"/api/customer/demo/orders/state"+query,null,null).path("currentOrder").path("items").get(0).path("quantita").asInt());
    }

    @Test void invalidOrExcessivePaymentAllocationsMustNotBecomeAFullPayment() throws Exception {
        long expensiveDish=980103L;
        db.update("insert into piatto (id,nome,descrizione,prezzo,disponibile,categoria_id,consigliato,ristoratore_id) values (?,?,?,?,?,?,?,?)",expensiveDish,"Allocation fixture","Fixture",100,true,CATEGORY,false,A);
        JsonNode order=ok(HttpMethod.POST,"/api/orders/manual",Map.of("tableId",1,"items",List.of(Map.of("dishId",a.dish(),"quantity",1),Map.of("dishId",expensiveDish,"quantity",1))),a.owner());
        long cheapItem=0;
        for(JsonNode item:order.path("items")) if(item.path("dishId").asLong()==a.dish()) cheapItem=item.path("id").asLong();
        assertTrue(cheapItem>0);
        List<Object> invalid=List.of(
            List.of(Map.of("orderItemId",cheapItem,"quantity",0)),
            Arrays.asList((Object)null),
            List.of(Map.of("orderItemId",cheapItem,"quantity",1),Map.of("orderItemId",cheapItem,"quantity",1)),
            List.of(Map.of("orderItemId",980777L,"quantity",1)));
        for(Object allocations:invalid) {
            assertEquals(HttpStatus.BAD_REQUEST,request(HttpMethod.POST,"/api/orders/"+order.path("id").asLong()+"/pay",Map.of("paymentMode","PARTIAL","allocations",allocations),a.owner()).getStatusCode());
            assertEquals(0,db.queryForObject("select count(*) from customer_order_payments where ordine_id=?",Integer.class,order.path("id").asLong()));
        }
    }

    private Tenant other(Tenant tenant){return tenant==a?b:a;}
    private String device(Tenant tenant,int client){return "workflow-"+tenant.restaurant()+"-client-"+client;}
    private Map<String,Object> credentials(Tenant tenant,int client) {
        Map<String,Object> body=new LinkedHashMap<>();
        body.put("token",tenant.qr());body.put("restaurantId",String.valueOf(tenant.restaurant()));
        body.put("tableId",1);body.put("deviceId",device(tenant,client));body.put("fingerprint","fp-"+device(tenant,client));
        return body;
    }
    private void scan(Tenant tenant,int client) {
        Map<String,Object> body=credentials(tenant,client);body.remove("token");body.put("qrToken",tenant.qr());
        body.put("tablePublicId",tenant.publicId());body.put("latitude",45.0);body.put("longitude",9.0);body.put("locationUnavailable",false);
        JsonNode response=ok(HttpMethod.POST,"/api/table/access",body,null);
        assertTrue(response.path("allowed").asBoolean(),"Actual table authorization denied: "+response.path("code").asText());
    }
    private JsonNode mutate(Tenant tenant,int client,int delta) {
        Map<String,Object> body=credentials(tenant,client);body.put("dishId",tenant.dish());body.put("delta",delta);
        return ok(HttpMethod.POST,"/api/customer/orders/draft/items",body,null);
    }
    private Map<String,Object> submitBody(Tenant tenant,int client,int quantity) {
        Map<String,Object> body=credentials(tenant,client);body.put("requestId",UUID.randomUUID().toString());
        body.put("items",List.of(Map.of("dishId",tenant.dish(),"quantity",quantity)));return body;
    }
    private String credentialsQuery(Tenant tenant,int client) {
        return "?token="+tenant.qr()+"&restaurantId="+tenant.restaurant()+"&tableId=1&deviceId="+device(tenant,client)+"&fingerprint=fp-"+device(tenant,client);
    }
    private int deviceCount(Tenant tenant){return db.queryForObject("select count(*) from table_device where table_id=?",Integer.class,tenant.table());}
    private void assertOrder(Tenant tenant,long id,int quantity,String total) {
        JsonNode order=ok(HttpMethod.GET,"/api/orders/"+id,null,tenant.owner());
        assertEquals(tenant.restaurant(),order.path("restaurantId").asLong());
        assertEquals(quantity,order.path("items").get(0).path("quantita").asInt());
        assertEquals(0,new java.math.BigDecimal(total).compareTo(order.path("totale").decimalValue()));
        assertEquals(1,db.queryForObject("select count(*) from customer_orders where ristoratore_id=? and status='APERTO'",Integer.class,tenant.restaurant()));
    }
    private ResponseEntity<String> request(HttpMethod method,String path,Object body,String owner) {
        HttpHeaders headers=new HttpHeaders();headers.setContentType(MediaType.APPLICATION_JSON);
        if(owner!=null)headers.setBearerAuth(owner);
        return http.exchange("http://localhost:"+port+path,method,new HttpEntity<>(body,headers),String.class);
    }
    private JsonNode ok(HttpMethod method,String path,Object body,String owner) {
        var response=request(method,path,body,owner);
        assertEquals(HttpStatus.OK,response.getStatusCode(),method+" "+path.split("\\?")[0]+" -> "+response.getStatusCode());
        try{return json.readTree(response.getBody());}catch(IOException ex){throw new AssertionError("Invalid API JSON",ex);}
    }
    private class SseProbe implements AutoCloseable {
        private final HttpClient client=HttpClient.newHttpClient();
        private final InputStream input;
        private final BlockingQueue<String> events=new LinkedBlockingQueue<>();
        SseProbe(Tenant tenant)throws Exception {
            var response=client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/orders/stream?token="+tenant.owner())).GET().build(),HttpResponse.BodyHandlers.ofInputStream());
            assertEquals(200,response.statusCode());input=response.body();
            Thread reader=new Thread(()->{try(var lines=new BufferedReader(new InputStreamReader(input,StandardCharsets.UTF_8))){String line;StringBuilder event=new StringBuilder();while((line=lines.readLine())!=null){if(line.isEmpty()){events.offer(event.toString());event.setLength(0);}else event.append(line).append('\n');}}catch(IOException ignored){}});
            reader.setDaemon(true);reader.start();
            String connected=events.poll(10,TimeUnit.SECONDS);assertNotNull(connected);assertTrue(connected.contains("connected"));
        }
        void awaitOrder(long order,long restaurant)throws Exception {
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            StringBuilder observed=new StringBuilder();
            while(System.nanoTime()<deadline){String event=events.poll(1,TimeUnit.SECONDS);if(event==null)continue;
                observed.append(event);
                String compact=event.replaceAll("\\s+","");
                if(compact.contains("\"orderId\":"+order)&&compact.contains("\"restaurantId\":"+restaurant))return;
            }
            fail("Dashboard SSE did not receive its committed order update. Frames: "+observed);
        }
        public void close()throws IOException{input.close();client.shutdownNow();}
    }
    @AfterEach void cleanup() {
        db.update("delete from experiment_assignment where restaurant_id in (?,?)",A,B);
        db.update("delete from customer_order_payment_allocations where payment_id in (select id from customer_order_payments where ordine_id in (select id from customer_orders where ristoratore_id in (?,?)))",A,B);
        db.update("delete from customer_order_payments where ordine_id in (select id from customer_orders where ristoratore_id in (?,?))",A,B);
        db.update("delete from customer_order_submission where ristoratore_id in (?,?)",A,B);
        db.update("delete from customer_order_items where ordine_id in (select id from customer_orders where ristoratore_id in (?,?))",A,B);
        db.update("delete from customer_orders where ristoratore_id in (?,?)",A,B);
        db.update("delete from table_access_log where table_id in (?,?)",980011L,980012L);
        db.update("delete from table_device where table_id in (?,?)",980011L,980012L);
        db.update("delete from tavoli where id in (?,?)",980011L,980012L);
        db.update("delete from dish_cooccurrence where base_dish_id in (select id from piatto where ristoratore_id in (?,?)) or suggested_dish_id in (select id from piatto where ristoratore_id in (?,?))",A,B,A,B);
        db.update("delete from piatto where ristoratore_id in (?,?)",A,B);
        db.update("delete from service_hours where restaurant_id in (?,?)",A,B);
        db.update("delete from menu_category where id=?",CATEGORY);
        db.update("delete from backoffice_user where restaurant_id in (?,?)",A,B);
        db.update("delete from ristoratore where id in (?,?)",A,B);
    }
}
