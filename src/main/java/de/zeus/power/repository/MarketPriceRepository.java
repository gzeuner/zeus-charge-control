package de.zeus.power.repository;

import de.zeus.power.entity.MarketPrice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MarketPriceRepository extends JpaRepository<MarketPrice, Long> {

    @Query("SELECT mp FROM MarketPrice mp WHERE mp.startTimestamp >= :currentTime OR (mp.startTimestamp < :currentTime AND mp.startTimestamp >= :pastTime) ORDER BY mp.marketPrice ASC")
    List<MarketPrice> findValidMarketPrices(@Param("currentTime") Long currentTime, @Param("pastTime") Long pastTime);
}
