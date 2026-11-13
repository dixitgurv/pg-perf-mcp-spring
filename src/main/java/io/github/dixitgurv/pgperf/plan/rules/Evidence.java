package io.github.dixitgurv.pgperf.plan.rules;

import java.util.LinkedHashMap;
import java.util.Map;

/** Ordered key/value evidence that, unlike Map.of, tolerates null values from the plan. */
final class Evidence {

    private Evidence() {
    }

    static Map<String, Object> of(Object... keysAndValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return map;
    }

    static String n(double value) {
        return String.format("%,d", Math.round(value));
    }
}
