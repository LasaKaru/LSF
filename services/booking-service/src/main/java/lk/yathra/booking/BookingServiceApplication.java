package lk.yathra.booking;

import lk.yathra.booking.config.BookingProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;

/**
 * booking-service owns INV-1 -- the guarantee that no two active segments overlap on one seat -- and
 * therefore owns seat inventory, holds and bookings in a single database and a single transaction.
 *
 * <p>That co-location is the most important architectural decision in the project and is argued in
 * full in docs/02 §5: a transactional invariant defines a service boundary, and splitting inventory
 * from booking would turn INV-1 into a distributed invariant enforceable only by a saga -- which means
 * a window where a seat is double-sold, resolved by cancelling somebody's confirmed ticket.
 */
@SpringBootApplication
@ComponentScan(basePackages = {"lk.yathra.booking", "lk.yathra.common"})
@EnableConfigurationProperties(BookingProperties.class)
public class BookingServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(BookingServiceApplication.class, args);
    }
}
