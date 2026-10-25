package com.pueblo.catalog.data;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.pueblo.catalog.api.CatalogService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** Offline, operator-invoked import only. There is deliberately no HTTP write endpoint. */
@Service
public class CatalogImporter {
    public record Batch(List<Brand> brands,List<Category> categories,List<Product> products) { }
    public record Brand(String slug,String name,String koreanName,String logoUrl) { }
    public record Category(String slug,String name) { }
    public record Product(String source,String externalId,String sourceUrl,String brandSlug,String categorySlug,
            String name,String description,List<String> images,Map<String,String> policy,Long listPrice,Long salePrice,
            String status,List<String> collections,List<Sku> skus) { }
    public record Sku(String externalId,Map<String,String> options,Long priceOverride,Integer stock,Integer purchaseLimit) { }
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public CatalogImporter(JdbcTemplate jdbc,ObjectMapper mapper) {
        this.jdbc=jdbc;
        this.mapper=mapper.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
    }
    @Transactional
    public int importJson(byte[] bytes) {
        if(bytes.length>2*1024*1024) throw bad();
        Batch batch;
        try { batch=mapper.readValue(bytes,Batch.class); } catch(Exception ex) { throw bad(); }
        if(batch==null) throw bad();
        count(batch.brands(),1000);count(batch.categories(),100);count(batch.products(),1000);
        Set<String> brands=new HashSet<>(),categories=new HashSet<>(),products=new HashSet<>();
        for(var b:batch.brands()) {
            if(b==null) throw bad(); slug(b.slug()); unique(brands,b.slug()); text(b.name(),160); optionalText(b.koreanName(),160); url(b.logoUrl());
        }
        for(var c:batch.categories()) { if(c==null) throw bad(); slug(c.slug());unique(categories,c.slug());text(c.name(),160); }
        for(var p:batch.products()) { validate(p);unique(products,p.source()+":"+p.externalId()); }
        Instant now=Instant.now();
        for(var b:batch.brands()) jdbc.update("""
                INSERT INTO catalog_brand(id,slug,name,korean_name,logo_url) VALUES(?,?,?,?,?)
                ON DUPLICATE KEY UPDATE name=VALUES(name),korean_name=VALUES(korean_name),logo_url=VALUES(logo_url)
                """,id("brand",b.slug()),b.slug(),normalized(b.name()),b.koreanName()==null?"":normalized(b.koreanName()),b.logoUrl());
        for(var c:batch.categories()) jdbc.update("""
                INSERT INTO catalog_category(id,slug,name) VALUES(?,?,?)
                ON DUPLICATE KEY UPDATE name=VALUES(name)
                """,id("category",c.slug()),c.slug(),normalized(c.name()));
        for(var p:batch.products()) {
            String productId=id(p.source(),p.externalId());
            String brandId=id("brand",p.brandSlug()),categoryId=id("category",p.categorySlug());
            if(jdbc.queryForObject("SELECT COUNT(*) FROM catalog_brand WHERE id=?",Integer.class,brandId)!=1
                    || jdbc.queryForObject("SELECT COUNT(*) FROM catalog_category WHERE id=?",Integer.class,categoryId)!=1) throw bad();
            jdbc.update("""
                    INSERT INTO catalog_product(id,brand_id,category_id,name,description,images,policy,list_price,sale_price,status,
                      source,external_id,source_url,imported_at,created_at,updated_at)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    ON DUPLICATE KEY UPDATE brand_id=VALUES(brand_id),category_id=VALUES(category_id),name=VALUES(name),
                      description=VALUES(description),images=VALUES(images),policy=VALUES(policy),list_price=VALUES(list_price),
                      sale_price=VALUES(sale_price),status=VALUES(status),source_url=VALUES(source_url),imported_at=VALUES(imported_at),updated_at=VALUES(updated_at)
                    """,productId,brandId,categoryId,normalized(p.name()),p.description(),json(p.images()),json(p.policy()),
                    p.listPrice(),p.salePrice(),p.status(),p.source(),p.externalId(),p.sourceUrl(),Timestamp.from(now),Timestamp.from(now),Timestamp.from(now));
            jdbc.update("DELETE FROM catalog_product_collection WHERE product_id=?",productId);
            for(var collection:p.collections()) jdbc.update("INSERT INTO catalog_product_collection VALUES(?,?)",productId,collection);
            // Import is metadata refresh, never a stock reset. Missing options are retained but made unavailable.
            jdbc.update("UPDATE catalog_sku SET active=FALSE WHERE product_id=?",productId);
            for(var sku:p.skus()) {
                String skuId=id(productId,sku.externalId());
                String optionKey=json(new TreeMap<>(sku.options()));
                int updated=jdbc.update("UPDATE catalog_sku SET options=?,option_key=?,price_override=?,purchase_limit=?,active=TRUE WHERE id=?",
                        optionKey,optionKey,sku.priceOverride(),sku.purchaseLimit(),skuId);
                if(updated==0) jdbc.update("""
                        INSERT INTO catalog_sku(id,product_id,external_id,options,option_key,price_override,stock,purchase_limit,active)
                        VALUES(?,?,?,?,?,?,?,?,TRUE)
                        """,skuId,productId,sku.externalId(),optionKey,optionKey,sku.priceOverride(),sku.stock(),sku.purchaseLimit());
            }
        }
        return batch.products().size();
    }
    private void validate(Product p) {
        if(p==null || p.source()==null || !p.source().matches("[A-Z][A-Z0-9_]{0,31}")) throw bad();
        identifier(p.externalId());slug(p.brandSlug());slug(p.categorySlug());text(p.name(),255);
        optionalText(p.description(),20000); if(p.description()==null) throw bad();
        url(p.sourceUrl());count(p.images(),20);for(String image:p.images()) { if(image==null) throw bad();url(image); }
        if(p.policy()==null || p.policy().size()>20) throw bad();
        p.policy().forEach((k,v)->{text(k,80);optionalText(v,2000);if(v==null)throw bad();});
        money(p.listPrice());money(p.salePrice());if(p.salePrice()>p.listPrice()) throw bad();
        if(!Set.of("DRAFT","PUBLISHED","DISCONTINUED","DELETED").contains(p.status()==null?"":p.status())) throw bad();
        count(p.collections(),3);Set<String> collections=new HashSet<>();
        for(String c:p.collections()) { if(c==null || !Set.of("men","women","lifestyle").contains(c)) throw bad();unique(collections,c); }
        count(p.skus(),100);Set<String> skuIds=new HashSet<>(),options=new HashSet<>();
        for(var s:p.skus()) {
            if(s==null)throw bad();identifier(s.externalId());unique(skuIds,s.externalId());
            if(s.options()==null || s.options().size()>5) throw bad();
            s.options().forEach((k,v)->{text(k,40);text(v,80);});
            String key=json(new TreeMap<>(s.options()));if(key.length()>512)throw bad();unique(options,key);
            if(s.priceOverride()!=null)money(s.priceOverride());
            if(s.stock()==null || s.stock()<0 || s.purchaseLimit()==null || s.purchaseLimit()<1 || s.purchaseLimit()>9)throw bad();
        }
        if("PUBLISHED".equals(p.status()) && (p.skus().isEmpty() || p.collections().isEmpty() || p.images().isEmpty()))throw bad();
        if("EXTERNAL".equals(p.source()) && (!"DRAFT".equals(p.status()) || !p.skus().isEmpty())) throw bad();
    }
    public static String id(String namespace,String key) { return UUID.nameUUIDFromBytes((namespace+":"+key).getBytes(StandardCharsets.UTF_8)).toString(); }
    private static void unique(Set<String> set,String value) { if(!set.add(value))throw bad(); }
    private static void count(List<?> values,int max) { if(values==null || values.size()>max)throw bad(); }
    private static void text(String value,int max) { if(value==null || value.isBlank() || value.length()>max)throw bad(); }
    private static void optionalText(String value,int max) { if(value!=null && value.length()>max)throw bad(); }
    private static void identifier(String value) { if(value==null || !value.matches("[A-Za-z0-9_.-]{1,100}"))throw bad(); }
    private static void slug(String value) { if(value==null)throw bad();try { CatalogService.slug(value); } catch(RuntimeException ex) { throw bad(); } }
    private static void money(Long value) { if(value==null || value<0 || value>1000000000000L)throw bad(); }
    private static String normalized(String value) { return java.text.Normalizer.normalize(value,java.text.Normalizer.Form.NFKC).strip().replaceAll("\\s+"," "); }
    private static void url(String value) {
        if(value==null)return;
        if(value.equals("/catalog-assets/demo.svg"))return;
        try {
            URI u=URI.create(value);
            if(value.length()>2048 || !"https".equals(u.getScheme()) || u.getHost()==null || u.getRawUserInfo()!=null || u.getFragment()!=null
                    || (u.getPort()!=-1 && u.getPort()!=443))throw bad();
        } catch(RuntimeException ex) { throw bad(); }
    }
    private String json(Object value) { try { return mapper.writeValueAsString(value); } catch(JsonProcessingException ex) { throw bad(); } }
    private static IllegalArgumentException bad() { return new IllegalArgumentException("Invalid catalog import: check schema, limits, URLs and source policy"); }
}
