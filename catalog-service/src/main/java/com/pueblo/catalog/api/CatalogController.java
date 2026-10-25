package com.pueblo.catalog.api;

import com.pueblo.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import static com.pueblo.catalog.api.CatalogModels.*;

@RestController
@RequestMapping("/api/v1")
@Tag(name="상품 카탈로그")
public class CatalogController {
    private final CatalogService catalog;
    public CatalogController(CatalogService catalog) { this.catalog=catalog; }
    @GetMapping("/products") @Operation(summary="상품 목록·검색·필터", description="q 생략은 전체 목록, 빈 q는 빈 결과. sort: newest, priceAsc, priceDesc. 금액은 KRW 정수.")
    ApiResponse<Page<Product>> products(@RequestParam(required=false) String collection,@RequestParam(required=false) String categoryId,
            @RequestParam(required=false) String brandSlug,@RequestParam(required=false) Boolean onSale,@RequestParam(required=false) String q,
            @RequestParam(defaultValue="newest") String sort,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size,HttpServletRequest request) {
        return ApiResponse.of(catalog.products(collection,categoryId,brandSlug,onSale,q,sort,page,size),request);
    }
    @GetMapping("/products/{id}") @Operation(summary="상품 상세·옵션·현재 재고",description="현재 재고는 참고값이며 구매 시 서버에서 다시 검증합니다.")
    ApiResponse<Detail> product(@PathVariable String id,HttpServletRequest request) { return ApiResponse.of(catalog.detail(id),request); }
    @GetMapping("/brands") @Operation(summary="브랜드 목록·검색")
    ApiResponse<Page<Brand>> brands(@RequestParam(required=false) String q,@RequestParam(defaultValue="1") int page,
            @RequestParam(defaultValue="20") int size,HttpServletRequest request) { return ApiResponse.of(catalog.brands(q,page,size),request); }
    @GetMapping("/brands/{slug}") @Operation(summary="브랜드 상세")
    ApiResponse<Brand> brand(@PathVariable String slug,HttpServletRequest request) { return ApiResponse.of(catalog.brand(slug),request); }
    @GetMapping("/categories") @Operation(summary="상품 종류 목록")
    ApiResponse<List<Category>> categories(HttpServletRequest request) { return ApiResponse.of(catalog.categories(),request); }
}
