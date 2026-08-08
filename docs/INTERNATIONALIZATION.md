# DIP — Internationalization

## Initial Languages
- English (en)
- French (fr)

Both are first-class.

## Rules
- No hard-coded user-facing strings.
- Translation keys live in centralized locale resources.
- Backend error codes are language-neutral; UI translates messages.
- Store user locale.
- AI assistant follows user locale.
- Emails/reports use selected locale.
- Dates/numbers/currency formatted by locale.
- Canonical database values are not translated.

## DRC Considerations
The system must support local operational realities:
- inconsistent identifiers;
- organization-name variants;
- multilingual names;
- variable address quality;
- phone-number normalization;
- multiple currencies where required;
- institution-specific customer identifiers.

Identity confidence must never be represented as certainty when source data is incomplete.
