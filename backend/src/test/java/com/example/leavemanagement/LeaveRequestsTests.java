package com.example.leavemanagement;
import com.example.leavemanagement.model.LeaveRequest;
import com.example.leavemanagement.model.LeaveStatus;
import com.example.leavemanagement.controller.LeaveRequestsController;
import com.example.leavemanagement.dto.CreateLeaveRequestDto;
import com.example.leavemanagement.model.Employee;
import com.example.leavemanagement.model.LeaveType;
import com.example.leavemanagement.repository.EmployeeRepository;
import com.example.leavemanagement.repository.LeaveRequestRepository;
import com.example.leavemanagement.service.LeaveRequestService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

// Runs against a real, throwaway PostgreSQL started by Testcontainers.
// (Docker must be available on the machine running the tests.)
@SpringBootTest
@Testcontainers
class LeaveRequestsTests {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private LeaveRequestsController controller;

    @Autowired
    private EmployeeRepository employees;

    @Autowired
    private LeaveRequestRepository leaveRequests;

    @Autowired
    private LeaveRequestService leaveRequestService;

    @Test
    void create_WithinQuota_Succeeds() {
        // Arrange
        Employee emp = new Employee();
        emp.setName("Test Emp");
        emp.setAnnualQuota(20);
        employees.save(emp);

        long before = leaveRequests.count();

        CreateLeaveRequestDto dto = new CreateLeaveRequestDto();
        dto.setEmployeeId(emp.getId());
        dto.setType(LeaveType.VACATION);
        dto.setStartDate(LocalDate.of(2026, 3, 1));
        dto.setEndDate(LocalDate.of(2026, 3, 3)); // 3 days, well within the quota

        // Act
        ResponseEntity<?> result = controller.create(dto);

        // Assert
        assertTrue(result.getStatusCode().is2xxSuccessful());
        assertEquals(before + 1, leaveRequests.count());
    }

    // TODO (candidate): add a test that proves the balance bug is fixed —
    // an employee who has already used most of the quota should NOT be able
    // to create a request that pushes them over the annual quota.
    @Test
    void create_ExceedsRemainingQuota_IsRejected() {
        // Arrange: employee has an annual quota of 20 days.
        Employee employee = new Employee();
        employee.setName("Balance Test Employee");
        employee.setAnnualQuota(20);
        employees.save(employee);

        // The employee has already used 18 approved vacation days.
        LeaveRequest approvedRequest = new LeaveRequest();
        approvedRequest.setEmployeeId(employee.getId());
        approvedRequest.setType(LeaveType.VACATION);
        approvedRequest.setStatus(LeaveStatus.APPROVED);
        approvedRequest.setStartDate(LocalDate.of(2026, 1, 1));
        approvedRequest.setEndDate(LocalDate.of(2026, 1, 18));
        approvedRequest.setDays(18);
        leaveRequests.save(approvedRequest);

        long before = leaveRequests.count();

        // Request 3 more days, while only 2 remain.
        CreateLeaveRequestDto dto = new CreateLeaveRequestDto();
        dto.setEmployeeId(employee.getId());
        dto.setType(LeaveType.VACATION);
        dto.setStartDate(LocalDate.of(2026, 3, 1));
        dto.setEndDate(LocalDate.of(2026, 3, 3));

        // Act
        ResponseEntity<?> result = controller.create(dto);

        // Assert: reject the request and do not save it.
        assertEquals(400, result.getStatusCode().value());
        assertEquals("Not enough vacation balance", result.getBody());
        assertEquals(before, leaveRequests.count());
    }

    @Test
    void create_SpansCalendarYears_IsRejected() {
        Employee employee = createEmployee(20);
        CreateLeaveRequestDto dto = new CreateLeaveRequestDto();
        dto.setEmployeeId(employee.getId());
        dto.setType(LeaveType.VACATION);
        dto.setStartDate(LocalDate.of(2026, 12, 31));
        dto.setEndDate(LocalDate.of(2027, 1, 1));

        ResponseEntity<?> result = controller.create(dto);

        assertEquals(400, result.getStatusCode().value());
        assertEquals("Leave requests must have valid dates within a single calendar year", result.getBody());
    }

    @Test
    void create_PreviousYearVacationDoesNotReduceCurrentYearAllowance() {
        Employee employee = createEmployee(1);
        createRequest(employee, LeaveType.VACATION, LeaveStatus.APPROVED,
                LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 20));
        CreateLeaveRequestDto dto = new CreateLeaveRequestDto();
        dto.setEmployeeId(employee.getId());
        dto.setType(LeaveType.VACATION);
        dto.setStartDate(LocalDate.of(2026, 2, 1));
        dto.setEndDate(LocalDate.of(2026, 2, 1));

        ResponseEntity<?> result = controller.create(dto);

        assertEquals(200, result.getStatusCode().value());
    }

    @Test
    void approve_PendingRequest_SucceedsAndPersistsApprovedStatus() {
        Employee employee = createEmployee(5);
        LeaveRequest request = createRequest(employee, LeaveType.VACATION, LeaveStatus.PENDING,
                LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 3));

        ResponseEntity<?> result = controller.approve(request.getId());

        assertEquals(200, result.getStatusCode().value());
        assertEquals(LeaveStatus.APPROVED, leaveRequests.findById(request.getId()).orElseThrow().getStatus());
        assertEquals(LeaveStatus.APPROVED, ((LeaveRequest) result.getBody()).getStatus());
    }

    @Test
    void approve_MissingRequest_ReturnsNotFound() {
        ResponseEntity<?> result = controller.approve(Long.MAX_VALUE);

        assertEquals(404, result.getStatusCode().value());
        assertEquals("Leave request not found", result.getBody());
    }

    @Test
    void approve_AlreadyDecidedRequest_ReturnsConflict() {
        Employee employee = createEmployee(10);
        for (LeaveStatus status : new LeaveStatus[]{LeaveStatus.APPROVED, LeaveStatus.REJECTED}) {
            LeaveRequest request = createRequest(employee, LeaveType.SICK, status,
                    LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 1));

            ResponseEntity<?> result = controller.approve(request.getId());

            assertEquals(409, result.getStatusCode().value());
            assertEquals("Only PENDING leave requests can be approved", result.getBody());
            assertEquals(status, leaveRequests.findById(request.getId()).orElseThrow().getStatus());
        }
    }

    @Test
    void approve_InsufficientAllowance_ReturnsConflictAndLeavesRequestPending() {
        Employee employee = createEmployee(10);
        createRequest(employee, LeaveType.VACATION, LeaveStatus.APPROVED,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 9));
        LeaveRequest pending = createRequest(employee, LeaveType.VACATION, LeaveStatus.PENDING,
                LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 2));

        ResponseEntity<?> result = controller.approve(pending.getId());

        assertEquals(409, result.getStatusCode().value());
        assertEquals("Not enough vacation balance", result.getBody());
        assertEquals(LeaveStatus.PENDING, leaveRequests.findById(pending.getId()).orElseThrow().getStatus());
    }

    @Test
    void approve_SpansCalendarYears_ReturnsBadRequestAndLeavesRequestPending() {
        Employee employee = createEmployee(10);
        LeaveRequest request = createRequest(employee, LeaveType.VACATION, LeaveStatus.PENDING,
                LocalDate.of(2026, 12, 31), LocalDate.of(2027, 1, 1));

        ResponseEntity<?> result = controller.approve(request.getId());

        assertEquals(400, result.getStatusCode().value());
        assertEquals(LeaveStatus.PENDING, leaveRequests.findById(request.getId()).orElseThrow().getStatus());
    }

    @Test
    void approve_ExactlyRemainingAllowance_Succeeds() {
        Employee employee = createEmployee(10);
        createRequest(employee, LeaveType.VACATION, LeaveStatus.APPROVED,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 8));
        LeaveRequest pending = createRequest(employee, LeaveType.VACATION, LeaveStatus.PENDING,
                LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 2));

        ResponseEntity<?> result = controller.approve(pending.getId());

        assertEquals(200, result.getStatusCode().value());
        assertEquals(LeaveStatus.APPROVED, leaveRequests.findById(pending.getId()).orElseThrow().getStatus());
    }

    @Test
    void approve_PreviousYearVacationDoesNotReduceCurrentYearAllowance() {
        Employee employee = createEmployee(1);
        createRequest(employee, LeaveType.VACATION, LeaveStatus.APPROVED,
                LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 20));
        LeaveRequest currentYear = createRequest(employee, LeaveType.VACATION, LeaveStatus.PENDING,
                LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 1));

        ResponseEntity<?> result = controller.approve(currentYear.getId());

        assertEquals(200, result.getStatusCode().value());
        assertEquals(LeaveStatus.APPROVED, leaveRequests.findById(currentYear.getId()).orElseThrow().getStatus());
    }

    @Test
    void approve_NonVacationRequestDoesNotConsumeVacationAllowance() {
        Employee employee = createEmployee(1);
        LeaveRequest sick = createRequest(employee, LeaveType.SICK, LeaveStatus.PENDING,
                LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 1));
        LeaveRequest vacation = createRequest(employee, LeaveType.VACATION, LeaveStatus.PENDING,
                LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 1));

        assertEquals(200, controller.approve(sick.getId()).getStatusCode().value());
        ResponseEntity<?> vacationResult = controller.approve(vacation.getId());

        assertEquals(200, vacationResult.getStatusCode().value());
        assertEquals(LeaveStatus.APPROVED, leaveRequests.findById(vacation.getId()).orElseThrow().getStatus());
    }

    @Test
    void approve_ConcurrentRequestsForSameEmployeeCannotExceedAllowance() throws Exception {
        Employee employee = createEmployee(3);
        LeaveRequest firstRequest = createRequest(employee, LeaveType.VACATION, LeaveStatus.PENDING,
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 2));
        LeaveRequest secondRequest = createRequest(employee, LeaveType.VACATION, LeaveStatus.PENDING,
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 2));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Boolean> first = executor.submit(() -> approveAfterBarrier(firstRequest.getId(), ready, start));
            Future<Boolean> second = executor.submit(() -> approveAfterBarrier(secondRequest.getId(), ready, start));

            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();

            int approvals = (first.get(10, TimeUnit.SECONDS) ? 1 : 0)
                    + (second.get(10, TimeUnit.SECONDS) ? 1 : 0);
            assertEquals(1, approvals);
            assertEquals(1, leaveRequests.findAll().stream()
                    .filter(request -> request.getEmployeeId().equals(employee.getId()))
                    .filter(request -> request.getStatus() == LeaveStatus.APPROVED)
                    .count());
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private boolean approveAfterBarrier(Long requestId, CountDownLatch ready, CountDownLatch start)
            throws InterruptedException {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Timed out waiting to start concurrent approval");
        }
        try {
            leaveRequestService.approve(requestId);
            return true;
        } catch (LeaveRequestService.ConflictException e) {
            return false;
        }
    }

    private Employee createEmployee(int annualQuota) {
        Employee employee = new Employee();
        employee.setName("Test Employee");
        employee.setAnnualQuota(annualQuota);
        return employees.save(employee);
    }

    private LeaveRequest createRequest(Employee employee, LeaveType type, LeaveStatus status,
                                       LocalDate startDate, LocalDate endDate) {
        LeaveRequest request = new LeaveRequest();
        request.setEmployeeId(employee.getId());
        request.setType(type);
        request.setStatus(status);
        request.setStartDate(startDate);
        request.setEndDate(endDate);
        request.setDays((int) ChronoUnit.DAYS.between(startDate, endDate) + 1);
        return leaveRequests.save(request);
    }
}
