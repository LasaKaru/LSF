package lk.yathra.booking.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lk.yathra.booking.trip.TripSnapshotDtos.Coach;
import lk.yathra.booking.trip.TripSnapshotDtos.PublishTripRequest;
import lk.yathra.booking.trip.TripSnapshotDtos.Stop;

/**
 * Builds the seeded up-country route as a publishable trip.
 *
 * <p>Uses the real 25-stop list and real distances so that tests exercise the same coordinates as the
 * demo and the documentation -- Fort is seq 1, Kandy is seq 9, Badulla is seq 25. Tests that assert
 * "[1,9) then [9,25)" therefore mean exactly what the brief means.
 */
public final class TripFixture {

    /** code, seq, cumulative km -- the seeded MAIN_UPCOUNTRY route. */
    private static final Object[][] STOPS = {
        {"CMB", 1, 0.00}, {"RGM", 2, 13.00}, {"GMP", 3, 29.00}, {"VYG", 4, 38.00},
        {"PGW", 5, 74.00}, {"RBK", 6, 84.00}, {"KDG", 7, 110.00}, {"PDN", 8, 116.00},
        {"KDY", 9, 121.00}, {"GPL", 10, 133.00}, {"NWP", 11, 147.00}, {"HAT", 12, 178.00},
        {"KTG", 13, 186.00}, {"TWL", 14, 194.00}, {"NAN", 15, 210.00}, {"AMB", 16, 222.00},
        {"PTP", 17, 227.00}, {"OHY", 18, 238.00}, {"IDG", 19, 250.00}, {"HPT", 20, 258.00},
        {"DTW", 21, 265.00}, {"BWL", 22, 272.00}, {"ELA", 23, 281.00}, {"DMD", 24, 288.00},
        {"BDL", 25, 292.00}
    };

    private TripFixture() {}

    public static PublishTripRequest upCountryTrip(LocalDate serviceDate) {
        return upCountryTrip(UUID.randomUUID(), serviceDate, 3, 4);
    }

    /**
     * @param rows seat rows in the single reserved coach; kept small in tests so publication is fast
     * @param columns seats across
     */
    public static PublishTripRequest upCountryTrip(
            UUID trainId, LocalDate serviceDate, int rows, int columns) {

        Instant departs = serviceDate.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().plus(6, ChronoUnit.HOURS);

        List<Stop> stops = new ArrayList<>();
        for (Object[] s : STOPS) {
            String code = (String) s[0];
            int seq = (Integer) s[1];
            BigDecimal km = BigDecimal.valueOf((Double) s[2]);
            stops.add(
                    new Stop(
                            seq,
                            UUID.nameUUIDFromBytes(code.getBytes()),
                            code,
                            code,
                            null,
                            null,
                            km,
                            departs.plus(seq * 20L, ChronoUnit.MINUTES),
                            departs.plus(seq * 20L + 2, ChronoUnit.MINUTES)));
        }

        ObjectMapper mapper = new ObjectMapper();
        var layout = mapper.createObjectNode();
        layout.put("rows", rows);
        layout.put("columns", columns);
        layout.put("aisleAfterColumn", 2);
        var pattern = layout.putArray("seatPattern");
        for (int c = 0; c < columns; c++) {
            pattern.add(String.valueOf((char) ('A' + c)));
        }
        var windows = layout.putArray("windowColumns");
        windows.add(1);
        windows.add(columns);
        layout.put("facing", "FORWARD");
        layout.putArray("blanks");

        List<Coach> coaches =
                List.of(
                        new Coach("R1", "RESERVED", "SECOND", true, 1, layout, null),
                        // An unreserved coach is included so tests cover the rule that it never
                        // participates in seat-level inventory.
                        new Coach("U1", "UNRESERVED", "THIRD", false, 2, null, 100));

        return new PublishTripRequest(
                trainId,
                "1005",
                "Podi Menike",
                "MAIN_UPCOUNTRY",
                serviceDate,
                "UP",
                departs,
                departs.plus(9, ChronoUnit.HOURS),
                departs.minus(30, ChronoUnit.MINUTES),
                stops,
                coaches);
    }
}
