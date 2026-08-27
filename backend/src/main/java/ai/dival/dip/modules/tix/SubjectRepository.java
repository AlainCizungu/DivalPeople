package ai.dival.dip.modules.tix;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SubjectRepository extends JpaRepository<Subject, UUID> {

    /**
     * The subject holding a national document.
     *
     * <p>Named for what it does now that identifiers have scopes. It used to be
     * {@code findByIdentifier} and to match any identifier at all, which quietly became wrong the
     * day an operator's own account number became a kind of identifier: the same call would have
     * resolved a rival's customer to whoever asked. Operator-scoped lookups go through
     * {@link SubjectIdentifierRepository#locate}, which requires the operator asking.
     */
    @Query("select distinct i.subject from SubjectIdentifier i "
            + "where i.identifierType = :type and i.normalizedValue = :value "
            + "and i.ownerTenantId is null")
    Optional<Subject> findByNationalIdentifier(
            @Param("type") IdentifierType type, @Param("value") String value);

    List<Subject> findByNormalizedName(String normalizedName);

    /**
     * Subjects no operator holds a record against any more.
     *
     * <p>The tail of erasure. A subject is in the exchange only to carry debt records, so once the
     * last one is gone the name, date of birth and identity documents have nothing justifying
     * them. Leaving them behind is the worst outcome available: personal data with no lawful
     * basis and no record explaining why it is held.
     *
     * <p><strong>Must be called in exchange mode.</strong> The NOT EXISTS is evaluated against
     * tix_debt_record, which carries row-level security. Outside exchange mode a caller sees only
     * its own tenant's records, so a subject that three other operators still hold records against
     * looks orphaned — and this query feeds a delete. Run it bound to one tenant and it erases
     * almost everybody in the registry, silently, on a schedule, at two in the morning.
     *
     * <p>That is not a hypothetical: the first version of the purge called this outside any tenant
     * binding at all, where app_current_tenant() is null, every policy comparison is false, and
     * the subquery returns nothing for every subject alive. The caller is what makes this safe,
     * which is a fragile arrangement, so it is stated here as loudly as it can be stated.
     */
    /**
     * <p><strong>Accounts count as well as debts</strong>, and forgetting that was a real defect
     * rather than a hypothetical one. When {@code tix_relationship} arrived, a subject with a
     * spotless payment history and no adverse record satisfied "no debt records" and was swept as
     * an orphan — which is exactly the company DIP built the lifecycle model to be able to
     * describe. The foreign key turned a silent erasure into a loud one, and five purge tests went
     * red; without it the sweep would have deleted good customers nightly and nothing would have
     * said so.
     */
    @Query("select s from Subject s where not exists "
            + "(select 1 from DebtRecord d where d.subject.id = s.id) "
            + "and not exists "
            + "(select 1 from Relationship r where r.subject.id = s.id)")
    List<Subject> findWithNothingHeldAgainstThem();
}
