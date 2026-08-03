-- ===========================================================================
-- Seed: fare rule set 2026.08.01
--
-- !! ILLUSTRATIVE FIGURES !!  Internally consistent and chosen to demonstrate
-- the model's behaviour. Must be calibrated against official Sri Lanka
-- Railways tariff schedules and approved by the department before deployment.
-- Worked derivation: docs/05 §5.
-- ===========================================================================

INSERT INTO fare_rule_set (id, version, currency, rounding_minor, minimum_fare_minor,
                           effective_from, is_published)
VALUES ('d0000000-0000-4000-8000-000000000001', '2026.08.01', 'LKR',
        1000,   -- round to the nearest LKR 10
        2000,   -- minimum fare LKR 20
        '2026-01-01T00:00:00Z', TRUE)
ON CONFLICT (version) DO NOTHING;

-- --- Telescopic bands (2nd class reference rates) ---------------------------
-- Band multipliers 1.00 / 0.85 / 0.70 applied to a 4.30 LKR/km base rate.
INSERT INTO fare_band (rule_set_id, band_order, lower_km, upper_km, rate_per_km, label)
VALUES
    ('d0000000-0000-4000-8000-000000000001', 1,   0.00,  50.00, 4.3000, 'First 50 km @ 4.30'),
    ('d0000000-0000-4000-8000-000000000001', 2,  50.00, 150.00, 3.6550, 'Next 100 km @ 3.655'),
    ('d0000000-0000-4000-8000-000000000001', 3, 150.00,   NULL, 3.0100, 'Beyond 150 km @ 3.010')
ON CONFLICT (rule_set_id, band_order) DO NOTHING;

-- --- Class multipliers ------------------------------------------------------
INSERT INTO fare_class_multiplier (rule_set_id, class_code, multiplier) VALUES
    ('d0000000-0000-4000-8000-000000000001', 'FIRST',       1.6000),
    ('d0000000-0000-4000-8000-000000000001', 'SECOND',      1.0000),
    ('d0000000-0000-4000-8000-000000000001', 'THIRD',       0.7000),
    ('d0000000-0000-4000-8000-000000000001', 'OBSERVATION', 1.0000)
ON CONFLICT (rule_set_id, class_code) DO NOTHING;

-- --- Coach multipliers: the fare-side fix ----------------------------------
-- RESERVED is 1.00. The ~2x surcharge existed only to cover the seat sitting
-- empty for the rest of the route; the seat now resells, so the surcharge has
-- no economic justification and keeping it would be indefensible.
INSERT INTO fare_coach_multiplier (rule_set_id, coach_type, multiplier) VALUES
    ('d0000000-0000-4000-8000-000000000001', 'RESERVED',    1.0000),
    ('d0000000-0000-4000-8000-000000000001', 'UNRESERVED',  0.8000),
    ('d0000000-0000-4000-8000-000000000001', 'OBSERVATION', 1.3500),
    ('d0000000-0000-4000-8000-000000000001', 'FIRST_AC',    1.0000)
ON CONFLICT (rule_set_id, coach_type) DO NOTHING;

-- --- Scenic premium ---------------------------------------------------------
-- Nanu Oya -> Ella, the internationally famous stretch and the one with
-- genuine willingness to pay. Charged only on kilometres actually travelled
-- within it, so a Fort->Kandy passenger pays nothing.
INSERT INTO fare_scenic_segment (rule_set_id, route_code, from_station_code,
                                 to_station_code, uplift, label)
SELECT 'd0000000-0000-4000-8000-000000000001', 'MAIN_UPCOUNTRY', 'NAN', 'ELA', 0.1500,
       'Nanu Oya - Ella scenic section'
WHERE NOT EXISTS (
    SELECT 1 FROM fare_scenic_segment
     WHERE rule_set_id = 'd0000000-0000-4000-8000-000000000001'
       AND from_station_code = 'NAN' AND to_station_code = 'ELA');

-- --- Demand tiers (disabled by default; DEMAND_PRICING_ENABLED=false) -------
-- Driven by SEGMENT occupancy, not train occupancy: a train that is full to
-- Kandy and empty afterwards should price those two stretches differently.
-- That is only expressible at all because inventory is now segment-granular.
INSERT INTO fare_demand_tier (rule_set_id, lower_occupancy_pct, upper_occupancy_pct, multiplier)
SELECT 'd0000000-0000-4000-8000-000000000001', v.lo, v.hi, v.m
  FROM (VALUES (0.00, 40.00, 0.9000), (40.00, 70.00, 1.0000),
               (70.00, 90.00, 1.1500), (90.00, NULL::numeric, 1.4000)
       ) AS v(lo, hi, m)
 WHERE NOT EXISTS (SELECT 1 FROM fare_demand_tier
                    WHERE rule_set_id = 'd0000000-0000-4000-8000-000000000001');

-- --- Advance purchase tiers -------------------------------------------------
INSERT INTO fare_advance_tier (rule_set_id, min_days_ahead, max_days_ahead, discount)
SELECT 'd0000000-0000-4000-8000-000000000001', v.lo, v.hi, v.d
  FROM (VALUES (14, NULL::int, 0.1000), (7, 13, 0.0500), (0, 6, 0.0000)
       ) AS v(lo, hi, d)
 WHERE NOT EXISTS (SELECT 1 FROM fare_advance_tier
                    WHERE rule_set_id = 'd0000000-0000-4000-8000-000000000001');
