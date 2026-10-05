import { AbstractControl, ValidationErrors, ValidatorFn } from '@angular/forms';

export const leaveRequestDateRangeValidator: ValidatorFn = (
  control: AbstractControl
): ValidationErrors | null => {
  const startDate = control.get('startDate')?.value as string | null;
  const endDate = control.get('endDate')?.value as string | null;

  if (!startDate || !endDate) {
    return null;
  }

  if (parseDate(startDate) === null || parseDate(endDate) === null) {
    return { invalidDate: true };
  }

  if (startDate > endDate) {
    return { startAfterEnd: true };
  }

  if (startDate.slice(0, 4) !== endDate.slice(0, 4)) {
    return { spansCalendarYears: true };
  }

  return null;
};

export function calculateInclusiveDays(startDate: string | null, endDate: string | null): number | null {
  if (!startDate || !endDate || startDate > endDate || startDate.slice(0, 4) !== endDate.slice(0, 4)) {
    return null;
  }

  const start = parseDate(startDate);
  const end = parseDate(endDate);
  if (start === null || end === null) {
    return null;
  }

  return Math.floor((end - start) / 86_400_000) + 1;
}

function parseDate(value: string): number | null {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value);
  if (!match) {
    return null;
  }

  const year = Number(match[1]);
  const month = Number(match[2]);
  const day = Number(match[3]);
  const timestamp = Date.UTC(year, month - 1, day);
  const parsed = new Date(timestamp);

  return parsed.getUTCFullYear() === year
    && parsed.getUTCMonth() === month - 1
    && parsed.getUTCDate() === day
    ? timestamp
    : null;
}
