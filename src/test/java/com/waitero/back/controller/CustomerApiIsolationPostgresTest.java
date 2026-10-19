package com.waitero.back.controller;

import com.waitero.back.BackApplication;
import com.waitero.back.dto.SecureTableAccessRequest;
import com.waitero.back.service.JwtService;
import com.waitero.back.service.ServiceHourScheduleService;
import com.waitero.back.service.TableAccessService;
import com.waitero.back.service.OrdineService;
import com.waitero.back.security.BackofficePrincipal;
import com.waitero.back.entity.BackofficeRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = BackApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CustomerApiIsolationPostgresTest {
    private static final long RESTAURANT_A=960001L, RESTAURANT_B=960002L, TABLE_A=960011L, TABLE_B=960012L, ORDER_A=960021L;
    @Autowired MockMvc mockMvc; @Autowired JdbcTemplate jdbcTemplate; @Autowired JwtService jwtService;
    @Autowired TableAccessService tableAccessService; @Autowired OrdineService ordineService; @MockitoBean ServiceHourScheduleService serviceHourScheduleService;
    private String tokenA, tokenB;

    @BeforeEach void setUp() {
        cleanup(); when(serviceHourScheduleService.isOpenAt(any(),any())).thenReturn(true);
        seedRestaurant(RESTAURANT_A,"api-a@test.local"); seedRestaurant(RESTAURANT_B,"api-b@test.local");
        tokenA=jwtService.generateQrToken(RESTAURANT_A,1); tokenB=jwtService.generateQrToken(RESTAURANT_B,2);
        seedTable(TABLE_A,RESTAURANT_A,1,"api-public-a",tokenA); seedTable(TABLE_B,RESTAURANT_B,2,"api-public-b",tokenB);
        registerA();
    }
    @AfterEach void tearDown(){ cleanup(); }

    @Test void mixedTenantCustomerCredentialsAreForbiddenAcrossReadAndWriteApisWithoutMutation() throws Exception {
        assertForbiddenReads(tokenA,RESTAURANT_B,2,"device-a"); assertForbiddenReads(tokenB,RESTAURANT_A,1,"device-a");
        String mismatched=credentialsJson(tokenA,RESTAURANT_B,2,"device-a");
        mockMvc.perform(post("/api/customer/orders/draft/items").contentType(MediaType.APPLICATION_JSON)
            .content(mismatched.substring(0,mismatched.length()-1)+",\"dishId\":1,\"delta\":1}")).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/customer/orders/call-waiter").contentType(MediaType.APPLICATION_JSON).content(mismatched))
            .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/customer/orders").contentType(MediaType.APPLICATION_JSON)
            .content(mismatched.substring(0,mismatched.length()-1)+",\"requestId\":\"11111111-1111-1111-1111-111111111111\",\"items\":[]}"))
            .andExpect(status().isForbidden());
        assertEquals(0,jdbcTemplate.queryForObject("select count(*) from customer_orders where ristoratore_id in (?, ?)",Integer.class,RESTAURANT_A,RESTAURANT_B));
        assertEquals(0,jdbcTemplate.queryForObject("select count(*) from tavoli where id in (?, ?) and waiter_call_pending = true",Integer.class,TABLE_A,TABLE_B));
        assertEquals(0,jdbcTemplate.queryForObject("select count(*) from table_device where table_id = ?",Integer.class,TABLE_B));
    }
    @Test void repeatedPaymentFinalizationClearsAnyRemainingRegisteredDevices() {
        LocalDateTime now=LocalDateTime.now();
        jdbcTemplate.update("insert into customer_orders (id,created_at,paid_at,status,table_id,updated_at,ristoratore_id,totale,item_count,variant) values (?,?,?,?,?,?,?,?,?,?)",ORDER_A,now,now,"PAGATO",1,now,RESTAURANT_A,0,0,"A");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new BackofficePrincipal(1L,BackofficeRole.RISTORATORE,RESTAURANT_A,null),null));
        try { ordineService.payOrder(ORDER_A,null); } finally { SecurityContextHolder.clearContext(); }
        assertEquals(0,jdbcTemplate.queryForObject("select count(*) from table_device where table_id = ?",Integer.class,TABLE_A));
    }

    private void assertForbiddenReads(String token,long restaurantId,int tableId,String deviceId)throws Exception{
        for(String endpoint:new String[]{"/current","/draft","/state","/stream"})
            mockMvc.perform(get("/api/customer/orders"+endpoint).param("token",token).param("restaurantId",String.valueOf(restaurantId))
                .param("tableId",String.valueOf(tableId)).param("deviceId",deviceId)).andExpect(status().isForbidden());
    }
    private String credentialsJson(String token,long restaurantId,int tableId,String deviceId){
        return "{\"token\":\""+token+"\",\"restaurantId\":\""+restaurantId+"\",\"tableId\":"+tableId+",\"deviceId\":\""+deviceId+"\"}";
    }
    private void registerA(){
        SecureTableAccessRequest r=new SecureTableAccessRequest(); r.setTablePublicId("api-public-a"); r.setRestaurantId(String.valueOf(RESTAURANT_A));
        r.setTableId(1); r.setQrToken(tokenA); r.setDeviceId("device-a"); r.setFingerprint("fp-a"); r.setLocationUnavailable(true);
        if(!tableAccessService.validateAndRegister(r).isAllowed()) throw new IllegalStateException("Fixture device A was not registered");
    }
    private void seedRestaurant(long id,String email){
        jdbcTemplate.update("insert into ristoratore (id,email,nome,password,provider,created_at) values (?,?,?,?,?,?)",id,email,email,"pwd","LOCAL",LocalDateTime.now());
    }
    private void seedTable(long id,long restaurantId,int number,String publicId,String token){
        LocalDateTime now=LocalDateTime.now(); jdbcTemplate.update("insert into tavoli (id,ristoratore_id,table_public_id,numero,nome,coperti,attivo,qr_token,created_at,updated_at) values (?,?,?,?,?,?,?,?,?,?)",id,restaurantId,publicId,number,"Table "+number,4,true,token,now,now);
    }
    private void cleanup(){
        jdbcTemplate.update("delete from customer_order_submission where ristoratore_id in (?,?)",RESTAURANT_A,RESTAURANT_B);
        jdbcTemplate.update("delete from customer_order_items where ordine_id in (select id from customer_orders where ristoratore_id in (?,?))",RESTAURANT_A,RESTAURANT_B);
        jdbcTemplate.update("delete from customer_orders where ristoratore_id in (?,?)",RESTAURANT_A,RESTAURANT_B);
        jdbcTemplate.update("delete from table_access_log where table_id in (?,?)",TABLE_A,TABLE_B);
        jdbcTemplate.update("delete from table_device where table_id in (?,?)",TABLE_A,TABLE_B);
        jdbcTemplate.update("delete from tavoli where id in (?,?)",TABLE_A,TABLE_B);
        jdbcTemplate.update("delete from ristoratore where id in (?,?)",RESTAURANT_A,RESTAURANT_B);
    }
}
