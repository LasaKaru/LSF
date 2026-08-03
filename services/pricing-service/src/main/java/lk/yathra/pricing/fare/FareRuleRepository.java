package lk.yathra.pricing.fare;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.yathra.common.api.ApiException;
import lk.yathra.common.api.ErrorCode;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Loads the effective fare rule set. */
@Repository
public class FareRuleRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public FareRuleRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The rule set in force right now.
     *
     * <p>Selected by effective date rather than by a "current" flag, so publishing future fares is
     * just an INSERT with a future {@code effective_from}, and rolling a bad change back is an UPDATE
     * to a date rather than a deployment.
     */
    public FareRules loadEffective() {
        var rows =
                jdbc.query(
                        """
                        SELECT id, version, currency, rounding_minor, minimum_fare_minor
                          FROM fare_rule_set
                         WHERE is_published
                           AND effective_from <= now()
                           AND (effective_to IS NULL OR effective_to > now())
                         ORDER BY effective_from DESC
                         LIMIT 1
                        """,
                        Map.of(),
                        (rs, i) ->
                                new Object[] {
                                    rs.getObject("id", UUID.class),
                                    rs.getString("version"),
                                    rs.getString("currency"),
                                    rs.getLong("rounding_minor"),
                                    rs.getLong("minimum_fare_minor")
                                });

        if (rows.isEmpty()) {
            throw new ApiException(
                    ErrorCode.INTERNAL_ERROR, "No fare rule set is currently effective.");
        }

        Object[] row = rows.get(0);
        UUID ruleSetId = (UUID) row[0];
        Map<String, Object> params = Map.of("id", ruleSetId);

        var bands =
                jdbc.query(
                        "SELECT band_order, lower_km, upper_km, rate_per_km, label FROM fare_band "
                                + "WHERE rule_set_id = :id ORDER BY band_order",
                        params,
                        (rs, i) ->
                                new FareRules.Band(
                                        rs.getInt("band_order"),
                                        rs.getBigDecimal("lower_km"),
                                        rs.getBigDecimal("upper_km"),
                                        rs.getBigDecimal("rate_per_km"),
                                        rs.getString("label")));

        var classMultipliers =
                jdbc.query(
                        "SELECT class_code::text AS code, multiplier FROM fare_class_multiplier WHERE rule_set_id = :id",
                        params,
                        (rs, i) -> new FareRules.Multiplier(rs.getString("code"), rs.getBigDecimal("multiplier")));

        var coachMultipliers =
                jdbc.query(
                        "SELECT coach_type::text AS code, multiplier FROM fare_coach_multiplier WHERE rule_set_id = :id",
                        params,
                        (rs, i) -> new FareRules.Multiplier(rs.getString("code"), rs.getBigDecimal("multiplier")));

        var scenic =
                jdbc.query(
                        "SELECT route_code, from_station_code, to_station_code, uplift, label "
                                + "FROM fare_scenic_segment WHERE rule_set_id = :id",
                        params,
                        (rs, i) ->
                                new FareRules.ScenicSegment(
                                        rs.getString("route_code"),
                                        rs.getString("from_station_code"),
                                        rs.getString("to_station_code"),
                                        rs.getBigDecimal("uplift"),
                                        rs.getString("label")));

        var demand =
                jdbc.query(
                        "SELECT lower_occupancy_pct, upper_occupancy_pct, multiplier FROM fare_demand_tier "
                                + "WHERE rule_set_id = :id ORDER BY lower_occupancy_pct",
                        params,
                        (rs, i) ->
                                new FareRules.DemandTier(
                                        rs.getBigDecimal("lower_occupancy_pct"),
                                        rs.getBigDecimal("upper_occupancy_pct"),
                                        rs.getBigDecimal("multiplier")));

        var advance =
                jdbc.query(
                        "SELECT min_days_ahead, max_days_ahead, discount FROM fare_advance_tier "
                                + "WHERE rule_set_id = :id ORDER BY min_days_ahead DESC",
                        params,
                        (rs, i) ->
                                new FareRules.AdvanceTier(
                                        rs.getInt("min_days_ahead"),
                                        (Integer) rs.getObject("max_days_ahead"),
                                        rs.getBigDecimal("discount")));

        return new FareRules(
                (String) row[1],
                (String) row[2],
                (Long) row[3],
                (Long) row[4],
                bands,
                classMultipliers,
                coachMultipliers,
                scenic,
                demand,
                advance);
    }

    /** Advance-purchase discount for a departure {@code daysAhead} away. */
    public static BigDecimal advanceDiscount(FareRules rules, long daysAhead) {
        return rules.advanceTiers().stream()
                .filter(t -> daysAhead >= t.minDaysAhead())
                .filter(t -> t.maxDaysAhead() == null || daysAhead <= t.maxDaysAhead())
                .map(FareRules.AdvanceTier::discount)
                .findFirst()
                .orElse(BigDecimal.ZERO);
    }

    public static List<FareRules.ScenicSegment> scenicFor(FareRules rules, String routeCode) {
        return rules.scenicSegments().stream()
                .filter(s -> s.routeCode().equalsIgnoreCase(routeCode))
                .toList();
    }
}
