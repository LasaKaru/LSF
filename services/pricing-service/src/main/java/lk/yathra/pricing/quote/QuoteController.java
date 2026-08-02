package lk.yathra.pricing.quote;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import lk.yathra.pricing.fare.FareRules;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class QuoteController {

    private final QuoteService quotes;

    public QuoteController(QuoteService quotes) {
        this.quotes = quotes;
    }

    public record QuoteRequestBody(
            @NotNull UUID tripId,
            @NotBlank String fromStation,
            @NotBlank String toStation,
            String classCode,
            String coachType,
            Integer passengers) {}

    @PostMapping("/quotes")
    public ResponseEntity<QuoteService.QuoteResponse> quote(@Valid @RequestBody QuoteRequestBody body) {
        var response =
                quotes.quote(
                        new QuoteService.QuoteRequest(
                                body.tripId(),
                                body.fromStation(),
                                body.toStation(),
                                body.classCode(),
                                body.coachType(),
                                body.passengers()));
        // A quote is priced for one moment and one passenger; caching it would be a way to serve a
        // stale price after a fare change.
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(response);
    }

    /**
     * The published tariff.
     *
     * <p>Exposed deliberately: a state operator's fares should be inspectable, and "why was I charged
     * this?" ought to be answerable without asking the department.
     */
    @GetMapping("/fare-rules")
    public FareRules fareRules() {
        return quotes.currentRules();
    }
}
