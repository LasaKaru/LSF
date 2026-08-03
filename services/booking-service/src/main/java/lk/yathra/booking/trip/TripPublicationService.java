package lk.yathra.booking.trip;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.yathra.booking.trip.TripSnapshotDtos.PublishTripRequest;
import lk.yathra.booking.trip.TripSnapshotDtos.PublishTripResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Snapshots a trip's topology into booking_db (ADR-005).
 *
 * <p>Publication is <b>idempotent</b>: re-publishing an existing (train, date, direction) returns the
 * existing trip untouched rather than rebuilding it. That matters more than it looks -- rebuilding
 * would delete {@code trip_seat} rows that sold bookings point at, and the whole reason the snapshot
 * exists is that sold inventory must never be disturbed by later edits.
 */
@Service
public class TripPublicationService {

    private static final Logger log = LoggerFactory.getLogger(TripPublicationService.class);

    private final NamedParameterJdbcTemplate jdbc;
    private final SeatMaterialiser seatMaterialiser;

    public TripPublicationService(NamedParameterJdbcTemplate jdbc, SeatMaterialiser seatMaterialiser) {
        this.jdbc = jdbc;
        this.seatMaterialiser = seatMaterialiser;
    }

    @Transactional
    public PublishTripResponse publish(PublishTripRequest req) {
        var existing =
                jdbc.queryForList(
                        """
                        SELECT id FROM trip
                         WHERE train_id = :trainId AND service_date = :serviceDate
                           AND direction = CAST(:direction AS direction_enum)
                        """,
                        new MapSqlParameterSource()
                                .addValue("trainId", req.trainId())
                                .addValue("serviceDate", req.serviceDate())
                                .addValue("direction", req.direction()),
                        UUID.class);

        if (!existing.isEmpty()) {
            UUID tripId = existing.get(0);
            return new PublishTripResponse(
                    tripId, "ALREADY_PUBLISHED", countStops(tripId), countCoaches(tripId), countSeats(tripId));
        }

        UUID tripId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO trip (id, train_id, train_code, train_name_en, route_code, service_date,
                                  direction, status, departs_at, arrives_at, booking_cutoff_at)
                VALUES (:id, :trainId, :trainCode, :trainNameEn, :routeCode, :serviceDate,
                        CAST(:direction AS direction_enum), 'PUBLISHED', :departsAt, :arrivesAt, :cutoffAt)
                """,
                new MapSqlParameterSource()
                        .addValue("id", tripId)
                        .addValue("trainId", req.trainId())
                        .addValue("trainCode", req.trainCode())
                        .addValue("trainNameEn", req.trainNameEn())
                        .addValue("routeCode", req.routeCode())
                        .addValue("serviceDate", req.serviceDate())
                        .addValue("direction", req.direction())
                        .addValue("departsAt", Timestamp.from(req.departsAt()))
                        .addValue("arrivesAt", Timestamp.from(req.arrivesAt()))
                        .addValue("cutoffAt", Timestamp.from(req.bookingCutoffAt())));

        for (var stop : req.stops()) {
            jdbc.update(
                    """
                    INSERT INTO trip_stop (trip_id, stop_sequence, station_id, station_code,
                                           station_name_en, station_name_si, station_name_ta,
                                           distance_km, scheduled_arrival, scheduled_departure)
                    VALUES (:tripId, :seq, :stationId, :code, :nameEn, :nameSi, :nameTa,
                            :distanceKm, :arr, :dep)
                    """,
                    new MapSqlParameterSource()
                            .addValue("tripId", tripId)
                            .addValue("seq", stop.stopSequence())
                            .addValue("stationId", stop.stationId())
                            .addValue("code", stop.stationCode())
                            .addValue("nameEn", stop.nameEn())
                            .addValue("nameSi", stop.nameSi())
                            .addValue("nameTa", stop.nameTa())
                            .addValue("distanceKm", stop.distanceKm())
                            .addValue("arr", stop.scheduledArrival() == null ? null : Timestamp.from(stop.scheduledArrival()))
                            .addValue("dep", stop.scheduledDeparture() == null ? null : Timestamp.from(stop.scheduledDeparture())));
        }

        int seatCount = 0;
        for (var coach : req.coaches()) {
            UUID coachId = UUID.randomUUID();
            JsonNode layout = coach.layout();
            jdbc.update(
                    """
                    INSERT INTO trip_coach (id, trip_id, coach_number, coach_type, class_code,
                                            is_reservable, position_index, layout, capacity)
                    VALUES (:id, :tripId, :number, CAST(:coachType AS coach_type_enum),
                            CAST(:classCode AS class_enum), :reservable, :position,
                            CAST(:layout AS jsonb), :capacity)
                    """,
                    new MapSqlParameterSource()
                            .addValue("id", coachId)
                            .addValue("tripId", tripId)
                            .addValue("number", coach.coachNumber())
                            .addValue("coachType", coach.coachType())
                            .addValue("classCode", coach.classCode())
                            .addValue("reservable", coach.reservable())
                            .addValue("position", coach.positionIndex())
                            .addValue("layout", layout == null || layout.isNull() ? null : layout.toString())
                            .addValue("capacity", coach.capacity()));

            // Unreserved coaches have no seat map -- they are sold by headcount, not by seat, so
            // they never participate in segment inventory.
            if (!coach.reservable()) {
                continue;
            }

            List<SeatMaterialiser.MaterialisedSeat> seats = seatMaterialiser.materialise(layout);
            for (var seat : seats) {
                jdbc.update(
                        """
                        INSERT INTO trip_seat (trip_id, trip_coach_id, seat_label, row_index, column_index,
                                               is_window, is_aisle, facing)
                        VALUES (:tripId, :coachId, :label, :row, :col, :window, :aisle,
                                CAST(:facing AS facing_enum))
                        """,
                        new MapSqlParameterSource()
                                .addValue("tripId", tripId)
                                .addValue("coachId", coachId)
                                .addValue("label", seat.label())
                                .addValue("row", seat.rowIndex())
                                .addValue("col", seat.columnIndex())
                                .addValue("window", seat.window())
                                .addValue("aisle", seat.aisle())
                                .addValue("facing", seat.facing()));
            }
            seatCount += seats.size();
        }

        log.info(
                "trip.published tripId={} train={} date={} direction={} stops={} coaches={} seats={}",
                tripId,
                req.trainCode(),
                req.serviceDate(),
                req.direction(),
                req.stops().size(),
                req.coaches().size(),
                seatCount);

        return new PublishTripResponse(tripId, "PUBLISHED", req.stops().size(), req.coaches().size(), seatCount);
    }

    private int countStops(UUID tripId) {
        return count("trip_stop", tripId);
    }

    private int countCoaches(UUID tripId) {
        return count("trip_coach", tripId);
    }

    private int countSeats(UUID tripId) {
        return count("trip_seat", tripId);
    }

    private int count(String table, UUID tripId) {
        Integer n =
                jdbc.queryForObject(
                        "SELECT count(*) FROM " + table + " WHERE trip_id = :tripId",
                        Map.of("tripId", tripId),
                        Integer.class);
        return n == null ? 0 : n;
    }
}
