package io.ddalkkak.scan;

import org.springframework.data.jpa.repository.JpaRepository;

/** measure_sample 은 B3 만 쓴다 (지킬 규칙 3) */
interface MeasureSampleRepository extends JpaRepository<MeasureSampleRow, Long> {

}
