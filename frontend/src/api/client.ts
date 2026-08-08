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
  "OUTSTANDING" | "SETTLED" | "DISPUTED" | "UNDER_INVESTIGATION" | "CLEARED";

export type InquiryOutcome =
  "NO_MATCH" | "CLEAR" | "OUTSTANDING_DEBT" | "REVIEW_REQUIRED";

export type InquiryRequest = {
  identifiers: { type: IdentifierType; value: string }[];
  fullName?: string;
  purpose: string;
};

export type InquiryResult = {
  outcome: InquiryOutcome;
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

/**
 * Calls the API through this application's own server.
 *
 * <p>There is no access token in this file, and no parameter to pass one. The proxy attaches it
 * server-side from a session the browser cannot read — see ADR 0003. `credentials: "include"`
 * is what carries the session cookie.
 */
async function request<T>(path: string, init?: RequestInit): Promise<T> {
  // /api/v1/employees becomes /api/proxy/employees; the proxy puts the version back.
  const proxied = path.replace(/^\/api\/v1/, "/api/proxy");

  const response = await fetch(proxied, {
    ...init,
    credentials: "include",
    headers: {
      "Content-Type": "application/json",
      ...init?.headers,
    },
  });

  if (!response.ok) {
    // The API returns a stable error envelope, but a proxy or gateway may not.
    let code = "UNKNOWN";
    let message = response.statusText;
    try {
      const body = (await response.json()) as {
        code?: string;
        message?: string;
      };
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
  "LEGAL_ENTITY" | "BRANCH" | "DEPARTMENT" | "COST_CENTER" | "LOCATION";

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
  listUnits(): Promise<OrgUnit[]> {
    return request<OrgUnit[]>("/api/v1/organization/units");
  },
};

// Dival People (HR) clients — employees, recruitment, lifecycle, leave, attendance,
// performance, learning, payroll and self-service — were removed on 8 August 2026 with the
// screens that called them. The backend endpoints still exist and still pass their tests;
// nothing in this application reaches them any more. See docs/ROADMAP.md.

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
  list(): Promise<AppNotification[]> {
    return request<AppNotification[]>("/api/v1/notifications");
  },

  unreadCount(): Promise<{ unread: number }> {
    return request<{ unread: number }>("/api/v1/notifications/unread-count");
  },

  markRead(id: string): Promise<AppNotification> {
    return request<AppNotification>(`/api/v1/notifications/${id}/read`, {
      method: "POST",
    });
  },

  markAllRead(): Promise<{ marked: number }> {
    return request<{ marked: number }>("/api/v1/notifications/read-all", {
      method: "POST",
    });
  },
};

export type SubjectType = "INDIVIDUAL" | "BUSINESS";

/** What an operator submits to declare that one of its subscribers has defaulted. */
export type DeclarationRequest = {
  identifiers: { type: IdentifierType; value: string }[];
  fullName: string;
  subjectType: SubjectType;
  dateOfBirth: string | null;
  nationality: string | null;
  amount: string;
  currency: string;
  serviceCategory: string;
  defaultDate: string;
  dunningEvidence: boolean;
};

/**
 * A record as returned to the operator that declared it.
 *
 * <p>Carries an amount, which {@link InquiryResult} deliberately never does. This is an
 * operator's own data coming back to it; the exchange still tells other operators only a status.
 */
export type DebtRecord = {
  id: string;
  subjectId: string;
  status: DebtStatus;
  amount: string;
  currency: string;
  serviceCategory: string;
  defaultDate: string;
  retentionUntil: string;
};

export type DeclarationResult = {
  record: DebtRecord;
  subjectWasCreated: boolean;
  identifiersLearned: number;
};

export const tixApi = {
  inquire(body: InquiryRequest): Promise<InquiryResult> {
    return request<InquiryResult>("/api/v1/tix/inquiries", {
      method: "POST",
      body: JSON.stringify(body),
    });
  },

  declare(body: DeclarationRequest): Promise<DeclarationResult> {
    return request<DeclarationResult>("/api/v1/tix/debt-records", {
      method: "POST",
      body: JSON.stringify(body),
    });
  },

  listDebtRecords(): Promise<DebtRecord[]> {
    return request<DebtRecord[]>("/api/v1/tix/debt-records");
  },

  settle(id: string): Promise<DebtRecord> {
    return request<DebtRecord>(`/api/v1/tix/debt-records/${id}/settle`, {
      method: "POST",
    });
  },

  dispute(id: string): Promise<DebtRecord> {
    return request<DebtRecord>(`/api/v1/tix/debt-records/${id}/dispute`, {
      method: "POST",
    });
  },
};
