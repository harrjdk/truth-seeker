package fixtures.shop;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** JPetStore CartController-shaped: session attributes, a lazily created basket, request parameters parsed in a loop. */
public class BasketController {

    public interface Session {
        Object getAttribute(String name);

        void setAttribute(String name, Object value);
    }

    public interface Request {
        String getParameter(String name);
    }

    public interface Account {
        String getUsername();
    }

    public interface ProductMapper {
        List<Product> search(String keyword);
    }

    private final ProductMapper products;

    public BasketController(ProductMapper products) {
        this.products = products;
    }

    private Basket getBasket(Session session) {
        Basket basket = (Basket) session.getAttribute("basket");
        if (basket == null) {
            basket = new Basket();
            session.setAttribute("basket", basket);
        }
        return basket;
    }

    public String removeItem(String itemId, Session session) {
        Basket basket = getBasket(session);
        Product removed = basket.removeItemById(itemId);
        if (removed == null) {
            return "error";
        }
        return "basket";
    }

    public String updateQuantities(Request request, Session session) {
        Basket basket = getBasket(session);
        Iterator<BasketItem> items = basket.getAllItems();
        while (items.hasNext()) {
            BasketItem item = items.next();
            String itemId = item.getProduct().getId();
            try {
                int quantity = Integer.parseInt(request.getParameter(itemId));
                basket.setQuantityByItemId(itemId, quantity);
                if (quantity < 1) {
                    items.remove();
                }
            } catch (NumberFormatException e) {
                // ignore invalid input
            }
        }
        return "basket";
    }

    public String checkout(Session session) {
        Account account = (Account) session.getAttribute("account");
        Basket basket = (Basket) session.getAttribute("basket");
        if (account == null) {
            return "redirect:/signon";
        }
        if (basket == null) {
            return "error";
        }
        session.setAttribute("owner", account.getUsername());
        return "checkout";
    }

    public List<Product> search(String keywords) {
        List<Product> found = new ArrayList<>();
        for (String keyword : keywords.split("\s+")) {
            found.addAll(products.search("%" + keyword.toLowerCase() + "%"));
        }
        return found;
    }

    public List<OrderLine> lines(Basket basket) {
        List<OrderLine> lines = new ArrayList<>();
        Iterator<BasketItem> it = basket.getAllItems();
        while (it.hasNext()) {
            lines.add(new OrderLine(lines.size() + 1, it.next()));
        }
        return lines;
    }
}
