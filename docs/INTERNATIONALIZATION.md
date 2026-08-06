# Dival People — Internationalization

## Launch languages
English and French. English is default; French must have complete feature parity.

## Rules
- All user-facing strings use translation keys.
- No text is hard-coded in components.
- Use locale-aware dates, times, numbers, currencies, percentages, names, and addresses.
- Store timestamps in UTC; display in user or tenant timezone.
- Store ISO currency code with every monetary amount.

## Suggested structure
```text
/locales
  /en
    common.json
    employees.json
    payroll.json
  /fr
    common.json
    employees.json
    payroll.json
```

## User and tenant configuration
Users choose preferred language. Tenants configure default and supported languages, timezone, currency, holiday calendars, required documents, contract types, and country rules.

## Translation workflow
English source, professional French translation, terminology review, UI testing, approval. Machine translation may assist but is not final authority for legal, payroll, employee-relations, insurance, or lending content.

## Core glossary
| English | French |
|---|---|
| Employee | Employé / Collaborateur |
| Leave Request | Demande de congé |
| Payroll | Paie |
| Payslip | Bulletin de paie |
| Department | Département |
| Job Requisition | Demande de recrutement |
| Performance Review | Évaluation de performance |
| Salary Advance | Avance sur salaire |
| Fraud Alert | Alerte de fraude |
| Under Review | En cours d'examen |

## Documents
Contracts, offers, payslips, HR letters, acknowledgments, and financial disclosures may be generated in English, French, or bilingual format.

## AI assistant
Detect preferred language, answer consistently in that language, preserve approved terminology, cite policy sources when possible, and respect permissions.

## Tests
Missing keys, French text expansion, accents in search/sort, date/currency formats, emails, notifications, and generated documents.
