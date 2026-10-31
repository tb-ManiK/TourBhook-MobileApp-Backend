package com.tourbhook.api.repository;

import com.tourbhook.api.entity.Trip;
import com.tourbhook.api.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.LocalDate;

public interface TripRepository extends JpaRepository<Trip, String> {
    List<Trip> findByUserOrderByStartDateDesc(User user);

    List<Trip> findByUserAndEndDateGreaterThanEqualOrderByStartDateAsc(User user, LocalDate today);

    @Query("select distinct t.city from Trip t where t.endDate >= :from and t.startDate <= :to")
    List<String> findDistinctCitiesWithTripsBetween(@Param("from") LocalDate from, @Param("to") LocalDate to);

}
