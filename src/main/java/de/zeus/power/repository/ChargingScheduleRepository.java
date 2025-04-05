package de.zeus.power.repository;

import de.zeus.power.entity.ChargingSchedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Copyright 2024 Guido Zeuner - https://tiny-tool.de
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@Repository
public interface ChargingScheduleRepository extends JpaRepository<ChargingSchedule, Long> {

    @Query("SELECT COUNT(c) > 0 FROM ChargingSchedule c WHERE c.startTimestamp = :start AND c.endTimestamp = :end AND c.price = :price")
    boolean existsByStartEndAndPrice(@Param("start") long start, @Param("end") long end, @Param("price") double price);
}
