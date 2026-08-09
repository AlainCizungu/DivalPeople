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

  // FormData must set its own Content-Type, because only the browser knows the multipart
  // boundary it generated. Sending "application/json" over a file upload makes the server unable
  // to parse it; sending the header with an undefined value is worse, because Headers coerces it
  // to the literal string "undefined".
  const sendingForm = init?.body instanceof FormData;

  const response = await fetch(proxied, {
    ...init,
    credentials: "include",
    headers: {
      ...(sendingForm ? {} : { "Content-Type": "application/json" }),
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

/**
 * Aging bands, mirroring the columns of the real telecom export.
 *
 * <p>Ordered as the server returns them. Do not sort this list in a component — the order is the
 * age order, and alphabetising it would put DAYS_120 before DAYS_30.
 */
export type AgingBand =
  | "NOT_DUE"
  | "DAYS_30"
  | "DAYS_60"
  | "DAYS_90"
  | "DAYS_120"
  | "DAYS_150"
  | "DAYS_180"
  | "DAYS_270"
  | "OVER_270";

/**
 * Money, per currency, always.
 *
 * <p>Amounts are strings because they are decimal totals: parsing them into a JavaScript number
 * to add them up would reintroduce exactly the rounding the backend uses BigDecimal to avoid.
 * Formatting for display is fine; arithmetic is not, and there is none in these screens.
 */
export type CurrencyExposure = {
  currency: string;
  outstanding: string;
  outstandingCount: number;
  contested: string;
  contestedCount: number;
  settled: string;
  settledCount: number;
};

export type PortfolioBand = {
  band: AgingBand;
  count: number;
  amounts: { currency: string; amount: string }[];
};

export type Portfolio = {
  asOf: string;
  recordCount: number;
  importedRecords: number;
  awaitingErasure: number;
  exposure: CurrencyExposure[];
  aging: PortfolioBand[];
  byStatus: { status: DebtStatus; count: number }[];
  byService: { label: string; count: number }[];
};

/** What a person is asking the exchange to do about the data it holds on them. */
export type SubjectRequestType = "ACCESS" | "RECTIFICATION" | "ERASURE" | "DISPUTE";

export type SubjectRequestStatus =
  | "RECEIVED"
  | "IDENTITY_VERIFIED"
  | "UPHELD"
  | "REFUSED"
  | "WITHDRAWN";

/**
 * A case, as shown to whoever is handling it.
 *
 * <p>Carries no identifier and no name, and the server does not send them. Whoever is progressing
 * the case already knows who walked in; a queue that echoed identity documents back would be a
 * second copy of the registry with weaker controls around it.
 *
 * <p>`overdue` is computed on the server. Whether a statutory deadline has run out is a fact, not
 * a rendering choice, and two browsers in different timezones must not disagree about it.
 */
export type SubjectRequest = {
  id: string;
  requestType: SubjectRequestType;
  status: SubjectRequestStatus;
  detail: string | null;
  raisedAt: string;
  dueAt: string;
  overdue: boolean;
  identityVerifiedAt: string | null;
  decidedAt: string | null;
  decisionReason: string | null;
};

/**
 * One operator's entry in a person's file.
 *
 * <p>Names the operator, which is exactly what an enquiring operator is never told. The subject is
 * entitled to know who is reporting them; a competitor is not.
 */
export type Disclosure = {
  operator: string;
  status: DebtStatus;
  amount: string;
  defaultDate: string;
  retainedUntil: string;
};

export type Edition =
  | "BANKING"
  | "NGO"
  | "TELECOM"
  | "GOVERNMENT"
  | "HEALTHCARE"
  | "ENTERPRISE";

/** A participating institution. One tenant, one organisation on the exchange. */
export type Participant = {
  id: string;
  name: string;
  slug: string;
  edition: Edition;
  defaultLocale: string;
  active: boolean;
  createdAt: string;
};

/**
 * Platform administration.
 *
 * <p>Guarded by PLATFORM_ADMIN on the server. The nav hides these screens when the signed-in user
 * lacks the role, which is a courtesy rather than a control — the server refuses regardless, and
 * hiding a link has never stopped anybody typing a URL.
 */
export const participantsApi = {
  list(): Promise<Participant[]> {
    return request<Participant[]>("/api/v1/platform/tenants");
  },

  create(body: {
    name: string;
    slug: string;
    edition: Edition;
    defaultLocale: string;
  }): Promise<Participant> {
    return request<Participant>("/api/v1/platform/tenants", {
      method: "POST",
      body: JSON.stringify(body),
    });
  },

  activate(id: string): Promise<Participant> {
    return request<Participant>(`/api/v1/platform/tenants/${id}/activate`, { method: "POST" });
  },

  deactivate(id: string): Promise<Participant> {
    return request<Participant>(`/api/v1/platform/tenants/${id}/deactivate`, { method: "POST" });
  },
};

export type SourceKind = "SPREADSHEET" | "API" | "MANUAL";

export type IngestSource = {
  id: string;
  code: string;
  name: string;
  kind: SourceKind;
  active: boolean;
};

export type BatchStatus =
  | "RECEIVED"
  | "VALIDATED"
  | "PUBLISHED"
  | "REJECTED"
  | "REVERTED";

export type ImportBatch = {
  id: string;
  sourceId: string;
  sourceCode: string;
  filename: string;
  checksumSha256: string;
  byteSize: number;
  rowCount: number;
  status: BatchStatus;
  receivedAt: string;
  publishedAt: string | null;
};

/** @param payload the row as stored: a JSON object, verbatim, never re-serialised. */
export type RawRow = { id: string; rowNumber: number; payload: string };

export const ingestApi = {
  listSources(): Promise<IngestSource[]> {
    return request<IngestSource[]>("/api/v1/ingest/sources");
  },

  registerSource(body: { code: string; name: string; kind: SourceKind }): Promise<IngestSource> {
    return request<IngestSource>("/api/v1/ingest/sources", {
      method: "POST",
      body: JSON.stringify(body),
    });
  },

  /**
   * Uploads the file itself, not a parsed version of it.
   *
   * <p>`request` omits its default Content-Type for a FormData body, so the browser supplies the
   * multipart boundary itself.
   *
   * <p>The server parses the CSV. Doing it here would mean the checksum and the stored rows were
   * two unrelated claims — see CsvReader.
   */
  upload(sourceId: string, file: File): Promise<ImportBatch> {
    const form = new FormData();
    form.append("file", file);
    form.append("sourceId", sourceId);
    return request<ImportBatch>("/api/v1/ingest/batches", {
      method: "POST",
      body: form,
    });
  },

  listBatches(): Promise<ImportBatch[]> {
    return request<ImportBatch[]>("/api/v1/ingest/batches");
  },

  rows(batchId: string, limit = 50): Promise<RawRow[]> {
    return request<RawRow[]>(`/api/v1/ingest/batches/${batchId}/rows?limit=${limit}`);
  },

  validate(batchId: string): Promise<ImportBatch> {
    return request<ImportBatch>(`/api/v1/ingest/batches/${batchId}/validate`, { method: "POST" });
  },

  publish(batchId: string): Promise<ImportBatch> {
    return request<ImportBatch>(`/api/v1/ingest/batches/${batchId}/publish`, { method: "POST" });
  },

  reject(batchId: string, reason: string): Promise<ImportBatch> {
    return request<ImportBatch>(`/api/v1/ingest/batches/${batchId}/reject`, {
      method: "POST",
      body: JSON.stringify({ reason }),
    });
  },

  revert(batchId: string, reason: string): Promise<ImportBatch> {
    return request<ImportBatch>(`/api/v1/ingest/batches/${batchId}/revert`, {
      method: "POST",
      body: JSON.stringify({ reason }),
    });
  },
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

  /** The calling operator's own exposure. Aggregated server-side; there is no tenant parameter. */
  portfolio(): Promise<Portfolio> {
    return request<Portfolio>("/api/v1/tix/portfolio");
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

/**
 * What a person may ask about themselves.
 *
 * <p>Raising and deciding are guarded by different roles on the server, and the split is the
 * point: whoever takes the request at the counter should not also rule on it. These screens hide
 * the actions an account cannot perform, which is a courtesy — the server refuses regardless.
 */
export const subjectRightsApi = {
  list(): Promise<SubjectRequest[]> {
    return request<SubjectRequest[]>("/api/v1/tix/subject-requests");
  },

  raise(body: {
    requestType: SubjectRequestType;
    identifierType: IdentifierType;
    identifier: string;
    detail?: string;
  }): Promise<SubjectRequest> {
    return request<SubjectRequest>("/api/v1/tix/subject-requests", {
      method: "POST",
      body: JSON.stringify(body),
    });
  },

  verifyIdentity(id: string, evidence: string): Promise<SubjectRequest> {
    return request<SubjectRequest>(
      `/api/v1/tix/subject-requests/${id}/verify-identity`,
      { method: "POST", body: JSON.stringify({ evidence }) },
    );
  },

  /** The subject's whole file, across every operator. The most heavily audited read here. */
  disclose(id: string): Promise<Disclosure[]> {
    return request<Disclosure[]>(`/api/v1/tix/subject-requests/${id}/disclosure`);
  },

  decideErasure(id: string): Promise<SubjectRequest> {
    return request<SubjectRequest>(
      `/api/v1/tix/subject-requests/${id}/decide-erasure`,
      { method: "POST" },
    );
  },

  close(id: string, upheld: boolean, reason: string): Promise<SubjectRequest> {
    return request<SubjectRequest>(`/api/v1/tix/subject-requests/${id}/close`, {
      method: "POST",
      body: JSON.stringify({ upheld, reason }),
    });
  },
};
