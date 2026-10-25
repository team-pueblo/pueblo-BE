package com.pueblo.catalog;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.pueblo.catalog.data.CatalogImporter;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Testcontainers
@SpringBootTest(properties={"pueblo.messaging.enabled=false","pueblo.docs.enabled=true","springdoc.api-docs.enabled=true","springdoc.swagger-ui.enabled=true", "pueblo.catalog.allowed-origins=http://localhost:5173"})
@AutoConfigureMockMvc
class CatalogIntegrationTest {
    @Container static MySQLContainer<?> mysql=new MySQLContainer<>("mysql:8.4.7");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",()->mysql.getJdbcUrl()+"?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true");
        r.add("spring.datasource.username",mysql::getUsername);r.add("spring.datasource.password",mysql::getPassword);
        r.add("spring.kafka.bootstrap-servers",()->"localhost:1");
    }
    @Autowired MockMvc mvc;
    @Autowired CatalogImporter importer;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    byte[] demo;
    @BeforeEach void seed() throws Exception {
        for(String t:List.of("catalog_sku","catalog_product_collection","catalog_product","catalog_brand","catalog_category")) jdbc.update("DELETE FROM "+t);
        demo=new ClassPathResource("catalog/demo.json").getContentAsByteArray();
        importer.importJson(demo);
    }
    String id(int n) { return CatalogImporter.id("DEMO",String.valueOf(n)); }
    JsonNode getJson(String url) throws Exception {
        return mapper.readTree(mvc.perform(get(url)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    @Test void publicListAndFiltersExcludeDraftButIncludeSoldOut() throws Exception {
        assertThat(getJson("/api/v1/products").at("/data/total").asInt()).isEqualTo(5);
        var sold=getJson("/api/v1/products/"+id(2));
        assertThat(sold.at("/data/saleStatus").asText()).isEqualTo("SOLD_OUT");
        mvc.perform(get("/api/v1/products/"+id(6))).andExpect(status().isNotFound());
        String category=CatalogImporter.id("category","tshirt");
        var filtered=getJson("/api/v1/products?collection=women&brandSlug=pueblo-lab&categoryId="+category+"&onSale=true");
        assertThat(filtered.at("/data/total").asInt()).isEqualTo(1);
        assertThat(filtered.at("/data/items/0/id").asText()).isEqualTo(id(1));
        assertThat(getJson("/api/v1/products?collection=lifestyle").at("/data/total").asInt()).isEqualTo(1);
        jdbc.update("UPDATE catalog_product SET status='DELETED' WHERE id=?",id(1));
        mvc.perform(get("/api/v1/products/"+id(1))).andExpect(status().isNotFound());
        jdbc.update("UPDATE catalog_brand SET active=FALSE WHERE slug='pueblo-lab'");
        mvc.perform(get("/api/v1/products/"+id(3))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/brands/pueblo-lab")).andExpect(status().isNotFound());
    }
    @Test void searchNormalizationAndEmptyQueryHaveDistinctMeaning() throws Exception {
        mvc.perform(get("/api/v1/products").param("q","  PUEBLO   LAB ")).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(3));
        mvc.perform(get("/api/v1/products").param("q","\ud478\uc5d0\ube14\ub85c")).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(3));
        mvc.perform(get("/api/v1/products").param("q","\uff24\uff45\uff4d\uff4f")).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(5));
        assertThat(getJson("/api/v1/products?q=").at("/data/total").asInt()).isZero();
        for(String q:List.of("%","_","no-such-product","' OR 1=1 --"))
            mvc.perform(get("/api/v1/products").param("q",q)).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
    }
    @Test void stablePricePaginationAndBrandWithoutProducts() throws Exception {
        jdbc.update("UPDATE catalog_product SET list_price=100000,sale_price=10000");
        var all=getJson("/api/v1/products?sort=priceAsc&size=100").at("/data/items");
        Set<String> seen=new HashSet<>();
        for(int page=1;page<=5;page++) {
            String value=getJson("/api/v1/products?sort=priceAsc&size=1&page="+page).at("/data/items/0/id").asText();
            assertThat(value).isEqualTo(all.get(page-1).get("id").asText());seen.add(value);
        }
        assertThat(seen).hasSize(5);
        getJson("/api/v1/brands/empty-brand");
        assertThat(getJson("/api/v1/products?brandSlug=empty-brand").at("/data/total").asInt()).isZero();
        mvc.perform(get("/api/v1/brands/unknown")).andExpect(status().isNotFound());
        assertThat(getJson("/api/v1/categories").at("/data").size()).isEqualTo(6);
    }
    @Test void skuOptionsPriceAndLimitsAreReturned() throws Exception {
        var p=getJson("/api/v1/products/"+id(1)).at("/data");
        assertThat(p.get("skus").size()).isEqualTo(2);
        assertThat(p.get("collections").size()).isEqualTo(2);
        assertThat(p.get("currency").asText()).isEqualTo("KRW");
        assertThat(p.at("/policy/notice").asText()).isNotBlank();
        Set<Integer> prices=new HashSet<>();
        for(var sku:p.get("skus")) {
            assertThat(sku.at("/options/size").asText()).isIn("M","L");
            prices.add(sku.get("price").asInt());
        }
        assertThat(prices).containsExactlyInAnyOrder(29000,30000);
    }
    @Test void invalidInputsAreRejectedAndWritesStayClosed() throws Exception {
        for(String query:List.of("page=0","size=101","sort=drop-table","collection=invalid","categoryId=invalid"))
            mvc.perform(get("/api/v1/products?"+query)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
        mvc.perform(get("/api/v1/products").param("q","x".repeat(201))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/products").contentType("application/json").content("{}")).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/private")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/products").header("Origin","https://evil.example")).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/products").header("Origin","http://localhost:5173"))
                .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin","http://localhost:5173"));
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andExpect(jsonPath("$.paths['/api/v1/products']").exists());
    }
    @Test void reimportDoesNotDuplicateProductsOrRestoreStock() {
        String skuId=CatalogImporter.id(id(1),"black-m");
        jdbc.update("UPDATE catalog_sku SET stock=1 WHERE id=?",skuId);
        importer.importJson(demo);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM catalog_product",Integer.class)).isEqualTo(6);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM catalog_sku",Integer.class)).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT stock FROM catalog_sku WHERE id=?",Integer.class,skuId)).isEqualTo(1);
    }
    @Test void invalidBatchRollsBackAndExternalProductsRemainDraft() throws Exception {
        var batch=(ObjectNode)mapper.readTree(demo);
        ((ObjectNode)batch.at("/products/0")).put("name","Should roll back");
        ((ObjectNode)batch.at("/products/1")).put("categorySlug","missing-category");
        byte[] rollbackBatch=mapper.writeValueAsBytes(batch);
        assertThatThrownBy(()->importer.importJson(rollbackBatch)).isInstanceOf(IllegalArgumentException.class);
        assertThat(getJson("/api/v1/products/"+id(1)).at("/data/name").asText()).isEqualTo("Demo Cotton T-Shirt");
        batch=(ObjectNode)mapper.readTree(demo);
        ObjectNode external=(ObjectNode)batch.at("/products/0");
        external.put("source","EXTERNAL");external.put("status","DRAFT");external.set("skus",mapper.createArrayNode());
        importer.importJson(mapper.writeValueAsBytes(batch));
        String externalId=CatalogImporter.id("EXTERNAL","1");
        mvc.perform(get("/api/v1/products/"+externalId)).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM catalog_sku WHERE product_id=?",Integer.class,externalId)).isZero();
        external.put("status","PUBLISHED");
        byte[] invalid=mapper.writeValueAsBytes(batch);
        assertThatThrownBy(()->importer.importJson(invalid)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void duplicateOptionCombinationsAndFractionalPricesAreRejected() throws Exception {
        var batch=(ObjectNode)mapper.readTree(demo);
        ((ObjectNode)batch.at("/products/0/skus/1")).set("options",batch.at("/products/0/skus/0/options"));
        byte[] invalid=mapper.writeValueAsBytes(batch);
        assertThatThrownBy(()->importer.importJson(invalid)).isInstanceOf(IllegalArgumentException.class);
        batch=(ObjectNode)mapper.readTree(demo);
        ((ObjectNode)batch.at("/products/0")).put("salePrice",123.45);
        byte[] fractional=mapper.writeValueAsBytes(batch);
        assertThatThrownBy(()->importer.importJson(fractional)).isInstanceOf(IllegalArgumentException.class);
    }
}
