package lk.yathra.booking.waitlist;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import lk.yathra.booking.booking.BookingDtos.QuoteRef;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/waitlist")
public class WaitlistController {

    private final WaitlistService waitlist;

    public WaitlistController(WaitlistService waitlist) {
        this.waitlist = waitlist;
    }

    public record JoinBody(
            @NotNull UUID tripId,
            @NotBlank String from,
            @NotBlank String to,
            String classCode,
            String name,
            @NotBlank String email,
            String phone,
            @Valid @NotNull QuoteRef quote) {}

    @PostMapping
    public ResponseEntity<WaitlistService.EntryView> join(@Valid @RequestBody JoinBody body) {
        var view =
                waitlist.join(
                        new WaitlistService.JoinRequest(
                                body.tripId(), body.from(), body.to(), body.classCode(),
                                body.name(), body.email(), body.phone(), body.quote()));
        return ResponseEntity.status(HttpStatus.CREATED).header("Cache-Control", "no-store").body(view);
    }

    @GetMapping("/{id}")
    public WaitlistService.EntryView status(@PathVariable UUID id) {
        return waitlist.view(id);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> leave(@PathVariable UUID id, @RequestParam String email) {
        waitlist.leave(id, email);
        return ResponseEntity.noContent().build();
    }
}
