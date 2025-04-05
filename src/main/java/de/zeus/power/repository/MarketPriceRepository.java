package de.zeus.power.repository;

import de.zeus.power.entity.MarketPrice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

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
public interface MarketPriceRepository extends JpaRepository<MarketPrice, Long> {

    @Query("SELECT mp FROM MarketPrice mp WHERE mp.startTimestamp >= :currentTime OR (mp.startTimestamp < :currentTime AND mp.startTimestamp >= :pastTime) ORDER BY mp.marketPrice ASC")
    List<MarketPrice> findValidMarketPrices(@Param("currentTime") Long currentTime, @Param("pastTime") Long pastTime);

    @Query("SELECT mp FROM MarketPrice mp WHERE mp.startTimestamp <= :currentTime AND mp.endTimestamp > :currentTime")
    Optional<MarketPrice> findCurrentMarketPrice(@Param("currentTime") Long currentTime);
}
