package io.prreviewassistant.testing;

import java.util.List;

public class OrderService {

    public int calculateAverage(List<Integer> prices) {
        int total = 0;

        for (Integer price : prices) {
            total += price;
        }

        return total / prices.size();
    }
}