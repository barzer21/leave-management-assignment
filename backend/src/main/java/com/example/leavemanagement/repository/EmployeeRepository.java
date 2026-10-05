package com.example.leavemanagement.repository;

import com.example.leavemanagement.model.Employee;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface EmployeeRepository extends JpaRepository<Employee, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select employee from Employee employee where employee.id = :id")
    Optional<Employee> findByIdForUpdate(@Param("id") Long id);
}
