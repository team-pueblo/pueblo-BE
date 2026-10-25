package com.pueblo.catalog.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class CatalogModels {
    private CatalogModels() { }
    public record Page<T>(List<T> items, int page, int size, long total) { }
    public record Brand(String id, String slug, String name, String koreanName, String logoUrl, int displayOrder) { }
    public record Category(String id, String slug, String name, int displayOrder) { }
    public record Product(String id, Brand brand, String categoryId, String name, String imageUrl,
            long listPrice, long salePrice, String currency, boolean isNew, boolean onSale, String saleStatus) { }
    public record Sku(String id, Map<String,String> options, long price, int availableQuantity,
            int purchaseLimit, String saleStatus) { }
    public record Detail(String id, Brand brand, String categoryId, String name, String description,
            List<String> images, Map<String,String> policy, List<String> collections,
            long listPrice, long salePrice, String currency, boolean isNew, boolean onSale,
            String saleStatus, List<Sku> skus, Instant createdAt) { }
}
