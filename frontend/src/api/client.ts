/** Shapes mirroring the backend DTOs in ai.dival.dip.modules.tix. */
export type IdentifierType =
  | "MSISDN"
  | "NATIONAL_ID"
  | "PASSPORT"
  | "DRIVER_LICENSE"
  | "VOTER_CARD"
  | "RCCM"
  | "TAX_NUMBER"
  | "ACCOUNT_REFERENCE";

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
  /**
   * How many participating operators hold a record that counts — never which.
   *
   * The one number the exchange discloses. It is not statuses.length, which is what this screen
   * used to show under a label promising institutions: two operators both reporting an
   * outstanding debt collapse to a single status.
   */
  institutionCount: number;
  fraudSignals: string[];
  /**
   * The DIP Risk Indicator, or null when the exchange had nothing to assess.
   *
   * Null for a no-match and for a match the platform is not confident about, for the same reason
   * the subject id is withheld: an answer the exchange will not stand behind carries a verdict
   * and nothing else.
   */
  indicator: RiskIndicator | null;
};

export type RiskFactorCode =
  | "PAYMENT_BEHAVIOUR"
  | "DEBT_AGING"
  | "REPORTING_INSTITUTIONS"
  | "IDENTITY_CONFIDENCE"
  | "FRAUD_INDICATORS"
  | "OUTSTANDING_EXPOSURE"
  | "DISPUTE_HISTORY";

export type RiskRating = "NOT_ASSESSED" | "LOW" | "MODERATE" | "HIGH";

export type RiskBand = "LOW" | "MODERATE" | "ELEVATED" | "HIGH";

export type NotAssessedReason =
  | "MIXED_CURRENCY"
  | "DISPUTES_ARE_NOT_DISCLOSED"
  | "NO_FRAUD_SIGNAL_IS_COMPUTABLE";

export type RiskFactor = {
  code: RiskFactorCode;
  rating: RiskRating;
  points: number;
  /** Why this factor was left out, and null when it was not. */
  reason: NotAssessedReason | null;
};

/**
 * A risk indicator and everything behind it.
 *
 * The scale runs the risk way up: 0 is no adverse information and 100 is the most the platform
 * can observe. That is the opposite of a credit score, which is the point — anybody who mistakes
 * it for one reads it backwards immediately and finds out.
 *
 * Codes travel on the wire and the words live in the message catalogue, so a factor can be
 * reworded in either language without touching the model a lending decision was made with.
 */
export type RiskIndicator = {
  score: number;
  band: RiskBand;
  factors: RiskFactor[];
  /** The one or two factors that produced most of the score. Empty when nothing did. */
  principalDrivers: RiskFactorCode[];
  modelVersion: string;
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

/**
 * One row of a search over the calling operator's own book.
 *
 * <p>`outstanding` and `currency` are null when the subject's records span more than one currency;
 * `mixedCurrency` says so, because a single figure across two currencies is a number that is not
 * of anything.
 */
export type SearchResult = {
  subjectId: string;
  name: string;
  subjectType: SubjectType;
  recordCount: number;
  openCount: number;
  outstanding: string | null;
  currency: string | null;
  mixedCurrency: boolean;
  oldestDefault: string | null;
  oldestBand: AgingBand | null;
};

export type HeldRecord = {
  recordId: string;
  status: DebtStatus;
  amount: string;
  currency: string;
  serviceCategory: string;
  defaultDate: string;
  band: AgingBand;
  retainedUntil: string;
  imported: boolean;
};

export type SubjectProfile = {
  subjectId: string;
  name: string;
  subjectType: SubjectType;
  dateOfBirth: string | null;
  nationality: string | null;
  identifiers: { type: IdentifierType; value: string }[];
  summary: SearchResult;
  records: HeldRecord[];
};

/**
 * One line of the audit trail.
 *
 * <p>`actorId` is an account id and not a name. Resolving one would mean the audit code importing
 * the users module, which the architecture rules forbid — the trail belongs to no single module,
 * which is exactly why it must not depend on one.
 *
 * <p>`detail` is the actor's stated reason where the API asked for one. On a TIX inquiry it is the
 * purpose, and it is the column that makes the rest of the row worth keeping.
 */
export type AuditEntry = {
  id: string;
  action: string;
  resourceType: string;
  resourceId: string | null;
  outcome: "SUCCESS" | "DENIED" | "FAILURE";
  actorId: string | null;
  requestId: string | null;
  ipAddress: string | null;
  detail: string | null;
  occurredAt: string;
};

export type AuditActionCount = { action: string; count: number };

export const auditApi = {
  events(action: string | null, limit = 100): Promise<AuditEntry[]> {
    const query = new URLSearchParams({ limit: String(limit) });
    if (action) query.set("action", action);
    return request<AuditEntry[]>(`/api/v1/audit/events?${query.toString()}`);
  },

  /** Counted over the whole trail, not the page. */
  summary(): Promise<AuditActionCount[]> {
    return request<AuditActionCount[]>("/api/v1/audit/summary");
  },
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
  /** Null on deliveries received before the field existed; those cannot be derived from. */
  reportedAsAt: string | null;
  receivedAt: string;
  publishedAt: string | null;
};

/** @param payload the row as stored: a JSON object, verbatim, never re-serialised. */
export type RawRow = { id: string; rowNumber: number; payload: string };

/**
 * One column of a delivery, counted.
 *
 * <p>`vocabulary` is empty for any column with more than a handful of distinct values — that line
 * is what separates a set of statuses worth showing from a list of customers that is not.
 *
 * <p>`total`, `minimum` and `maximum` are decimal strings and are null unless every non-blank
 * value in the column parses as a number. One `#N/A` makes the whole column text, deliberately:
 * a total over the rows that happened to parse would hide the row somebody needs to look at.
 */
export type ProfiledColumn = {
  column: string;
  filled: number;
  blank: number;
  distinct: number;
  numeric: boolean;
  total: string | null;
  minimum: string | null;
  maximum: string | null;
  shortestLength: number;
  longestLength: number;
  vocabulary: { value: string; count: number }[];
};

/**
 * Rows that cannot become records, whatever the mapping turns out to be.
 *
 * <p>Nothing is actually rejected — the batch keeps every row exactly as delivered. This says
 * which ones will not survive contact with the mapping, so an operator can fix their export
 * before anybody depends on it.
 *
 * <p>`complete` is false when there were more problems than `findings` lists. The counts are still
 * exact; only the listing is truncated, and a truncated report that did not say so would be wrong
 * rather than short.
 */
export type BatchIssues = {
  emptyRows: number;
  duplicateRows: number;
  rowsMissingIdentifier: number;
  keyColumns: string[];
  findings: {
    issue: "EMPTY_ROW" | "DUPLICATE_ROW" | "MISSING_IDENTIFIER";
    rowNumber: number;
    column: string | null;
    detail: string | null;
  }[];
  complete: boolean;
};

/**
 * What an operator says its columns mean.
 *
 * <p>`current` is false once a newer version supersedes this one. Mappings are never edited: a
 * published delivery was derived through whichever was current at the time, and rewriting one in
 * place would leave that batch untraceable to the rules that produced it.
 */
export type SourceMappingView = {
  id: string;
  versionNumber: number;
  /** Null when the delivery carries no identifier and the name column identifies the subject. */
  identifierColumn: string | null;
  identifierType: IdentifierType | null;
  nameColumn: string;
  amountColumn: string;
  currency: string;
  serviceCategory: string;
  subjectType: SubjectType;
  current: boolean;
  definedAt: string;
  supersededAt: string | null;
};

/** What the derivation did. `complete` is false when more rows were refused than are listed. */
export type DerivationReport = {
  rows: number;
  created: number;
  refused: number;
  refusals: { rowNumber: number; reason: string }[];
  complete: boolean;
  asAt: string;
  mappingVersion: number;
  /** How long the derivation took. Shown, because whoever waited for it is owed the number. */
  elapsedMs: number;
};

export type BatchProfile = {
  rows: number;
  columns: ProfiledColumn[];
  issues: BatchIssues;
};

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
  /**
   * @param reportedAsAt what the operator says the delivery reflects, as YYYY-MM-DD. Required,
   *                     because the profiled export carries no dates at all and a record derived
   *                     from it would otherwise have a retention clock starting from a moment DIP
   *                     invented rather than one anybody asserted.
   */
  upload(sourceId: string, file: File, reportedAsAt: string): Promise<ImportBatch> {
    const form = new FormData();
    form.append("file", file);
    form.append("sourceId", sourceId);
    form.append("reportedAsAt", reportedAsAt);
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

  /** Counted over every row, not the page of them the rows endpoint returns. */
  profile(batchId: string): Promise<BatchProfile> {
    return request<BatchProfile>(`/api/v1/ingest/batches/${batchId}/profile`);
  },

  /** Null when the operator has not defined one — the server answers 204 rather than 404. */
  async currentMapping(sourceId: string): Promise<SourceMappingView | null> {
    const mapping = await request<SourceMappingView | undefined>(
      `/api/v1/ingest/sources/${sourceId}/mapping`,
    );
    return mapping ?? null;
  },

  mappingHistory(sourceId: string): Promise<SourceMappingView[]> {
    return request<SourceMappingView[]>(
      `/api/v1/ingest/sources/${sourceId}/mapping/history`,
    );
  },

  defineMapping(
    sourceId: string,
    body: {
      identifierColumn: string | null;
      identifierType: IdentifierType | null;
      nameColumn: string;
      amountColumn: string;
      currency: string;
      serviceCategory: string;
      subjectType: SubjectType;
    },
  ): Promise<SourceMappingView> {
    return request<SourceMappingView>(`/api/v1/ingest/sources/${sourceId}/mapping`, {
      method: "POST",
      body: JSON.stringify(body),
    });
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

};

/**
 * What is waiting on somebody, counted on the server.
 *
 * A section is `null` when the caller's roles do not cover it — deliberately not zero, because
 * "no overdue cases" and "you may not see the overdue cases" are different statements and only
 * one of them is reassuring.
 */
export type Overview = {
  asOf: string;
  register: {
    total: number;
    outstanding: number;
    contested: number;
    settled: number;
    expiringSoon: number;
    awaitingErasure: number;
  } | null;
  rights: { open: number; overdue: number; dueSoon: number } | null;
  deliveries: {
    awaitingValidation: number;
    awaitingPublication: number;
    published: number;
  } | null;
};

export const overviewApi = {
  load(): Promise<Overview> {
    return request<Overview>("/api/v1/overview");
  },
};

export type SettingProvenance =
  | "TERMS_OF_REFERENCE"
  | "LEGAL_ADVICE"
  | "UNVERIFIED_PLACEHOLDER"
  | "OPERATIONAL_DEFAULT"
  | "COMPILED"
  | "NOT_SET";

/**
 * One configured value and where it came from.
 *
 * `value` is null when nothing is configured, which for the reporting floor is itself the answer:
 * a currency with no floor is refused rather than defaulted.
 */
export type Setting = {
  key: string;
  value: string | null;
  unit: string | null;
  provenance: SettingProvenance;
};

export type Settings = {
  retention: Setting[];
  rights: Setting[];
  reporting: Setting[];
  exchange: Setting[];
  models: Setting[];
};

export type RoleArea = { name: string; endpoints: number };

/**
 * One role and what it unlocks.
 *
 * `endpoints` can be zero, and that is information rather than an empty state: the role exists,
 * can be assigned, and grants nothing.
 *
 * `heldBy` is null when the caller may not see who is in their organisation — which is not the
 * same as nobody holding it.
 */
export type RoleAccess = {
  role: string;
  endpoints: number;
  areas: RoleArea[];
  heldBy: number | null;
  held: boolean;
};

export type AccessMember = {
  email: string;
  displayName: string;
  roles: string[];
  active: boolean;
  lastSeenAt: string | null;
};

export type Access = {
  roles: RoleAccess[];
  /** Null, not empty, when the caller lacks the tenant administrator role. */
  members: AccessMember[] | null;
};

export type MatchSignalCode =
  | "EXACT_NAME"
  | "SIMILAR_NAME"
  | "SHARED_NATIONAL_IDENTIFIER"
  | "SAME_SUBJECT_TYPE"
  | "SAME_NATIONALITY"
  | "SAME_DATE_OF_BIRTH"
  | "DIFFERENT_ACCOUNT_REFERENCES"
  | "SAME_SECONDARY_PHONE"
  | "SAME_CITY"
  | "SIMILAR_ADDRESS";

/**
 * What one signal said.
 *
 * UNAVAILABLE is not CONFLICTS. "City did not match" and "no delivery carries a city" lead a
 * reviewer to opposite conclusions, and only one of them is true.
 */
export type MatchVerdict = "AGREES" | "CONFLICTS" | "NEUTRAL" | "UNAVAILABLE";

export type MatchSignal = {
  code: MatchSignalCode;
  verdict: MatchVerdict;
  weight: number;
};

export type MatchStatus = "OPEN" | "CONFIRMED" | "REJECTED" | "INVESTIGATING";

export type RegistrySubject = {
  id: string;
  business: boolean;
  fullName: string;
  normalizedName: string;
  nationality: string | null;
  dateOfBirth: string | null;
  nationalIdentifiers: Record<string, string>;
  hasAccountReference: boolean;
};

export type MatchCase = {
  id: string;
  /** The older record, which a confirmation keeps. */
  left: RegistrySubject;
  right: RegistrySubject;
  confidence: number;
  signals: MatchSignal[];
  status: MatchStatus;
  modelVersion: string;
  detectedAt: string;
  note: string | null;
};

export type ResolutionScan = { subjects: number; compared: number; opened: number };

export type ResolutionDecision = {
  caseId: string;
  outcome: MatchStatus;
  survivor: string | null;
  moved: number;
};

export const resolutionApi = {
  open(): Promise<MatchCase[]> {
    return request<MatchCase[]>("/api/v1/resolution/candidates");
  },

  scan(): Promise<ResolutionScan> {
    return request<ResolutionScan>("/api/v1/resolution/scan", { method: "POST" });
  },

  decide(id: string, outcome: MatchStatus, note: string): Promise<ResolutionDecision> {
    return request<ResolutionDecision>(`/api/v1/resolution/candidates/${id}/decision`, {
      method: "POST",
      body: JSON.stringify({ outcome, note }),
    });
  },
};

export type BehaviourFlag = "HIGH_VOLUME" | "MOSTLY_NO_MATCH" | "HIT_THE_RATE_LIMIT";

export type InquiryBehaviour = {
  actorId: string | null;
  inquiries: number;
  noMatch: number;
  refused: number;
  lastAsked: string;
  flags: BehaviourFlag[];
};

/** @param medianInquiries published so a reader can see what "unusual" was measured against. */
export type BehaviourReport = {
  windowDays: number;
  medianInquiries: number;
  people: InquiryBehaviour[];
};

export type Watch = {
  id: string;
  subjectId: string;
  name: string;
  purpose: string;
  expiresAt: string;
  /** The last answer: exactly what an inquiry discloses, and nothing more. */
  lastOutcome: InquiryOutcome | null;
  lastInstitutions: number | null;
  lastCheckedAt: string | null;
};

export const watchlistApi = {
  list(): Promise<Watch[]> {
    return request<Watch[]>("/api/v1/tix/watchlist");
  },

  watch(subjectId: string, purpose: string): Promise<Watch> {
    return request<Watch>("/api/v1/tix/watchlist", {
      method: "POST",
      body: JSON.stringify({ subjectId, purpose }),
    });
  },

  unwatch(id: string): Promise<void> {
    return request<void>(`/api/v1/tix/watchlist/${id}`, { method: "DELETE" });
  },

  /** Costs one inquiry per watch against this operator's hourly allowance. */
  sweep(): Promise<{ watched: number; changed: number }> {
    return request<{ watched: number; changed: number }>("/api/v1/tix/watchlist/sweep", {
      method: "POST",
    });
  },
};

export const anomaliesApi = {
  behaviour(): Promise<BehaviourReport> {
    return request<BehaviourReport>("/api/v1/anomalies/behaviour");
  },
};

export const accessApi = {
  load(): Promise<Access> {
    return request<Access>("/api/v1/access");
  },
};

export const settingsApi = {
  load(): Promise<Settings> {
    return request<Settings>("/api/v1/settings");
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

  /**
   * Searches the calling operator's own book.
   *
   * <p>Not the registry. A subject is shared across operators, so a search starting from subjects
   * would let any participant enumerate what its competitors have reported — the server scopes
   * this to records the caller declared, and there is no parameter that could widen it.
   */
  /**
   * The operator's own book, by kind.
   *
   * Truncated is carried rather than inferred from the length: a list that stops at a round
   * number without saying so reads as the whole book.
   */
  browse(type: SubjectType): Promise<{ subjects: SearchResult[]; truncated: boolean }> {
    return request<{ subjects: SearchResult[]; truncated: boolean }>(
      `/api/v1/tix/subjects?type=${type}`,
    );
  },

  search(query: string): Promise<SearchResult[]> {
    return request<SearchResult[]>(
      `/api/v1/tix/search?q=${encodeURIComponent(query)}`,
    );
  },

  subject(subjectId: string): Promise<SubjectProfile> {
    return request<SubjectProfile>(`/api/v1/tix/subjects/${subjectId}`);
  },

  /**
   * Turns a delivered batch into records.
   *
   * <p>Separate from publishing on purpose: publishing accepts the delivery, this makes the people
   * in it visible to every other operator on the exchange.
   */
  deriveImport(batchId: string, dunningEvidence: boolean): Promise<DerivationReport> {
    return request<DerivationReport>(`/api/v1/tix/imports/${batchId}/derive`, {
      method: "POST",
      body: JSON.stringify({ dunningEvidence }),
    });
  },

  /**
   * Withdraws a delivery and removes the records it created.
   *
   * <p>On the tix side rather than beside the other batch actions, because it is the undo of
   * deriveImport rather than a change to the file: the batch is retracted and every record it
   * produced is deleted. Refused while any of those records is under dispute.
   */
  revertImport(batchId: string, reason: string): Promise<{
    rows: number;
    recordsRemoved: number;
  }> {
    return request(`/api/v1/tix/imports/${batchId}/revert`, {
      method: "POST",
      body: JSON.stringify({ reason }),
    });
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

  /**
   * Closes a case the person is no longer pursuing, which is not a decision about it.
   *
   * Any suppression it caused is lifted server-side, or a dispute somebody abandoned would hold
   * true records out of the exchange forever.
   */
  withdraw(id: string, note: string): Promise<SubjectRequest> {
    return request<SubjectRequest>(`/api/v1/tix/subject-requests/${id}/withdraw`, {
      method: "POST",
      body: JSON.stringify({ note }),
    });
  },
};
