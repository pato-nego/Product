package pato.nego.product.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import pato.nego.product.Product;

public record ProductRequest(
        @NotBlank String name,
        @NotNull @Positive BigDecimal price,
        @NotBlank String unit) {

    public Product toProduct() {
        return new Product(name, price, unit);
    }
}
