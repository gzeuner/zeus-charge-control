package de.zeus.power.repository;

import de.zeus.power.entity.ChargingSchedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ChargingScheduleRepository extends JpaRepository<ChargingSchedule, Long> {
}
