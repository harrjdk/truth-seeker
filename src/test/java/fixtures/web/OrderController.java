package fixtures.web;

import fixtures.order.OrderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

@RestController
@RequestMapping("/orders")
public class OrderController {

    @Autowired
    private OrderService orderService;

    @GetMapping("/{id}/retries")
    public int retries(@PathVariable("id") long id) {
        return orderService.retriesLeft((int) id);
    }

    @PostMapping("/{id}/pay")
    public String pay(@PathVariable("id") long id) {
        return orderService.pay(id, new BigDecimal("10.00")) ? "ok" : "declined";
    }
}
