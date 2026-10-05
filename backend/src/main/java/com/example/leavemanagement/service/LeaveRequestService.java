package com.example.leavemanagement.service;

import com.example.leavemanagement.model.Employee;
import com.example.leavemanagement.model.LeaveRequest;
import com.example.leavemanagement.model.LeaveStatus;
import com.example.leavemanagement.model.LeaveType;
import com.example.leavemanagement.repository.EmployeeRepository;
import com.example.leavemanagement.repository.LeaveRequestRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

@Service
public class LeaveRequestService {

    private final EmployeeRepository employeeRepository;
    private final LeaveRequestRepository leaveRequestRepository;

    public LeaveRequestService(EmployeeRepository employeeRepository,
                               LeaveRequestRepository leaveRequestRepository) {
        this.employeeRepository = employeeRepository;
        this.leaveRequestRepository = leaveRequestRepository;
    }

    @Transactional
    public LeaveRequest approve(Long id) {
        Long employeeId = leaveRequestRepository.findEmployeeIdById(id)
                .orElseThrow(() -> new NotFoundException("Leave request not found"));

        Employee employee = employeeRepository.findByIdForUpdate(employeeId)
                .orElseThrow(() -> new NotFoundException("Employee not found"));

        // Reload only after taking the employee lock so status validation observes
        // the latest committed request state after any competing approval.
        LeaveRequest request = leaveRequestRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Leave request not found"));

        if (request.getStatus() != LeaveStatus.PENDING) {
            throw new ConflictException("Only PENDING leave requests can be approved");
        }
        if (!isSingleCalendarYear(request.getStartDate(), request.getEndDate())) {
            throw new InvalidRequestException("Leave requests must start and end in the same calendar year");
        }

        if (request.getType() == LeaveType.VACATION) {
            int used = getApprovedVacationDays(employeeId, request.getStartDate().getYear());
            if (request.getDays() > employee.getAnnualQuota() - used) {
                throw new ConflictException("Not enough vacation balance");
            }
        }

        request.setStatus(LeaveStatus.APPROVED);
        return request;
    }

    @Transactional(readOnly = true)
    public int getApprovedVacationDays(Long employeeId, int year) {
        LocalDate yearStart = LocalDate.of(year, 1, 1);
        LocalDate yearEnd = LocalDate.of(year, 12, 31);
        return leaveRequestRepository
                .findOverlappingRequests(
                        employeeId, LeaveType.VACATION, LeaveStatus.APPROVED, yearEnd, yearStart)
                .stream()
                .mapToInt(request -> daysWithinYear(request, yearStart, yearEnd))
                .sum();
    }

    public static boolean isSingleCalendarYear(LocalDate startDate, LocalDate endDate) {
        return startDate != null
                && endDate != null
                && !startDate.isAfter(endDate)
                && startDate.getYear() == endDate.getYear();
    }

    private int daysWithinYear(LeaveRequest request, LocalDate yearStart, LocalDate yearEnd) {
        LocalDate overlapStart = request.getStartDate().isAfter(yearStart)
                ? request.getStartDate() : yearStart;
        LocalDate overlapEnd = request.getEndDate().isBefore(yearEnd)
                ? request.getEndDate() : yearEnd;
        return Math.toIntExact(ChronoUnit.DAYS.between(overlapStart, overlapEnd) + 1);
    }

    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String message) {
            super(message);
        }
    }

    public static class ConflictException extends RuntimeException {
        public ConflictException(String message) {
            super(message);
        }
    }

    public static class InvalidRequestException extends RuntimeException {
        public InvalidRequestException(String message) {
            super(message);
        }
    }
}
