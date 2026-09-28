package io.prreviewassistant.testing;

import java.util.Map;

public class PullSageTest {

    public String findUsername(Map<Long, String> users, Long id) {
        String username = users.get(id);

        if (username == null) {
            throw new IllegalArgumentException("User not found");
        }

        return username.toUpperCase();
    }
}