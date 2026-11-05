package io.ddalkkak.sample.orderapi.product;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductRepository extends JpaRepository<Product, Long> {

	List<Product> findByNameContainingOrderById(String name);

}
