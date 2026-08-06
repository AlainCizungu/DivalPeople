import { apiBaseUrl } from "@/auth/config";

/** Shapes mirroring the backend DTOs in ai.dival.dip.modules.tix. */
export type IdentifierType =
  | "MSISDN"
  | "NATIONAL_ID"
  | "PASSPORT"
  | "DRIVER_LICENSE"
  | "VOTER_CARD"
  | "RCCM"
  | "TAX_NUMBER";

export type DebtStatus =
  | "OUTSTANDING"
  | "SETTLED"
  | "DISPUTED"
  | "UNDER_INVESTIGATION"
  | "CLEARED";

export type InquiryOutcome =
  | "NO_MATCH"
  | "CLEAR"
  | "OUTSTANDING_DEBT"
  | "REVIEW_REQUIRED";

export type InquiryRequest = {
  identifiers: { type: IdentifierType; value: string }[];
  fullName?: string;
  purpose: string;
};

export type InquiryResult = {
  outcome: InquiryOutcome;
  confidence: number;
  subjectId: string | null;
  statuses: DebtStatus[];
  fraudSignals: string[];
};

/** Thrown for any non-2xx response, carrying the status so callers can distinguish 403 from 500. */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
  ) {
    super(message);
    this.name = "ApiError";
  }
}

async function request<T>(
  path: string,
  accessToken: string,
  init?: RequestInit,
): Promise<T> {
  const response = await fetch(`${apiBaseUrl}${path}`, {
    ...init,
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${accessToken}`,
      ...init?.headers,
    },
  });

  if (!response.ok) {
    // The API returns a stable error envelope, but a proxy or gateway may not.
    let code = "UNKNOWN";
    let message = response.statusText;
    try {
      const body = (await response.json()) as { code?: string; message?: string };
      code = body.code ?? code;
      message = body.message ?? message;
    } catch {
      // Non-JSON error body; the status alone will have to do.
    }
    throw new ApiError(response.status, code, message);
  }

  if (response.status === 204) {
    return undefined as T;
  }
  return (await response.json()) as T;
}

export type OrgUnitType =
  | "LEGAL_ENTITY"
  | "BRANCH"
  | "DEPARTMENT"
  | "COST_CENTER"
  | "LOCATION";

export type OrgUnit = {
  id: string;
  parentId: string | null;
  unitType: OrgUnitType;
  code: string;
  name: string;
  depth: number;
  active: boolean;
};

export const organizationApi = {
  listUnits(accessToken: string): Promise<OrgUnit[]> {
    return request<OrgUnit[]>("/api/v1/organization/units", accessToken);
  },
};

export type EmployeeStatus = "ACTIVE" | "ON_LEAVE" | "SUSPENDED" | "TERMINATED";

/**
 * Directory view of an employee. Deliberately carries no personal data beyond a name — the
 * server returns dates of birth and identifiers only from the single-employee endpoint.
 */
export type EmployeeSummary = {
  id: string;
  employeeNumber: string;
  displayName: string;
  status: EmployeeStatus;
  orgUnitId: string | null;
  orgUnitName: string | null;
  managerId: string | null;
};

export const employeesApi = {
  list(accessToken: string): Promise<EmployeeSummary[]> {
    return request<EmployeeSummary[]>("/api/v1/employees", accessToken);
  },
};

export type RequisitionStatus =
  | "DRAFT"
  | "PENDING_APPROVAL"
  | "APPROVED"
  | "OPEN"
  | "ON_HOLD"
  | "FILLED"
  | "CANCELLED";

export type ApplicationStatus =
  | "APPLIED"
  | "SCREENING"
  | "INTERVIEWING"
  | "OFFER"
  | "HIRED"
  | "REJECTED"
  | "WITHDRAWN";

export type ContractType =
  | "PERMANENT"
  | "FIXED_TERM"
  | "PART_TIME"
  | "INTERNSHIP"
  | "CONSULTANT";

export type Requisition = {
  id: string;
  requisitionNumber: string;
  title: string;
  contractType: ContractType;
  headcount: number;
  filledCount: number;
  status: RequisitionStatus;
  orgUnitId: string | null;
  orgUnitName: string | null;
  requestedBy: string | null;
  approvedBy: string | null;
  description: string | null;
  targetStartDate: string | null;
};

export type Application = {
  id: string;
  requisitionId: string;
  requisitionTitle: string;
  candidateId: string;
  candidateName: string;
  status: ApplicationStatus;
  appliedOn: string;
  outcomeReason: string | null;
};

export const recruitmentApi = {
  listRequisitions(accessToken: string): Promise<Requisition[]> {
    return request<Requisition[]>("/api/v1/recruitment/requisitions", accessToken);
  },

  applications(requisitionId: string, accessToken: string): Promise<Application[]> {
    return request<Application[]>(
      `/api/v1/recruitment/requisitions/${requisitionId}/applications`,
      accessToken,
    );
  },
};

export type ChecklistType = "ONBOARDING" | "OFFBOARDING";

export type ChecklistStatus = "IN_PROGRESS" | "COMPLETED" | "CANCELLED";

export type ItemStatus = "PENDING" | "DONE" | "BLOCKED" | "NOT_APPLICABLE";

export type ItemCategory =
  | "PAPERWORK"
  | "EQUIPMENT"
  | "ACCESS"
  | "PAYROLL"
  | "TRAINING"
  | "INTRODUCTION"
  | "COMPLIANCE"
  | "OTHER";

/** List view. Carries counts rather than every step of every checklist. */
export type ChecklistSummary = {
  id: string;
  employeeId: string;
  employeeName: string;
  checklistType: ChecklistType;
  templateName: string;
  anchorDate: string;
  status: ChecklistStatus;
  settledCount: number;
  itemCount: number;
  outstandingMandatory: number;
};

export type ChecklistItem = {
  id: string;
  checklistId: string;
  sortOrder: number;
  title: string;
  instructions: string | null;
  category: ItemCategory;
  assigneeId: string | null;
  assigneeName: string | null;
  dueOn: string | null;
  mandatory: boolean;
  status: ItemStatus;
  completedAt: string | null;
  notes: string | null;
};

export type ChecklistDetail = {
  id: string;
  employeeId: string;
  employeeName: string;
  checklistType: ChecklistType;
  templateName: string;
  anchorDate: string;
  status: ChecklistStatus;
  completedAt: string | null;
  items: ChecklistItem[];
};

export const lifecycleApi = {
  open(accessToken: string): Promise<ChecklistSummary[]> {
    return request<ChecklistSummary[]>("/api/v1/lifecycle/checklists", accessToken);
  },

  checklist(id: string, accessToken: string): Promise<ChecklistDetail> {
    return request<ChecklistDetail>(`/api/v1/lifecycle/checklists/${id}`, accessToken);
  },

  settle(
    id: string,
    body: { status: ItemStatus; notes?: string; completedByEmployeeId?: string },
    accessToken: string,
  ): Promise<ChecklistItem> {
    return request<ChecklistItem>(`/api/v1/lifecycle/items/${id}/status`, accessToken, {
      method: "POST",
      body: JSON.stringify(body),
    });
  },
};

export type LeaveRequestStatus = "SUBMITTED" | "APPROVED" | "REJECTED" | "CANCELLED";

export type LedgerEntryType =
  | "OPENING"
  | "ACCRUAL"
  | "GRANT"
  | "TAKEN"
  | "RETURNED"
  | "ADJUSTMENT"
  | "LAPSED";

/** Every figure that goes into the total, so the number is never taken on trust. */
export type LeaveBalance = {
  id: string;
  employeeId: string;
  leaveTypeId: string;
  leaveTypeName: string;
  leaveYear: number;
  openingDays: string;
  accruedDays: string;
  takenDays: string;
  pendingDays: string;
  adjustmentDays: string;
  availableDays: string;
};

export type LeaveRequest = {
  id: string;
  employeeId: string;
  employeeName: string;
  leaveTypeId: string;
  leaveTypeName: string;
  startDate: string;
  endDate: string;
  halfDayStart: boolean;
  halfDayEnd: boolean;
  days: string;
  reason: string | null;
  status: LeaveRequestStatus;
  approverId: string | null;
  approverName: string | null;
  decidedAt: string | null;
  decisionNotes: string | null;
};

export type LeaveLedgerEntry = {
  id: string;
  entryType: LedgerEntryType;
  days: string;
  requestId: string | null;
  reason: string | null;
  createdAt: string;
};

export const leaveApi = {
  balances(employeeId: string, accessToken: string, year?: number): Promise<LeaveBalance[]> {
    const query = year === undefined ? "" : `?year=${year}`;
    return request<LeaveBalance[]>(
      `/api/v1/leave/employees/${employeeId}/balances${query}`,
      accessToken,
    );
  },

  requests(employeeId: string, accessToken: string): Promise<LeaveRequest[]> {
    return request<LeaveRequest[]>(
      `/api/v1/leave/employees/${employeeId}/requests`,
      accessToken,
    );
  },

  pending(accessToken: string): Promise<LeaveRequest[]> {
    return request<LeaveRequest[]>("/api/v1/leave/requests/pending", accessToken);
  },

  ledger(balanceId: string, accessToken: string): Promise<LeaveLedgerEntry[]> {
    return request<LeaveLedgerEntry[]>(
      `/api/v1/leave/balances/${balanceId}/ledger`,
      accessToken,
    );
  },
};

export type TimeEntrySource = "WEB" | "MOBILE" | "BIOMETRIC" | "IMPORT" | "MANUAL";

export type TimesheetStatus = "DRAFT" | "SUBMITTED" | "APPROVED" | "REJECTED";

export type TimeEntry = {
  id: string;
  employeeId: string;
  employeeName: string;
  workDate: string;
  startedAt: string;
  endedAt: string | null;
  breakMinutes: number;
  workedMinutes: number;
  source: TimeEntrySource;
  notes: string | null;
  supersedesId: string | null;
  superseded: boolean;
  amendReason: string | null;
};

/** Minutes throughout. What an hour is worth is a payroll question, not this one. */
export type TimesheetTotals = {
  worked: number;
  expected: number;
  leave: number;
  holiday: number;
  overtime: number;
  absent: number;
};

export type Timesheet = {
  id: string;
  employeeId: string;
  employeeName: string;
  periodStart: string;
  periodEnd: string;
  workedMinutes: number;
  expectedMinutes: number;
  leaveMinutes: number;
  holidayMinutes: number;
  overtimeMinutes: number;
  absentMinutes: number;
  status: TimesheetStatus;
  submittedAt: string | null;
  approverId: string | null;
  approverName: string | null;
  decidedAt: string | null;
  decisionNotes: string | null;
};

export const attendanceApi = {
  entries(
    employeeId: string,
    from: string,
    to: string,
    accessToken: string,
  ): Promise<TimeEntry[]> {
    return request<TimeEntry[]>(
      `/api/v1/attendance/employees/${employeeId}/entries?from=${from}&to=${to}`,
      accessToken,
    );
  },

  preview(
    employeeId: string,
    from: string,
    to: string,
    accessToken: string,
  ): Promise<TimesheetTotals> {
    return request<TimesheetTotals>(
      `/api/v1/attendance/employees/${employeeId}/preview?from=${from}&to=${to}`,
      accessToken,
    );
  },

  timesheets(employeeId: string, accessToken: string): Promise<Timesheet[]> {
    return request<Timesheet[]>(
      `/api/v1/attendance/employees/${employeeId}/timesheets`,
      accessToken,
    );
  },

  pending(accessToken: string): Promise<Timesheet[]> {
    return request<Timesheet[]>("/api/v1/attendance/timesheets/pending", accessToken);
  },
};

export type NotificationSeverity = "INFO" | "WARNING" | "CRITICAL";

export type AppNotification = {
  id: string;
  /** Translation key such as "contractExpiring"; the server never sends rendered text. */
  messageKey: string;
  params: Record<string, string>;
  severity: NotificationSeverity;
  resourceType: string | null;
  resourceId: string | null;
  read: boolean;
  createdAt: string;
};

export const notificationsApi = {
  list(accessToken: string): Promise<AppNotification[]> {
    return request<AppNotification[]>("/api/v1/notifications", accessToken);
  },

  unreadCount(accessToken: string): Promise<{ unread: number }> {
    return request<{ unread: number }>("/api/v1/notifications/unread-count", accessToken);
  },

  markRead(id: string, accessToken: string): Promise<AppNotification> {
    return request<AppNotification>(`/api/v1/notifications/${id}/read`, accessToken, {
      method: "POST",
    });
  },

  markAllRead(accessToken: string): Promise<{ marked: number }> {
    return request<{ marked: number }>("/api/v1/notifications/read-all", accessToken, {
      method: "POST",
    });
  },
};

export const tixApi = {
  inquire(body: InquiryRequest, accessToken: string): Promise<InquiryResult> {
    return request<InquiryResult>("/api/v1/tix/inquiries", accessToken, {
      method: "POST",
      body: JSON.stringify(body),
    });
  },

  listDebtRecords(accessToken: string) {
    return request<unknown[]>("/api/v1/tix/debt-records", accessToken);
  },
};
