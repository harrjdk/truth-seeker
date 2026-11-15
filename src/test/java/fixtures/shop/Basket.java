package fixtures.shop;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** JPetStore Cart-shaped: a map and a list initialised empty, an Iterator accessor, lookups by id. */
public class Basket {
    private final Map<String, BasketItem> itemMap = new ConcurrentHashMap<>();
    private final List<BasketItem> itemList = new ArrayList<>();

    public Iterator<BasketItem> getAllItems() {
        return itemList.iterator();
    }

    public boolean containsItemId(String itemId) {
        return itemMap.containsKey(itemId);
    }

    public void addItem(Product product) {
        BasketItem item = itemMap.get(product.getId());
        if (item == null) {
            item = new BasketItem();
            item.setProduct(product);
            itemMap.put(product.getId(), item);
            itemList.add(item);
        }
        item.incrementQuantity();
    }

    public Product removeItemById(String itemId) {
        BasketItem item = itemMap.remove(itemId);
        if (item == null) {
            return null;
        }
        itemList.remove(item);
        return item.getProduct();
    }

    public void setQuantityByItemId(String itemId, int quantity) {
        BasketItem item = itemMap.get(itemId);
        item.setQuantity(quantity);
    }
}
