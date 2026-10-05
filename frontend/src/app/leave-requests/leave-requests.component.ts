import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { CreateLeaveRequest, Employee, LeaveRequest, LeaveType } from '../models/leave-request.model';
import { calculateInclusiveDays, leaveRequestDateRangeValidator } from '../models/leave-request-form';

// NOTE: This component was written quickly for a POC.
// It talks to the API directly, manages state by hand and uses `any` everywhere.
@Component({
  selector: 'app-leave-requests',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule],
  templateUrl: './leave-requests.component.html',
  styleUrls: ['./leave-requests.component.css']
})
export class LeaveRequestsComponent implements OnInit {
  requests: LeaveRequest[] = [];
  employees: Employee[] = [];
  loading = false;
  employeesError = '';
  submitting = false;
  submitted = false;
  formError = '';
  formSuccess = '';

  readonly leaveTypes = [
    { value: LeaveType.Vacation, label: 'Vacation' },
    { value: LeaveType.Sick, label: 'Sick' },
    { value: LeaveType.Unpaid, label: 'Unpaid' }
  ];
  readonly requestForm = new FormGroup({
    employeeId: new FormControl<number | null>(null, Validators.required),
    type: new FormControl<LeaveType | null>(null, Validators.required),
    startDate: new FormControl('', { nonNullable: true, validators: Validators.required }),
    endDate: new FormControl('', { nonNullable: true, validators: Validators.required })
  }, { validators: leaveRequestDateRangeValidator });

  private apiUrl = 'http://localhost:5080/api/leave-requests';
  private employeesApiUrl = 'http://localhost:5080/api/employees';

  constructor(private http: HttpClient) {}

  ngOnInit(): void {
    this.load();
    this.http.get<Employee[]>(this.employeesApiUrl).subscribe({
      next: (employees) => this.employees = employees,
      error: (error: unknown) => this.employeesError = this.getErrorMessage(
        error,
        'Could not load employees. Please try again later.'
      )
    });
  }

  load(): void {
    this.loading = true;
    this.http.get<LeaveRequest[]>(this.apiUrl).subscribe({
      next: (data) => {
        this.requests = data;
        this.loading = false;
      },
      error: () => {
        this.loading = false;
      }
    });
  }

  get inclusiveDays(): number | null {
    const { startDate, endDate } = this.requestForm.getRawValue();
    return calculateInclusiveDays(startDate, endDate);
  }

  shouldShowError(controlName: 'employeeId' | 'type' | 'startDate' | 'endDate'): boolean {
    const control = this.requestForm.controls[controlName];
    return control.invalid && (control.touched || this.submitted);
  }

  submitRequest(): void {
    this.submitted = true;
    this.requestForm.markAllAsTouched();
    this.formError = '';
    this.formSuccess = '';

    if (this.requestForm.invalid || this.submitting) {
      return;
    }

    const { employeeId, type, startDate, endDate } = this.requestForm.getRawValue();
    if (employeeId === null || type === null) {
      return;
    }

    const payload: CreateLeaveRequest = { employeeId, type, startDate, endDate };
    this.submitting = true;
    this.http.post<LeaveRequest>(this.apiUrl, payload).subscribe({
      next: (request) => {
        const employee = this.employees.find(item => item.id === request.employeeId);
        const createdRequest = { ...request, employee: request.employee ?? employee };
        this.requests = [...this.requests, createdRequest]
          .sort((a, b) => b.startDate.localeCompare(a.startDate));
        this.formSuccess = 'Leave request submitted successfully.';
        this.requestForm.reset({
          employeeId: null,
          type: null,
          startDate: '',
          endDate: ''
        });
        this.submitted = false;
        this.submitting = false;
      },
      error: (error: unknown) => {
        this.formError = this.getErrorMessage(error, 'Could not submit the leave request.');
        this.submitting = false;
      }
    });
  }

  showDateRangeError(): boolean {
    return (this.requestForm.controls.startDate.touched
      || this.requestForm.controls.endDate.touched
      || this.submitted)
      && (this.requestForm.hasError('startAfterEnd')
        || this.requestForm.hasError('spansCalendarYears')
        || this.requestForm.hasError('invalidDate'));
  }

  private getErrorMessage(error: unknown, fallback: string): string {
    if (error instanceof HttpErrorResponse) {
      if (typeof error.error === 'string' && error.error.trim()) {
        return error.error;
      }
      if (typeof error.error?.message === 'string') {
        return error.error.message;
      }
      if (error.status === 0) {
        return 'Could not connect to the server. Check that the API is running.';
      }
    }
    return fallback;
  }

  // Wired up by the candidate as part of the assignment.
  approve(id: number): void {
    // TODO (candidate): call POST /api/leave-requests/{id}/approve
    // and handle loading / error / success without a generic alert.
    this.http.post<any>(this.apiUrl + '/' + id + '/approve', {}).subscribe(() => {
      this.load();
    });
  }

  typeLabel(type: number): string {
    if (type == 0) return 'Vacation';
    if (type == 1) return 'Sick';
    return 'Unpaid';
  }

  statusLabel(status: number): string {
    if (status == 0) return 'Pending';
    if (status == 1) return 'Approved';
    return 'Rejected';
  }
}
