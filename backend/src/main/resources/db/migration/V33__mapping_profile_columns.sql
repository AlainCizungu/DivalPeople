-- ---------------------------------------------------------------------------
-- Three optional columns a mapping may name: sector, city and street.
--
-- V32 gave a subject somewhere to hold them. This is how they get there from a file, which is the
-- path that matters: the API declaration carries a profile only if an operator's billing system
-- happens to know one, and the published template is what will actually deliver them.
--
-- All three nullable, and that is not laziness. A mapping is defined per source and per version
-- against whatever header the operator actually sent; the two files DIP holds today carry none of
-- these, so requiring them would make every existing source undefinable. An operator adopting the
-- template names the new columns and gets the new signals; one who has not is unaffected.
-- ---------------------------------------------------------------------------

ALTER TABLE source_mapping
    ADD COLUMN sector_column  VARCHAR(200),
    ADD COLUMN city_column    VARCHAR(200),
    ADD COLUMN address_column VARCHAR(200);

COMMENT ON COLUMN source_mapping.sector_column IS
    'Header of the column holding the line of business, or NULL when the delivery carries none.';

-- Deliberately no constraint forbidding these from naming the amount or identifier column, unlike
-- V25's rule for the name and amount. Naming the amount column as the sector produces a subject
-- whose sector reads "1450.00", which is visibly absurd on the review screen and harms nothing;
-- naming it as the identifier produced records that were plausible and wrong, which is why that
-- one is a constraint and this one is not.
