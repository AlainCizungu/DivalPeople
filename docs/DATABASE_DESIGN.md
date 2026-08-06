# Dival People — Database Design

## Principles
PostgreSQL is the system of record. Every tenant-owned table has `tenant_id`, timestamps, and actor metadata where appropriate. Use migrations, foreign keys, indexes, encrypted sensitive fields, append-only audit logs, and immutable finalized payroll history.

## Core tables

### Platform
- `tenants`
- `tenant_settings`
- `users`
- `roles`
- `permissions`
- `user_roles`
- `role_permissions`

### Organization
- `legal_entities`
- `organizational_units`
- `locations`
- `cost_centers`

### Employees
- `employees`
- `employee_addresses`
- `employee_dependents`
- `emergency_contacts`
- `employee_identifiers`
- `employment_history`

### Contracts and compensation
- `employment_contracts`
- `compensation_records`
- `compensation_components`

### Documents
- `documents`
- `document_versions`

### Recruitment
- `job_requisitions`
- `candidates`
- `applications`
- `interviews`
- `candidate_evaluations`
- `offers`

### Onboarding/offboarding
- `workflow_templates`
- `workflow_instances`
- `workflow_tasks`
- `equipment_assignments`
- `policy_acknowledgments`

### Leave and attendance
- `leave_policies`
- `employee_leave_balances`
- `leave_requests`
- `attendance_records`
- `shifts`
- `employee_shift_assignments`

### Performance and learning
- `performance_cycles`
- `goals`
- `performance_reviews`
- `courses`
- `training_assignments`
- `certifications`

### Payroll
- `payroll_periods`
- `payroll_entries`
- `payroll_entry_components`
- `payslips`
- `payroll_exports`

### Financial services
- `financial_partners`
- `financial_products`
- `consent_records`
- `financial_service_applications`
- `repayment_instructions`
- `partner_transactions`

### Fraud and investigations
- `fraud_alerts`
- `risk_indicators`
- `investigation_cases`
- `investigation_notes`

### Audit and communication
- `audit_events`
- `notifications`
- `notification_templates`
- `webhook_deliveries`

## Required common columns
For tenant-owned tables:
- `id`
- `tenant_id`
- `created_at`
- `updated_at`
- `created_by`
- `updated_by`

## Sensitive data
Encrypt or tokenize national IDs, passports, bank accounts, insurance identifiers, health information, and sensitive financial data.

## Indexing
Composite indexes should generally begin with `tenant_id`, for example:
- `(tenant_id, employee_number)`
- `(tenant_id, status)`
- `(tenant_id, employee_id)`
- `(tenant_id, expiration_date)`
- `(tenant_id, created_at)`

## Data classification
- Public: public job postings
- Internal: organization structure and general policies
- Confidential: employee, recruitment, compensation, performance
- Restricted: IDs, bank data, health data, fraud investigations, authentication records

## Retention
Retention is configurable by country, tenant, record type, and legal requirement. Final payroll and audit history must not be casually deleted.

## Migrations
Use Flyway. Released migrations are immutable. Test migrations against representative data. Destructive changes require explicit approval and rollback planning.
