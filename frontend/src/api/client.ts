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
