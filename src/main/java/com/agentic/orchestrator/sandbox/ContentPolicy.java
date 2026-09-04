package com.agentic.orchestrator.sandbox;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Screens file content before it is written.
 *
 * <p>The path-based secret policy catches a file <em>named</em> like a credential store. This
 * catches a credential pasted into an ordinary-looking source file, which is the more common way
 * secrets actually reach a repository.
 *
 * <p>Deliberately conservative: these patterns match credential <em>shapes</em>, not the words
 * "password" or "token". Matching those would fire on every configuration class and every javadoc
 * that mentions authentication, and a check that cries wolf gets switched off.
 */
@Component
public class ContentPolicy {

    private record Rule(String name, Pattern pattern) {
    }

    private static final List<Rule> RULES = List.of(
            new Rule("PEM private key",
                    Pattern.compile("-----BEGIN (?:RSA |EC |OPENSSH |DSA |PGP )?PRIVATE KEY-----")),
            new Rule("AWS access key id",
                    Pattern.compile("\\b(?:AKIA|ASIA)[0-9A-Z]{16}\\b")),
            new Rule("GitHub token",
                    Pattern.compile("\\bgh[pousr]_[A-Za-z0-9]{36,}\\b")),
            new Rule("Slack token",
                    Pattern.compile("\\bxox[abprs]-[A-Za-z0-9-]{10,}\\b")),
            new Rule("Anthropic API key",
                    Pattern.compile("\\bsk-ant-[A-Za-z0-9_-]{20,}\\b")),
            new Rule("Generic assigned secret",
                    // A quoted, high-entropy-looking value assigned to a secret-ish name. Requires
                    // both the name and a substantial literal, so `password=""` or
                    // `token = properties.get(...)` do not trip it.
                    Pattern.compile(
                            "(?i)\\b(?:password|passwd|secret|api[_-]?key|access[_-]?token)\\s*"
                                    + "[=:]\\s*[\"'][A-Za-z0-9+/_=-]{16,}[\"']")));

    /**
     * @return the name of the first rule the content trips, or empty if it is clean
     */
    public Optional<String> firstViolation(String content) {
        if (content == null || content.isEmpty()) {
            return Optional.empty();
        }
        for (Rule rule : RULES) {
            if (rule.pattern().matcher(content).find()) {
                return Optional.of(rule.name());
            }
        }
        return Optional.empty();
    }
}
