package de.zeus.power.repository;

import de.zeus.power.entity.ChargingSchedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ChargingScheduleRepository extends JpaRepository<ChargingSchedule, Long> {

    @Query("SELECT COUNT(c) > 0 FROM ChargingSchedule c WHERE c.startTimestamp = :start AND c.endTimestamp = :end AND c.price = :price")
    boolean existsByStartEndAndPrice(@Param("start") long start, @Param("end") long end, @Param("price") double price);

}
