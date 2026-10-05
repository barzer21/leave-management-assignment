package com.example.leavemanagement.repository;

import com.example.leavemanagement.model.LeaveRequest;
import com.example.leavemanagement.model.LeaveStatus;
import com.example.leavemanagement.model.LeaveType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface LeaveRequestRepository extends JpaRepository<LeaveRequest, Long> {

    @Query("select request.employeeId from LeaveRequest request where request.id = :id")
    Optional<Long> findEmployeeIdById(@Param("id") Long id);

    @Query("""
            select request from LeaveRequest request
            where request.employeeId = :employeeId
              and request.type = :type
              and request.status = :status
              and request.startDate <= :yearEnd
              and request.endDate >= :yearStart
            """)
    List<LeaveRequest> findOverlappingRequests(
            @Param("employeeId") Long employeeId,
            @Param("type") LeaveType type,
            @Param("status") LeaveStatus status,
            @Param("yearEnd") LocalDate yearEnd,
            @Param("yearStart") LocalDate yearStart);
}
