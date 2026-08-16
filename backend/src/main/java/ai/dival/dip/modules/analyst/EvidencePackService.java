package ai.dival.dip.modules.analyst;

import ai.dival.dip.common.error.PolicyRefusedException;
import ai.dival.dip.modules.tix.ExchangeService;
import ai.dival.dip.modules.tix.IdentifierType;
import ai.dival.dip.modules.tix.InquiryRequest;
import ai.dival.dip.modules.tix.InquiryResult;
import ai.dival.dip.modules.tix.SearchService;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Everything the platform can honestly say about one company, and what it cannot.
 *
 * <p>This is the grounding an analyst rests on. No language model is involved — none is configured
 * in this deployment, and the boundary below has to be settled before one is, because the hard part
 * of an AI analyst was never the model.
 *
 * <p><strong>An analyst with a database connection is a disclosure hole.</strong> Every rule that
 * makes this exchange safe lives above SQL: the answer is a count of institutions and never their
 * names, row-level security keeps one operator out of another's records, a disputed record is
 * withheld the moment it is contested. A model handed a connection has none of that, and would
 * answer "which companies does the other operator report" correctly and catastrophically.
 *
 * <p>So the pack is assembled <strong>only from services the caller could have called
 * themselves</strong>, under their own tenant and their own roles. Nothing here is reachable that
 * was not already reachable by a person clicking two screens. That is the whole safety argument,
 * and it is why this class holds no repository.
 *
 * <p><strong>Asking the exchange costs an inquiry.</strong> The pack calls
 * {@link ExchangeService#inquire}, which charges the rate limiter and writes the audit row with the
 * stated purpose. Same decision as the watchlist and for the same reason: a convenience that
 * reached the exchange without spending the allowance would be a way to query it with the throttle
 * off and the trail thinned, and it would look identical on screen.
 *
 * <p><strong>What is missing is listed rather than omitted.</strong> A summary that quietly leaves
 * out what it could not find reads as complete, and this one will eventually be handed to something
 * that generates fluent prose from it. The absences are part of the evidence.
 */
@Service
public class EvidencePackService {

    /**
     * Which rules assembled this pack.
     *
     * <p>Stamped for the same reason the risk model stamps its version: a decision defended in
     * three years' time has to be explainable by what the platform did then, not by what it does
     * now. A printed pack that cannot be traced to its rules is an anecdote.
     */
    public static final String PACK_VERSION = "DIP-EP-1";

    private final SearchService search;
    private final ExchangeService exchange;
    private final Clock clock;

    public EvidencePackService(SearchService search, ExchangeService exchange, Clock clock) {
        this.search = search;
        this.exchange = exchange;
        this.clock = clock;
    }

    /**
     * Assembles the pack for one subject this operator holds a record against.
     *
     * <p>Refuses a subject the operator holds nothing about, and refuses it exactly as the profile
     * screen does — as "not found" rather than "not yours", because the two must be
     * indistinguishable. An analyst that said "not yours" would confirm the subject exists and turn
     * itself into the enumeration tool the search deliberately is not.
     *
     * @param purpose why this is being asked, required. The pack performs an inquiry, and an
     *                inquiry without a stated purpose is the thing the audit trail exists to
     *                prevent
     */
    @Transactional
    public EvidencePack forSubject(UUID subjectId, String purpose, UUID actorId) {
        if (purpose == null || purpose.isBlank()) {
            throw new PolicyRefusedException(
                    "Say why this company is being looked at. Assembling a pack asks the exchange, "
                            + "which is charged and recorded like any other inquiry.");
        }

        // First, and deliberately. It refuses when the operator holds nothing, which stops the
        // exchange being asked about a company this caller has no business asking about — and
        // stops the refusal itself costing them an inquiry.
        SearchService.Profile held = search.profileOf(subjectId, actorId);

        List<InquiryRequest.SubmittedIdentifier> documents = nationalDocuments(held);
        InquiryResult answer = exchange.inquire(
                new InquiryRequest(documents, held.name(), purpose.trim()), actorId);

        return new EvidencePack(PACK_VERSION, clock.instant(), purpose.trim(), held, answer,
                absences(documents, answer));
    }

    /**
     * The documents that identify this company to anybody, not just to us.
     *
     * <p>An account reference is excluded even though the operator holds one and it is a strong
     * identifier by every other measure. It resolves inside the institution that issued it, so
     * asking the exchange with one finds this operator's own record and nothing else — a pack that
     * confirmed itself.
     */
    private static List<InquiryRequest.SubmittedIdentifier> nationalDocuments(
            SearchService.Profile held) {
        List<InquiryRequest.SubmittedIdentifier> documents = new ArrayList<>();
        for (SearchService.Identifier identifier : held.identifiers()) {
            IdentifierType type = identifier.type();
            if (type.isStrong() && !type.isOperatorScoped()) {
                documents.add(new InquiryRequest.SubmittedIdentifier(type, identifier.value()));
            }
        }
        return List.copyOf(documents);
    }

    /**
     * What is not in the pack, and why.
     *
     * <p>Three of these never close — they are the exchange working — and two describe this
     * particular company's file. Keeping them in one list, coded rather than phrased, means the
     * screen can say them in either language and a reader can tell a design decision from a gap in
     * the data.
     */
    private static List<Absence> absences(List<InquiryRequest.SubmittedIdentifier> documents,
                                          InquiryResult answer) {
        List<Absence> absent = new ArrayList<>();
        absent.add(Absence.NO_MODEL_PRODUCED_THIS);
        absent.add(Absence.OTHER_OPERATORS_ARE_NOT_NAMED);
        absent.add(Absence.OTHER_OPERATORS_AMOUNTS_ARE_NOT_DISCLOSED);
        absent.add(Absence.CONTESTED_RECORDS_ARE_WITHHELD);

        if (documents.isEmpty()) {
            absent.add(Absence.NO_NATIONAL_DOCUMENT_IS_HELD);
        }
        if (answer.outcome() == InquiryResult.Outcome.REVIEW_REQUIRED) {
            absent.add(Absence.THE_EXCHANGE_WOULD_NOT_CONFIRM_IDENTITY);
        }
        return List.copyOf(absent);
    }

    /**
     * @param packVersion  which rules assembled this
     * @param assembledAt  when, so a printed pack carries its own age
     * @param purpose      what the caller said they were doing, as recorded in the audit trail
     * @param held         this operator's own file on the company, with the provenance of each
     *                     record — declared here, or derived from a delivery
     * @param exchange     what the exchange answered: an outcome, a set of statuses, how many
     *                     institutions, and the risk indicator with every factor behind it. Never
     *                     an amount and never a name
     * @param absent       what is not here, and why
     */
    public record EvidencePack(String packVersion, Instant assembledAt, String purpose,
                               SearchService.Profile held, InquiryResult exchange,
                               List<Absence> absent) {
    }
}
