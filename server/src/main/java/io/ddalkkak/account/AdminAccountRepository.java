package io.ddalkkak.account;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface AdminAccountRepository extends JpaRepository<AdminAccount, Long> {

	Optional<AdminAccount> findByUsername(String username);

	Optional<AdminAccount> findByPasswordHash(String passwordHash);

}
