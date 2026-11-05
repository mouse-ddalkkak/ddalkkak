package io.ddalkkak.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.LocalDateTime;

@Entity
class AdminAccount {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	Long id;

	@Column(nullable = false, unique = true, length = 50)
	String username;

	@Column(nullable = false, length = 100)
	String passwordHash;

	@Column(nullable = false)
	boolean setupAcked;

	@Column(nullable = false)
	boolean firstLoginDone;

	@Column(nullable = false)
	LocalDateTime createdAt = LocalDateTime.now();

	protected AdminAccount() {
	}

	AdminAccount(String username) {
		this.username = username;
	}

}
