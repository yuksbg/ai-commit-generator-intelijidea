package dev.aicommit;

import java.time.LocalDate;

final class PromptTemplate {
    static String render(String template, String branch, LocalDate date) {
        // Single pass: inserted branch names must not themselves expand as placeholders.
        var matcher = java.util.regex.Pattern.compile("\\{(BRANCH_NAME|DATE_ISO_8601)\\}").matcher(template);
        return matcher.replaceAll(match -> java.util.regex.Matcher.quoteReplacement(
            match.group(1).equals("BRANCH_NAME") ? branch : date.toString()));
    }
}
