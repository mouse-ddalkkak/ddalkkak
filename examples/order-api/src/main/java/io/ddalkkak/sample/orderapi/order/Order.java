package io.ddalkkak.sample.orderapi.order;

import io.ddalkkak.sample.orderapi.product.Product;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "orders")
public class Order {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private Long userId;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "product_id")
	private Product product;

	private int quantity;

	private long amount;

	@Column(nullable = false)
	private String status;

	@Column(nullable = false)
	private Instant createdAt;

	protected Order() {
	}

	public Order(Long userId, Product product, int quantity, Instant createdAt) {
		this.userId = userId;
		this.product = product;
		this.quantity = quantity;
		this.amount = product.getPrice() * quantity;
		this.status = "CREATED";
		this.createdAt = createdAt;
	}

	public void markPaid() {
		this.status = "PAID";
	}

	public Long getId() {
		return id;
	}

	public Long getUserId() {
		return userId;
	}

	public Product getProduct() {
		return product;
	}

	public int getQuantity() {
		return quantity;
	}

	public long getAmount() {
		return amount;
	}

	public String getStatus() {
		return status;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

}
