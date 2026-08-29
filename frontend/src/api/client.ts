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
  /** Who owes it. The list used to show amounts against a bare uuid. */
  subjectName: string;
  subjectType: SubjectType;
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
  /**
   * When this operator's own file on the subject last changed, or null.
   *
   * Scoped to `records` above, which are the caller's. How recently a *competitor* touched its
   * file is the "never since when" the exchange refuses, and it does not become acceptable by
   * being rendered as "2 days ago".
   */
  lastUpdatedAt: string | null;
};

/**
 * One named operator's position, when the deployment discloses them.
 *
 * **Empty in the shipped configuration.** The exchange answers with a count and never with a list;
 * see `DisclosureProperties` on the server. A screen must not infer "nobody else reports this
 * company" from an empty array — `contributorsWithheld` is what answers that.
 */
export type Contributor = {
  institution: string;
  /** Null when naming is on and pricing is off, and null for a position that is fully settled. */
  owed: string | null;
  currency: string | null;
  records: number;
};

export type SubjectSignal =
  | "DEFAULTED_WITH_YOU_BEFORE"
  | "OBLIGATION_OLDER_THAN_A_YEAR"
  | "REPORTED_BY_SEVERAL_INSTITUTIONS"
  | "AN_IDENTIFIER_IS_REUSED"
  | "SOME_RECORDS_ARE_CONTESTED"
  | "NO_NATIONAL_DOCUMENT_ON_FILE"
  | "NO_IDENTIFIER_CONFLICT"
  | "NOTHING_OUTSTANDING_IN_YOUR_BOOK"
  | "FRAUD_NOT_ASSESSED";

export type SubjectEventCode =
  | "OBLIGATION_FELL_DUE"
  | "OBLIGATION_SETTLED"
  | "RECORD_CONTESTED";

export type SubjectEvent = {
  on: string;
  code: SubjectEventCode;
  detail: string | null;
};

/**
 * The overview figures.
 *
 * **Every one of these is the caller's own except `institutionCount`.** The obvious caption is
 * "total known exposure" and it would be read as the market's; what the platform can total is the
 * asker's book, and what it can put beside it is how many institutions report the same subject.
 */
export type Subject360Overview = {
  /** What *you* are owed. Null when your own records are in more than one currency. */
  yourExposure: string | null;
  currency: string | null;
  yourRecords: number;
  /**
   * Whether an obligation is unpaid right now. A boolean, not a count.
   *
   * `uq_tix_debt_open_per_operator` is unique on `(tenant_id, subject_id) WHERE status =
   * 'OUTSTANDING'` — one operator holds at most one open obligation against one subject, so a
   * count would be 0 or 1 forever while reading as though it could be 4.
   */
  hasOutstanding: boolean;
  /** How many previously fell due and were paid. Unlike open accounts, not bounded at one. */
  settledRecords: number;
  contestedRecords: number;
  /** -1 when nothing is unpaid. */
  oldestUnpaidDays: number;
  institutionCount: number;
  /** -1 when the file has never been updated. Your own file only. */
  daysSinceUpdate: number;
  /** Null unless the deployment discloses amounts, and null when any contributor's is withheld. */
  marketExposure: string | null;
};

export type Subject360 = {
  viewVersion: string;
  assembledAt: string;
  subjectId: string;
  name: string;
  subjectType: SubjectType;
  identifiers: { type: IdentifierType; value: string }[];
  /** Null when the exchange would not confirm the identity. */
  indicator: RiskIndicator | null;
  outcome: InquiryOutcome;
  overview: Subject360Overview;
  signals: SubjectSignal[];
  contributors: Contributor[];
  /**
   * True when the list is empty *because this platform does not name them* — which is the shipped
   * state. An empty list is also what a subject nobody else reports looks like, so the screen
   * cannot tell the two apart without this.
   */
  contributorsWithheld: boolean;
  timeline: SubjectEvent[];
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
  /**
   * Sector, city and street, or null where the delivery carries none.
   *
   * All three optional and expected to be null for the two real deliveries, which predate the
   * published template. An operator who adopts it names them here and the resolution queue gains
   * three signals that have read "never available" on every case it has ever shown.
   */
  sectorColumn: string | null;
  cityColumn: string | null;
  addressColumn: string | null;
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
      sectorColumn: string | null;
      cityColumn: string | null;
      addressColumn: string | null;
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
/**
 * The exchange as a whole, shown to everybody in it.
 *
 * <p>Every field is a count and the shape has nowhere to put a name — that is enforced on the
 * server, where it matters, and mirrored here so the same property is visible from the screen.
 * `sectors` reads 0 until an operator maps a sector column on an import, which is why the strip
 * distinguishes "none recorded" from "none".
 */
export type Network = {
  institutions: number;
  contributing: number;
  subjects: number;
  sectors: number;
  sharedSubjects: number;
  declaredToday: number;
};

export type Overview = {
  asOf: string;
  /** What this operator calls itself. Null only if its tenant row has gone. */
  organisation: string | null;
  network: Network;
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

/**
 * One month of what an institution did.
 *
 * `month` is ISO `2026-08`. Formatted where the words are rather than on the server, because a
 * month name is language and the server sends none.
 */
export type ExecutiveMonth = {
  month: string;
  declared: number;
  inquiries: number;
  refused: number;
};

/**
 * The briefing for somebody who does not work the queues.
 *
 * Same null-versus-zero rule as {@link Overview}. `activity` is thirteen months, oldest first,
 * with the empty months present as zeroes — a series that skipped its quiet months would draw a
 * straight line through a summer when nothing happened and read as steady work.
 */
export type ExecutiveBriefing = {
  asOf: string;
  book: {
    total: number;
    outstanding: number;
    contested: number;
    settled: number;
    expiringSoon: number;
    awaitingErasure: number;
  } | null;
  activity: ExecutiveMonth[] | null;
  rights: { raised: number; inTime: number; late: number } | null;
};

export const executiveApi = {
  load(): Promise<ExecutiveBriefing> {
    return request<ExecutiveBriefing>("/api/v1/executive");
  },
};

/**
 * A thing an answer does not contain, and why.
 *
 * Attached to answers assembled from an evidence pack — today, "why is this company risky". Four
 * always apply; the last two only sometimes. `NO_MODEL_PRODUCED_THIS` is first on purpose: the menu
 * entry says "AI analyst" and a reader is entitled to know that nothing in front of them was
 * generated.
 */
export type PackAbsence =
  | "NO_MODEL_PRODUCED_THIS"
  | "OTHER_OPERATORS_ARE_NOT_NAMED"
  | "OTHER_OPERATORS_AMOUNTS_ARE_NOT_DISCLOSED"
  | "CONTESTED_RECORDS_ARE_WITHHELD"
  | "NO_NATIONAL_DOCUMENT_IS_HELD"
  | "THE_EXCHANGE_WOULD_NOT_CONFIRM_IDENTITY";

export type AskIntent =
  | "EXPOSURE_ABOVE"
  | "EXPOSURE_ABOVE_MULTI_INSTITUTION"
  | "WHY_RISKY"
  | "WHAT_CHANGED"
  | "PRIORITISE"
  | "UNSUPPORTED";

/**
 * What the analyst understood, shown before the answer.
 *
 * The failure mode of a natural-language front end is not a wrong number, it is a right number to
 * a different question. `byModel` says whether a language model read the question or the rules did,
 * so a screen full of rule-based readings is a visible symptom rather than a silent degradation.
 */
export type AskInterpretation = {
  intent: AskIntent;
  minAmount: string | null;
  days: number;
  subjectName: string | null;
  byModel: boolean;
};

/** A number, counted from rows by the platform. Never produced by a model. */
export type AskFigure = { code: string; value: string; unit: string | null };

export type AskCompany = {
  subjectId: string;
  name: string;
  /** What you are owed, not what the market is. */
  owed: string;
  oldestDays: number;
  records: number;
};

/**
 * An answer.
 *
 * `narrative` is the model's phrasing of `figures` and is decoration over numbers that are already
 * correct — null whenever narration is off or the model was unreachable. `inquiryCost` is what
 * screening across institutions *would* cost; it is quoted rather than spent.
 */
export type AskAnswer = {
  understood: AskInterpretation;
  figures: AskFigure[];
  companies: AskCompany[];
  inquiryCost: number;
  narrative: string | null;
  narratedBy: string | null;
  /** What the answer deliberately does not contain. Empty for questions that have no such list. */
  caveats: PackAbsence[];
};

export const analystApi = {
  /**
   * A question in words.
   *
   * POST because the question is free text a user typed and a query string would write it into
   * every access log on the way — and a question can name a company.
   */
  ask(question: string): Promise<AskAnswer> {
    return request<AskAnswer>("/api/v1/analyst/ask", {
      method: "POST",
      body: JSON.stringify({ question }),
    });
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
  /**
   * The account's identifier at the identity provider.
   *
   * Exposed only to a tenant administrator, only for their own organisation, and only because a
   * screen that can disable somebody needs a way to say which somebody. See AccessService.Member.
   */
  userId: string;
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
  | "SHARED_REGISTER_NUMBER"
  | "SHARED_NATIONAL_IDENTIFIER"
  | "SAME_SUBJECT_TYPE"
  | "SAME_NATIONALITY"
  | "SAME_DATE_OF_BIRTH"
  | "DIFFERENT_ACCOUNT_REFERENCES"
  | "SAME_SECONDARY_PHONE"
  | "SAME_SECTOR"
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
  /** The indicator at the last sweep. Null when never checked, or when the exchange withheld it. */
  lastScore: number | null;
  lastCheckedAt: string | null;
  /** Null for an unfiled watch, which is a real state and not an error. */
  watchlistId: string | null;
  watchlistName: string | null;
};

/**
 * A named group of companies one institution watches.
 *
 * `id` and `name` are null for the unfiled pseudo-group — watches nobody has put in a list. That is
 * a real state rather than a group, and the screen names it rather than hiding those rows.
 */
export type WatchlistGroup = {
  id: string | null;
  name: string | null;
  purpose: string | null;
  watched: number;
};

export type AlertSeverity = "MATERIAL" | "NOTABLE" | "INFORMATIONAL";

/**
 * Something changed about a watched company, with what it was before.
 *
 * Carries no amount and names no institution. Which participant began reporting and how much they
 * are owed are the exchange's standing refusals, and neither becomes disclosable because it arrived
 * as a change rather than as an answer.
 *
 * `previousScore` and `currentScore` are null when the exchange withheld the indicator — it does
 * that for any answer it is not confident about — so a null is "not known then", never "was zero".
 */
export type MonitoringAlert = {
  id: string;
  subjectId: string;
  name: string;
  raisedAt: string;
  severity: AlertSeverity;
  previousOutcome: InquiryOutcome | null;
  currentOutcome: InquiryOutcome;
  previousInstitutions: number | null;
  currentInstitutions: number;
  previousScore: number | null;
  currentScore: number | null;
  acknowledgedAt: string | null;
  acknowledgementNote: string | null;
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
  /**
   * One night's slice.
   *
   * `checked` is less than `watched` when the list is larger than a slice — every check is a
   * charged inquiry, so a big watchlist takes several nights. The screen says so rather than
   * implying a full pass.
   */
  groups(): Promise<WatchlistGroup[]> {
    return request<WatchlistGroup[]>("/api/v1/tix/watchlists");
  },

  createGroup(name: string, purpose: string): Promise<WatchlistGroup> {
    return request<WatchlistGroup>("/api/v1/tix/watchlists", {
      method: "POST",
      body: JSON.stringify({ name, purpose }),
    });
  },

  /** `watchlistId: null` unfiles the watch. It does not stop watching — that is `unwatch`. */
  file(watchId: string, watchlistId: string | null): Promise<void> {
    return request<void>(`/api/v1/tix/watchlist/${watchId}/file`, {
      method: "POST",
      body: JSON.stringify({ watchlistId }),
    });
  },

  alerts(): Promise<MonitoringAlert[]> {
    return request<MonitoringAlert[]>("/api/v1/tix/monitoring/alerts");
  },

  acknowledge(alertId: string, note: string): Promise<MonitoringAlert> {
    return request<MonitoringAlert>(
      `/api/v1/tix/monitoring/alerts/${alertId}/acknowledge`,
      { method: "POST", body: JSON.stringify({ note }) },
    );
  },

  sweep(): Promise<{ watched: number; checked: number; changed: number }> {
    return request<{ watched: number; checked: number; changed: number }>(
      "/api/v1/tix/watchlist/sweep",
      { method: "POST" },
    );
  },
};

export const anomaliesApi = {
  behaviour(): Promise<BehaviourReport> {
    return request<BehaviourReport>("/api/v1/anomalies/behaviour");
  },
};

export type MembershipOptions = {
  /** False when this deployment has no identity-provider service account configured. */
  available: boolean;
  /** Every role this institution may assign. Never includes the platform's own. */
  grantable: string[];
  /**
   * True when the invitation is emailed as a link rather than shown as a password.
   *
   * Read before the form is submitted, so it can say what is about to happen. An administrator
   * who expects to copy a password and instead gets "check their inbox" has no way to know
   * whether that worked.
   */
  emailInvites: boolean;
};

export type Invitation = {
  userId: string;
  email: string;
  roles: string[];
  /**
   * Shown once, retrievable never — and **null when the invitation was emailed**, because on that
   * path no password is ever set on the account.
   */
  password: string | null;
  /** True when a link was sent, so the administrator has nothing to pass on. */
  emailed: boolean;
};

export const accessApi = {
  load(): Promise<Access> {
    return request<Access>("/api/v1/access");
  },

  membershipOptions(): Promise<MembershipOptions> {
    return request<MembershipOptions>("/api/v1/users/members/options");
  },

  invite(body: { email: string; displayName: string; roles: string[] }): Promise<Invitation> {
    return request<Invitation>("/api/v1/users/members", {
      method: "POST",
      body: JSON.stringify(body),
    });
  },

  setMemberRoles(userId: string, roles: string[]): Promise<void> {
    return request<void>(`/api/v1/users/members/${userId}/roles`, {
      method: "PUT",
      body: JSON.stringify({ roles }),
    });
  },

  setMemberActive(userId: string, active: boolean): Promise<void> {
    return request<void>(`/api/v1/users/members/${userId}/active`, {
      method: "PUT",
      body: JSON.stringify({ active }),
    });
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

  /**
   * Everything the platform can say about one company.
   *
   * **Costs an inquiry**, so `purpose` is required and there is no default. A screen that supplied
   * a plausible one — "profile view" — would put the same sentence on every row of the audit trail
   * and empty it of its content.
   */
  profile360(subjectId: string, purpose: string): Promise<Subject360> {
    return request<Subject360>(
      `/api/v1/tix/subjects/${encodeURIComponent(subjectId)}/profile360`
        + `?purpose=${encodeURIComponent(purpose)}`,
    );
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
   * Subjects of one kind, listed.
   *
   * <p><strong>Nothing in this application calls it.</strong> The Businesses and Individuals
   * screens were its only callers and were folded into Records, where the kind is a filter rather
   * than a destination. Kept rather than deleted: `GET /api/v1/tix/subjects?type=` is a working,
   * guarded, tested endpoint, and removing a server capability because one screen stopped using it
   * is a wider decision than the one that was made. Delete both together, or neither.
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
