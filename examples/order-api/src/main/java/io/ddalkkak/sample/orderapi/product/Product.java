package io.ddalkkak.sample.orderapi.product;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

@Entity
public class Product {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private String name;

	private long price;

	protected Product() {
	}

	public Product(String name, long price) {
		this.name = name;
		this.price = price;
	}

	public Long getId() {
		return id;
	}

	// 지연 로딩 프록시는 getter를 불러야 DB에서 읽는다 (심은 문제 ①이 생기는 곳)
	public String getName() {
		return name;
	}

	public long getPrice() {
		return price;
	}

}
