package com.elastiflix;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Guards the hand-written stylesheet that replaced the Tailwind CDN.
 *
 * <p>The stylesheet defines only the classes the templates actually use. That is a deliberate
 * trade-off — no Node toolchain, no CDN, a few kilobytes instead of a browser-side compiler —
 * but it has one failure mode the old setup did not: a class typed into a template that nobody
 * ever defined does <em>nothing</em>, silently, and only a human looking at the page notices.
 *
 * <p>These tests turn that into a build failure, and pin the CSP-relevant properties of the
 * markup (no CDN, no inline handlers) so the policy cannot quietly regress.
 */
class TemplateAssetsTest {

    private static final PathMatchingResourcePatternResolver RESOLVER =
            new PathMatchingResourcePatternResolver();

    /** Class attributes, ignoring any that Thymeleaf computes at render time. */
    private static final Pattern CLASS_ATTRIBUTE = Pattern.compile("\\sclass=\"([^\"]*)\"");

    /** String literals inside a th:classappend ternary, e.g. ... ? 'bg-brand text-white' : '...' */
    private static final Pattern CLASSAPPEND = Pattern.compile("th:classappend=\"([^\"]*)\"");
    private static final Pattern QUOTED_LITERAL = Pattern.compile("'([^']+)'");

    private static final Pattern CSS_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);

    /**
     * A class name in a selector. The {@code \\.} branch is what allows Tailwind-style escapes —
     * {@code .gap-1\.5}, {@code .bg-black\/70}, {@code .text-\[10px\]}, {@code .hover\:underline} —
     * and a leading digit or hyphen is allowed so negatives like {@code .-mt-32} are found.
     */
    private static final Pattern CSS_CLASS = Pattern.compile("\\.((?:\\\\.|[A-Za-z0-9_-])+)");

    /** HTML comments cannot execute anything, so they are removed before the CSP checks. */
    private static final Pattern HTML_COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);

    /** A src/href that points off-origin. The Thymeleaf xmlns declaration is not one of these. */
    private static final Pattern EXTERNAL_REFERENCE =
            Pattern.compile("(?:src|href)\\s*=\\s*\"\\s*(?:https?:)?//", Pattern.CASE_INSENSITIVE);

    private static String read(String location) throws IOException {
        Resource resource = RESOLVER.getResource(location);
        return resource.getContentAsString(StandardCharsets.UTF_8);
    }

    private static Resource[] templates() throws IOException {
        return RESOLVER.getResources("classpath:templates/**/*.html");
    }

    /** Every class name a template asks for, including the branches of a th:classappend. */
    private static Set<String> classesUsedInTemplates() throws IOException {
        Set<String> used = new TreeSet<>();
        for (Resource template : templates()) {
            String html = template.getContentAsString(StandardCharsets.UTF_8);

            Matcher attributes = CLASS_ATTRIBUTE.matcher(html);
            while (attributes.find()) {
                for (String candidate : attributes.group(1).trim().split("\\s+")) {
                    // Skip anything Thymeleaf substitutes; only literals can be checked here.
                    if (!candidate.isEmpty() && !candidate.contains("$")) {
                        used.add(candidate);
                    }
                }
            }

            Matcher appends = CLASSAPPEND.matcher(html);
            while (appends.find()) {
                Matcher literals = QUOTED_LITERAL.matcher(appends.group(1));
                while (literals.find()) {
                    for (String candidate : literals.group(1).trim().split("\\s+")) {
                        if (!candidate.isEmpty()) {
                            used.add(candidate);
                        }
                    }
                }
            }
        }
        return used;
    }

    /** Every class the stylesheet defines, with Tailwind-style escapes (\: \/ \. \[) unescaped. */
    private static Set<String> classesDefinedInStylesheet() throws IOException {
        String css = CSS_COMMENT.matcher(read("classpath:static/css/elastiflix.css")).replaceAll("");

        Set<String> defined = new TreeSet<>();
        // Only look at selectors — the text before each rule's opening brace — so that
        // lengths like `0.5rem` inside declarations are not mistaken for class names.
        for (String block : css.split("\\{")) {
            String selector = block.contains("}") ? block.substring(block.lastIndexOf('}') + 1) : block;
            Matcher matcher = CSS_CLASS.matcher(selector);
            while (matcher.find()) {
                defined.add(matcher.group(1).replaceAll("\\\\(.)", "$1"));
            }
        }
        return defined;
    }

    @Test
    void everyClassUsedInATemplateIsDefinedInTheStylesheet() throws IOException {
        Set<String> undefined = new TreeSet<>(classesUsedInTemplates());
        undefined.removeAll(classesDefinedInStylesheet());

        org.assertj.core.api.Assertions.assertThat(undefined)
                .as("These classes appear in a template but nothing defines them, so they render "
                        + "as no-ops. Add them to static/css/elastiflix.css.")
                .isEmpty();
    }

    @Test
    void noTemplateLoadsAThirdPartyScriptOrStylesheet() throws IOException {
        for (Resource template : templates()) {
            String markup = HTML_COMMENT.matcher(template.getContentAsString(StandardCharsets.UTF_8))
                    .replaceAll("");

            org.assertj.core.api.Assertions.assertThat(EXTERNAL_REFERENCE.matcher(markup).find())
                    .as("%s must not fetch assets off-origin — the CSP allows 'self' only. "
                            + "(An xmlns=\"http://...\" namespace is fine; a src/href is not.)",
                            template.getFilename())
                    .isFalse();
        }
    }

    @Test
    void noTemplateUsesAnInlineEventHandlerOrStyleBlock() throws IOException {
        for (Resource template : templates()) {
            // Comments stripped first: prose about `<style>` is not a `<style>`.
            String markup = HTML_COMMENT.matcher(template.getContentAsString(StandardCharsets.UTF_8))
                    .replaceAll("");

            org.assertj.core.api.Assertions.assertThat(markup)
                    .as("%s must keep behaviour in static/js/elastiflix.js: an inline handler or "
                            + "a <style> block would need 'unsafe-inline' back in the CSP",
                            template.getFilename())
                    .doesNotContain("onchange=")
                    .doesNotContain("onclick=")
                    .doesNotContain("onerror=")
                    .doesNotContain("onkeypress=")
                    .doesNotContain("onload=")
                    .doesNotContain("<style");
        }
    }

    @Test
    void theStylesheetAndScriptAreActuallyOnTheClasspath() throws IOException {
        // A 404 on either would leave the app unstyled or inert, and no other test would notice.
        org.assertj.core.api.Assertions.assertThat(read("classpath:static/css/elastiflix.css"))
                .contains(".bg-brand");
        org.assertj.core.api.Assertions.assertThat(read("classpath:static/js/elastiflix.js"))
                .contains("data-submit-on-change");
    }
}
