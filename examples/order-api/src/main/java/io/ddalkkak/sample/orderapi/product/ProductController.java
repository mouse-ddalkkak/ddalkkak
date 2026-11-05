package io.ddalkkak.sample.orderapi.product;

import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/products")
public class ProductController {

	public record ProductView(Long id, String name, long price) {

		static ProductView of(Product product) {
			return new ProductView(product.getId(), product.getName(), product.getPrice());
		}

	}

	private final ProductRepository products;

	ProductController(ProductRepository products) {
		this.products = products;
	}

	@GetMapping
	List<ProductView> list() {
		return products.findAll(Sort.by("id")).stream().map(ProductView::of).toList();
	}

	@GetMapping("/{id}")
	ProductView get(@PathVariable Long id) {
		return products.findById(id)
			.map(ProductView::of)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "상품이 없어요"));
	}

	// 상품 검색은 파라미터로 묶어 안전하다 (주문 검색 ②와 비교용)
	@GetMapping("/search")
	List<ProductView> search(@RequestParam String q) {
		// PostgreSQL 문자열에 못 들어가는 널 문자(0x00) — 그대로 두면 500
		if (q.indexOf('\u0000') >= 0) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "검색어에 쓸 수 없는 글자가 있어요");
		}
		return products.findByNameContainingOrderById(q).stream().map(ProductView::of).toList();
	}

}
