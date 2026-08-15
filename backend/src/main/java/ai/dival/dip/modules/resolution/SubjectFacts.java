package ai.dival.dip.modules.resolution;

import java.time.LocalDate;
import java.util.Map;
import java.util.Set;

/**
 * Everything about one record that the comparison is allowed to see.
 *
 * <p>Primitives, dates and strings, and not one entity. Same reason as the risk module: the
 * scorer is the part a reviewer will be asked to defend, so it is the part that has to be
 * readable on its own and testable without a database.
 *
 * @param business            whether this is a company rather than a person. Names behave
 *                            completely differently between the two and the scorer needs to know
 * @param normalizedName      the name, already normalised by whoever owns names
 * @param nationality         declared country, or null
 * @param dateOfBirth         for a person, or null
 * @param nationalIdentifiers issued documents, keyed by type. Globally unique in the registry,
 *                            which is what makes agreement here worth so much and disagreement
 *                            worth so much against
 * @param hasAccountReference whether the holding institution numbers this record itself
 * @param sector              line of business, or null where nobody has supplied one. Compared
 *                            loosely and weighed lightly: a great many companies share a sector
 * @param city                city or commune, or null. Compared as an equality, which a street
 *                            cannot be
 * @param streetAddress       street, or null. Agreement is worth a great deal and disagreement
 *                            worth nothing — two clerks type one address two ways at least as
 *                            often as two companies occupy two buildings
 */
public record SubjectFacts(
        boolean business,
        String normalizedName,
        String nationality,
        LocalDate dateOfBirth,
        Map<String, String> nationalIdentifiers,
        boolean hasAccountReference,
        String sector,
        String city,
        String streetAddress) {

    public SubjectFacts {
        if (normalizedName == null) {
            throw new IllegalArgumentException("A record with no name at all cannot be compared");
        }
        nationalIdentifiers = Map.copyOf(nationalIdentifiers);
    }

    /**
     * The types both records carry, which is the only place a disagreement can be found.
     *
     * <p>One record holding an RCCM and the other holding none is not a conflict — it is a gap,
     * and the whole country is full of them. A conflict needs both sides to have spoken.
     */
    Set<String> identifierTypesSharedWith(SubjectFacts other) {
        return nationalIdentifiers.keySet().stream()
                .filter(other.nationalIdentifiers()::containsKey)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
