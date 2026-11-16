package pato.nego.product.dto;

import java.math.BigDecimal;
import java.util.UUID;

import pato.nego.product.Product;

public record ProductResponse(
        UUID id,
        String name,
        BigDecimal price,
        String unit) {

    public static ProductResponse from(Product product) {
        return new ProductResponse(
                product.getId(),
                product.getName(),
                product.getPrice(),
                product.getUnit());
    }
}
