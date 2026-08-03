package lk.yathra.booking.trip;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Turns a coach layout grid into concrete seats.
 *
 * <p>This is the single place that knows what a layout <em>means</em>, which is what makes the brief's
 * configurability requirement real: a 2+2 second-class coach, a 2+1 observation saloon and a 3+2
 * commuter coach are three rows in {@code coach_layout}, and adding a fourth needs no code change here,
 * in the API, or in the frontend.
 *
 * <p>Expected grid shape:
 *
 * <pre>{@code
 * { "rows": 15, "columns": 4, "aisleAfterColumn": 2,
 *   "seatPattern": ["A","B","C","D"], "windowColumns": [1,4],
 *   "facing": "FORWARD", "blanks": [{"row":15,"col":4}] }
 * }</pre>
 */
@Component
public class SeatMaterialiser {

    public record MaterialisedSeat(
            String label, int rowIndex, int columnIndex, boolean window, boolean aisle, String facing) {}

    public List<MaterialisedSeat> materialise(JsonNode layout) {
        if (layout == null || layout.isNull() || !layout.hasNonNull("rows")) {
            return List.of();
        }

        int rows = layout.get("rows").asInt();
        int columns = layout.get("columns").asInt();
        Integer aisleAfter =
                layout.hasNonNull("aisleAfterColumn") ? layout.get("aisleAfterColumn").asInt() : null;
        String facing = layout.hasNonNull("facing") ? layout.get("facing").asText() : "FORWARD";

        List<String> pattern = new ArrayList<>();
        if (layout.has("seatPattern")) {
            layout.get("seatPattern").forEach(n -> pattern.add(n.asText()));
        }

        Set<Integer> windowColumns = new HashSet<>();
        if (layout.has("windowColumns")) {
            layout.get("windowColumns").forEach(n -> windowColumns.add(n.asInt()));
        } else {
            // Sensible default: the outermost columns are against the windows.
            windowColumns.add(1);
            windowColumns.add(columns);
        }

        Set<String> blanks = new HashSet<>();
        if (layout.has("blanks")) {
            layout.get("blanks").forEach(b -> blanks.add(b.get("row").asInt() + ":" + b.get("col").asInt()));
        }

        List<MaterialisedSeat> seats = new ArrayList<>();
        for (int row = 1; row <= rows; row++) {
            for (int col = 1; col <= columns; col++) {
                if (blanks.contains(row + ":" + col)) {
                    continue; // A structural gap -- a wheelchair bay, a door, a luggage rack.
                }
                String suffix = col <= pattern.size() ? pattern.get(col - 1) : String.valueOf(col);
                boolean aisle = aisleAfter != null && (col == aisleAfter || col == aisleAfter + 1);
                seats.add(
                        new MaterialisedSeat(
                                row + suffix, row, col, windowColumns.contains(col), aisle, facing));
            }
        }
        return seats;
    }
}
