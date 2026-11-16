package pato.nego.product;

import java.util.List;
import java.util.UUID;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import pato.nego.product.dto.ProductRequest;
import pato.nego.product.dto.ProductResponse;
import pato.nego.product.exceptions.ProductNotFoundException;

@Service
@RequiredArgsConstructor
public class ProductService {

    private static final String PRODUCT_CACHE = "products";

    private final ProductRepository productRepository;

    public ProductResponse create(ProductRequest request) {
        Product savedProduct = productRepository.save(request.toProduct());
        return ProductResponse.from(savedProduct);
    }

    public List<ProductResponse> findAll() {
        return productRepository.findAll().stream()
                .map(ProductResponse::from)
                .toList();
    }

    @Cacheable(value = PRODUCT_CACHE, key = "#id")
    public ProductResponse findById(UUID id) {
        return productRepository.findById(id)
                .map(ProductResponse::from)
                .orElseThrow(() -> new ProductNotFoundException(id));
    }

    @CacheEvict(value = PRODUCT_CACHE, key = "#id")
    public void delete(UUID id) {
        if (!productRepository.existsById(id)) {
            throw new ProductNotFoundException(id);
        }
        productRepository.deleteById(id);
    }
}
