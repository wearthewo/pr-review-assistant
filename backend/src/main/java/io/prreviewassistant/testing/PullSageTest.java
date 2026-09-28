package io.prreviewassistant.testing;

import java.util.Map;

public class PullSageTest {

    public String findUsername(Map<Long, String> users, Long id) {
        return users.get(id).toUpperCase();
    }
}