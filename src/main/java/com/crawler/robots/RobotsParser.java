package com.crawler.robots;

import java.util.ArrayList;
import java.util.List;

public final class RobotsParser {
    private RobotsParser() {}

    private static final class Group {
        final List<String> agents = new ArrayList<>();
        final List<RobotsRules.Rule> rules = new ArrayList<>();
        long crawlDelayMs = 0;
    }

    public static RobotsRules parse(String body, String productToken) {
        String token = productToken.toLowerCase();
        List<Group> groups = new ArrayList<>();
        Group current = null;
        boolean lastWasAgent = false;

        for (String rawLine : body.split("\n")) {
            String line = stripComment(rawLine).trim();
            if (line.isEmpty()) continue;
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String field = line.substring(0, colon).trim().toLowerCase();
            String value = line.substring(colon + 1).trim();

            switch (field) {
                case "user-agent" -> {
                    if (current == null || !lastWasAgent) {
                        current = new Group();
                        groups.add(current);
                    }
                    current.agents.add(value.toLowerCase());
                    lastWasAgent = true;
                }
                case "disallow" -> {
                    if (current != null) current.rules.add(RobotsRules.rule(value, false));
                    lastWasAgent = false;
                }
                case "allow" -> {
                    if (current != null) current.rules.add(RobotsRules.rule(value, true));
                    lastWasAgent = false;
                }
                case "crawl-delay" -> {
                    if (current != null) {
                        try {
                            current.crawlDelayMs = (long) (Double.parseDouble(value) * 1000);
                        } catch (NumberFormatException ignore) { }
                    }
                    lastWasAgent = false;
                }
                default -> lastWasAgent = false;
            }
        }

        Group exact = null, wildcard = null;
        for (Group g : groups) {
            for (String a : g.agents) {
                if (a.equals(token) && exact == null) exact = g;
                else if (a.equals("*") && wildcard == null) wildcard = g;
            }
        }
        Group use = exact != null ? exact : wildcard;
        if (use == null) return RobotsRules.ALLOW_ALL;

        List<RobotsRules.Rule> rules = new ArrayList<>();
        for (RobotsRules.Rule r : use.rules) if (r != null) rules.add(r);
        return new RobotsRules(rules, use.crawlDelayMs);
    }

    private static String stripComment(String line) {
        int h = line.indexOf('#');
        return h >= 0 ? line.substring(0, h) : line;
    }
}
