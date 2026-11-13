package io.github.dixitgurv.pgperf.plan;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Best-effort extraction of the column names compared in a plan's filter expression. */
public final class FilterColumns {

    private static final Pattern COMPARED = Pattern.compile(
            "\\(?\\s*([a-zA-Z_][a-zA-Z0-9_]*)\\s*\\)?(?:::[a-z ]+)?\\s*(=|<>|!=|<=|>=|<|>|~~\\*?|IS|ANY)");
    private static final Pattern QUALIFIED = Pattern.compile("\\b([a-zA-Z_][a-zA-Z0-9_]*)\\.([a-zA-Z_][a-zA-Z0-9_]*)\\b");
    private static final Set<String> KEYWORDS = Set.of("AND", "OR", "NOT", "NULL", "TRUE", "FALSE");

    private FilterColumns() {
    }

    /** {@code ((status)::text = 'disputed'::text)} gives {@code [status]}. */
    public static List<String> in(String expression) {
        List<String> columns = new ArrayList<>();
        if (expression == null) {
            return columns;
        }
        Matcher m = COMPARED.matcher(expression);
        while (m.find()) {
            String col = m.group(1);
            if (!KEYWORDS.contains(col.toUpperCase()) && !columns.contains(col)) {
                columns.add(col);
            }
        }
        return columns;
    }

    /** Columns qualified by {@code alias} in a join condition: {@code (o.customer_id = c.id)}, alias o gives {@code [customer_id]}. */
    public static List<String> qualifiedBy(String alias, String condition) {
        List<String> columns = new ArrayList<>();
        if (condition == null || alias == null) {
            return columns;
        }
        Matcher m = QUALIFIED.matcher(condition);
        while (m.find()) {
            if (m.group(1).equals(alias) && !columns.contains(m.group(2))) {
                columns.add(m.group(2));
            }
        }
        return columns;
    }
}
