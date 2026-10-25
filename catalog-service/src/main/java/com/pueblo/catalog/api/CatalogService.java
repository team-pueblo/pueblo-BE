package com.pueblo.catalog.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pueblo.common.exception.BusinessException;
import com.pueblo.common.exception.CommonErrorCode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.Normalizer;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.pueblo.catalog.api.CatalogModels.*;

@Service
@Transactional(readOnly=true)
public class CatalogService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private static final String PUBLIC = "p.status='PUBLISHED' AND b.active=TRUE";
    private static final String FROM = " FROM catalog_product p JOIN catalog_brand b ON b.id=p.brand_id ";
    private static final String FIELDS = "p.*, b.slug brand_slug,b.name brand_name,b.korean_name,b.logo_url,b.display_order," +
            " EXISTS(SELECT 1 FROM catalog_sku s WHERE s.product_id=p.id AND s.active=TRUE AND s.stock>0) available";
    public CatalogService(JdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc=jdbc; this.mapper=mapper; }

    public Page<Product> products(String collection, String categoryId, String brandSlug, Boolean onSale,
            String q, String sort, int page, int size) {
        paging(page,size);
        List<Object> args=new ArrayList<>();
        StringBuilder where=new StringBuilder(" WHERE "+PUBLIC);
        if(collection!=null) {
            if(!Set.of("men","women","lifestyle").contains(collection)) throw invalid();
            where.append(" AND EXISTS(SELECT 1 FROM catalog_product_collection pc WHERE pc.product_id=p.id AND pc.collection=?)");
            args.add(collection);
        }
        if(categoryId!=null) { uuid(categoryId); where.append(" AND p.category_id=?"); args.add(categoryId); }
        if(brandSlug!=null) { slug(brandSlug); where.append(" AND b.slug=?"); args.add(brandSlug); }
        if(onSale!=null) where.append(onSale ? " AND p.sale_price<p.list_price" : " AND p.sale_price=p.list_price");
        if(q!=null) {
            String search=normalize(q);
            // Explicit empty search means no search results; omitted q is a normal listing.
            if(search.isEmpty()) where.append(" AND FALSE");
            else {
                where.append(" AND (p.name LIKE ? ESCAPE '!' OR b.name LIKE ? ESCAPE '!' OR b.korean_name LIKE ? ESCAPE '!')");
                for(int i=0;i<3;i++) args.add(like(search));
            }
        }
        String order=switch(sort) {
            case "newest" -> "p.created_at DESC,p.id DESC";
            case "priceAsc" -> "p.sale_price ASC,p.id ASC";
            case "priceDesc" -> "p.sale_price DESC,p.id DESC";
            default -> throw invalid();
        };
        long count=jdbc.queryForObject("SELECT COUNT(*)"+FROM+where,Long.class,args.toArray());
        args.add(size); args.add(((long)page-1)*size);
        var items=jdbc.query("SELECT "+FIELDS+FROM+where+" ORDER BY "+order+" LIMIT ? OFFSET ?",(r,n)->product(r),args.toArray());
        return new Page<>(items,page,size,count);
    }

    public Detail detail(String id) {
        uuid(id);
        var rows=jdbc.query("SELECT "+FIELDS+FROM+" WHERE "+PUBLIC+" AND p.id=?",(r,n)-> {
            var p=product(r);
            return new Detail(p.id(),p.brand(),p.categoryId(),p.name(),r.getString("description"),
                    json(r.getString("images"),new TypeReference<List<String>>(){}),
                    json(r.getString("policy"),new TypeReference<Map<String,String>>(){}),List.of(),
                    p.listPrice(),p.salePrice(),p.currency(),p.isNew(),p.onSale(),p.saleStatus(),List.of(),
                    r.getTimestamp("created_at").toInstant());
        },id);
        if(rows.isEmpty()) throw missing();
        Detail p=rows.getFirst();
        var skus=jdbc.query("SELECT * FROM catalog_sku WHERE product_id=? ORDER BY id",(r,n)->new Sku(
                r.getString("id"),json(r.getString("options"),new TypeReference<Map<String,String>>(){}),
                r.getObject("price_override")==null?p.salePrice():r.getLong("price_override"),
                r.getBoolean("active")?r.getInt("stock"):0,r.getInt("purchase_limit"),
                !r.getBoolean("active")?"UNAVAILABLE":r.getInt("stock")>0?"ON_SALE":"SOLD_OUT"),id);
        var collections=jdbc.queryForList("SELECT collection FROM catalog_product_collection WHERE product_id=? ORDER BY collection",String.class,id);
        return new Detail(p.id(),p.brand(),p.categoryId(),p.name(),p.description(),p.images(),p.policy(),collections,
                p.listPrice(),p.salePrice(),p.currency(),p.isNew(),p.onSale(),p.saleStatus(),skus,p.createdAt());
    }
    public Page<Brand> brands(String q,int page,int size) {
        paging(page,size);
        String term=like(q==null?"":normalize(q));
        String where=" WHERE active=TRUE AND (name LIKE ? ESCAPE '!' OR korean_name LIKE ? ESCAPE '!')";
        long total=jdbc.queryForObject("SELECT COUNT(*) FROM catalog_brand"+where,Long.class,term,term);
        var rows=jdbc.query("SELECT id,slug brand_slug,name brand_name,korean_name,logo_url,display_order FROM catalog_brand"+where+
                " ORDER BY display_order,id LIMIT ? OFFSET ?",(r,n)->brand(r,r.getString("id")),term,term,size,((long)page-1)*size);
        return new Page<>(rows,page,size,total);
    }
    public Brand brand(String slug) {
        slug(slug);
        return jdbc.query("SELECT id,slug brand_slug,name brand_name,korean_name,logo_url,display_order FROM catalog_brand WHERE active=TRUE AND slug=?",
                (r,n)->brand(r,r.getString("id")),slug).stream().findFirst().orElseThrow(CatalogService::missing);
    }
    public List<Category> categories() {
        return jdbc.query("SELECT * FROM catalog_category ORDER BY display_order,id",(r,n)->new Category(
                r.getString("id"),r.getString("slug"),r.getString("name"),r.getInt("display_order")));
    }
    private Product product(ResultSet r) throws SQLException {
        List<String> images=json(r.getString("images"),new TypeReference<>(){});
        var newUntil=r.getTimestamp("new_until");
        return new Product(r.getString("id"),brand(r,r.getString("brand_id")),r.getString("category_id"),r.getString("name"),
                images.isEmpty()?null:images.getFirst(),r.getLong("list_price"),r.getLong("sale_price"),r.getString("currency"),
                newUntil!=null && newUntil.toInstant().isAfter(Instant.now()),r.getLong("sale_price")<r.getLong("list_price"),
                r.getBoolean("available")?"ON_SALE":"SOLD_OUT");
    }
    private Brand brand(ResultSet r,String id) throws SQLException {
        return new Brand(id,r.getString("brand_slug"),r.getString("brand_name"),r.getString("korean_name"),r.getString("logo_url"),r.getInt("display_order"));
    }
    private <T> T json(String value,TypeReference<T> type) {
        try { return mapper.readValue(value,type); } catch(Exception ex) { throw new IllegalStateException("Invalid stored catalog JSON"); }
    }
    public static String normalize(String value) {
        if(value.length()>200) throw invalid();
        return Normalizer.normalize(value,Normalizer.Form.NFKC).strip().replaceAll("\\s+"," ");
    }
    private static String like(String q) { return "%"+q.replace("!","!!").replace("%","!%").replace("_","!_")+"%"; }
    private static void paging(int page,int size) { if(page<1 || page>100000 || size<1 || size>100) throw invalid(); }
    private static void uuid(String id) { try { if(!UUID.fromString(id).toString().equals(id)) throw invalid(); } catch(IllegalArgumentException ex) { throw invalid(); } }
    public static void slug(String slug) { if(!slug.matches("[a-z0-9][a-z0-9-]{0,99}")) throw invalid(); }
    private static BusinessException invalid() { return new BusinessException(CommonErrorCode.INVALID_INPUT); }
    private static BusinessException missing() { return new BusinessException(CommonErrorCode.NOT_FOUND); }
}
