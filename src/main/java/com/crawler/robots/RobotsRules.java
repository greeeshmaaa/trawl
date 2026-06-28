package com.crawler.robots;

import java.util.List;
import java.util.regex.Pattern;

public class RobotsRules {
    public static final RobotsRules ALLOW_ALL = new RobotsRules(List.of(), 0);

    public static final class Rule {
        final Pattern regex;
        final int specificity;
        final boolean allow;
        Rule(Pattern regex, int specificity, boolean allow) {
            this.regex = regex;
            this.specificity = specificity;
            this.allow = allow;
        }
    }

    private final List<Rule> rules;
    private final long crawlDelayMs;

    public RobotsRules(List<Rule> rules, long crawlDelayMs) {
        this.rules = rules;
        this.crawlDelayMs = crawlDelayMs;
    }

    public long crawlDelayMs() { return crawlDelayMs; }

    public boolean allows(String path) {
        if (path == null || path.isEmpty()) path = "/";
        int bestSpec = -1;
        boolean decision = true;
        for (Rule r : rules) {
            if (r.regex.matcher(path).find()) {
                if (r.specificity > bestSpec || (r.specificity == bestSpec && r.allow)) {
                    bestSpec = r.specificity;
                    decision = r.allow;
                }
            }
        }
        return decision;
    }

    public static Rule rule(String pattern, boolean allow) {
        if (pattern == null || pattern.isEmpty()) return null;
        StringBuilder rx = new StringBuilder("^");
        boolean anchorEnd = false;
        int specificity = 0;
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '*') {
                rx.append(".*");
            } else if (c == '$' && i == pattern.length() - 1) {
                anchorEnd = true;
            } else {
                if ("\\.[]{}()<>+-=!?^|".indexOf(c) >= 0) rx.append('\\');
                rx.append(c);
                specificity++;
            }
        }
        if (anchorEnd) rx.append('$');
        return new Rule(Pattern.compile(rx.toString()), specificity, allow);
    }
}
