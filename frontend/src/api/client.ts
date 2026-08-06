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
