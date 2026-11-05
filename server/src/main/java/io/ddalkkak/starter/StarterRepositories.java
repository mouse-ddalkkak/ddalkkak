package io.ddalkkak.starter;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface ServiceRowRepository extends JpaRepository<ServiceRow, Long> {

	Optional<ServiceRow> findFirstByOrderById();

	Optional<ServiceRow> findFirstByNameAndHostAndPort(String name, String host, int port);

}

interface ApiSnapshotRepository extends JpaRepository<ApiSnapshot, Long> {

	/** 받은 목록 중 최신 (그 id 앞) */
	Optional<ApiSnapshot> findFirstByServiceIdAndApiCountGreaterThanAndIdLessThanOrderByIdDesc(Long serviceId, int apiCount, Long id);

	Optional<ApiSnapshot> findFirstByServiceIdAndApiCountGreaterThanOrderByIdDesc(Long serviceId, int apiCount);

}

interface ApiEndpointRepository extends JpaRepository<ApiEndpointRow, Long> {

	List<ApiEndpointRow> findBySnapshotIdOrderById(Long snapshotId);

}
