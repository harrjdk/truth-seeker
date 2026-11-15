package fixtures.shop;

import java.util.ArrayList;
import java.util.List;

/** PetClinic Vets-shaped: a list created lazily by its getter. */
public class Shelf {
    private List<Product> products;

    public List<Product> getProducts() {
        if (products == null) {
            products = new ArrayList<>();
        }
        return products;
    }
}
