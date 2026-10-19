package com.waitero.back.controller;

import com.waitero.back.BackApplication;
import com.waitero.back.entity.BackofficeRole;
import com.waitero.back.entity.BackofficeUser;
import com.waitero.back.service.JwtService;
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
import java.time.LocalDateTime;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = BackApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class V2RecommendationIsolationPostgresTest {
    private static final long A = 970001L, B = 970002L, CATEGORY = 970010L, DISH_A = 970101L, DISH_B = 970102L;
    @LocalServerPort int port;
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate db;
    @Autowired JwtService jwt;

    @BeforeEach void seed() {
        cleanup();
        for (long id : new long[]{A, B}) db.update("insert into ristoratore (id,email,nome,password,provider,created_at) values (?,?,?,?,?,?)", id, "v2-"+id+"@test.local", "V2 tenant "+id, "unused", "LOCAL", LocalDateTime.now());
        db.update("insert into menu_category (id,business_type,code,label,sort_order,active) values (?,?,?,?,?,?)", CATEGORY,"RISTORANTE","V2_ISOLATION","Isolation",99,true);
        db.update("insert into piatto (id,nome,descrizione,prezzo,disponibile,categoria_id,consigliato,ristoratore_id) values (?,?,?,?,?,?,?,?)",DISH_A,"Tenant A dish","test",10,true,CATEGORY,false,A);
        db.update("insert into piatto (id,nome,descrizione,prezzo,disponibile,categoria_id,consigliato,ristoratore_id) values (?,?,?,?,?,?,?,?)",DISH_B,"Tenant B confidential metrics","test",10,true,CATEGORY,false,B);
    }
    @AfterEach void cleanup() {
        db.update("delete from piatto where id in (?,?)",DISH_A,DISH_B);
        db.update("delete from menu_category where id=?",CATEGORY);
        db.update("delete from ristoratore where id in (?,?)",A,B);
    }
    private List<String> paths(long restaurant,long dish) {
        return List.of("/api/v2/menu-intelligence/ranking?restaurantId="+restaurant,
            "/api/v2/menu-intelligence/related/"+dish+"?restaurantId="+restaurant,
            "/api/v2/upsell/dish/"+dish+"?restaurantId="+restaurant,
            "/api/v2/upsell/cart?restaurantId="+restaurant+"&dishIds="+dish);
    }
    private ResponseEntity<String> get(String path, String token) {
        HttpHeaders headers=new HttpHeaders();
        if(token!=null)headers.setBearerAuth(token);
        return http.exchange("http://localhost:"+port+path,HttpMethod.GET,new HttpEntity<>(headers),String.class);
    }
    private String token(long restaurant) {
        return jwt.generateAccessToken(BackofficeUser.builder().id(restaurant).email("v2@test.local").role(BackofficeRole.RISTORATORE).restaurantId(restaurant).build());
    }
    @Test void anonymousMustNotReadSalesDerivedRecommendations() {
        for(String path:paths(B,DISH_B)) {
            var response=get(path,null);
            assertTrue(response.getStatusCode()==HttpStatus.UNAUTHORIZED || response.getStatusCode()==HttpStatus.FORBIDDEN,path+" returned "+response.getStatusCode()+": "+response.getBody());
        }
    }
    @Test void tenantMustNotReadOtherTenantRecommendationMetrics() {
        String token=token(A);
        for(String path:paths(B,DISH_B)) {
            var response=get(path,token);
            assertEquals(HttpStatus.FORBIDDEN,response.getStatusCode(),path+": "+response.getBody());
            assertFalse(String.valueOf(response.getBody()).contains("Tenant B confidential metrics"));
        }
    }
    @Test void ownTenantRecommendationsRemainAvailable() {
        String token=token(A);
        for(String path:paths(A,DISH_A)) assertEquals(HttpStatus.OK,get(path,token).getStatusCode(),path);
    }
}
