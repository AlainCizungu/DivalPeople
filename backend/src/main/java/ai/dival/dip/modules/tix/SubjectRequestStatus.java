package ai.dival.dip.modules.tix;

/**
 * Where a rights case has got to.
 *
 * <p>Nothing is decided before identity is verified, and that ordering is enforced by a database
 * constraint as well as by this type. Upholding a case suppresses or erases records; doing that
 * on an unverified claim would let anyone silence a debt by asserting they are the debtor.
 */
public enum SubjectRequestStatus {

    /** Opened. Somebody has come forward; nobody has checked who they are yet. */
    RECEIVED,

    /** A member of staff has satisfied themselves this is the right person, and said how. */
    IDENTITY_VERIFIED,

    /** Granted, in whole or in part. The reason says which. */
    UPHELD,

    /** Refused, with grounds the person can read and challenge. */
    REFUSED,

    /** The person did not pursue it. */
    WITHDRAWN
}
