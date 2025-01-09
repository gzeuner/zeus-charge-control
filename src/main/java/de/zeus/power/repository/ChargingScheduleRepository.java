package de.zeus.power.repository;

import de.zeus.power.entity.ChargingSchedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ChargingScheduleRepository extends JpaRepository<ChargingSchedule, Long> {

    @Query("SELECT COUNT(c) > 0 FROM ChargingSchedule c WHERE c.startTimestamp = :start AND c.endTimestamp = :end AND c.price = :price")
    boolean existsByStartEndAndPrice(@Param("start") long start, @Param("end") long end, @Param("price") double price);

    @Query("SELECT c FROM ChargingSchedule c WHERE c.startTimestamp >= :nightStart AND c.endTimestamp <= :nightEnd")
    List<ChargingSchedule> findNighttimeSchedules(@Param("nightStart") long nightStart, @Param("nightEnd") long nightEnd);

    @Modifying
    @Query("DELETE FROM ChargingSchedule c WHERE c.startTimestamp >= :nightStart AND c.endTimestamp > :resetTimestamp AND c.endTimestamp <= :nightEnd")
    void deleteSchedulesBeyondReset(@Param("nightStart") long nightStart, @Param("resetTimestamp") long resetTimestamp, @Param("nightEnd") long nightEnd);

}
