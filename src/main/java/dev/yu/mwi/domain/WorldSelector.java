package dev.yu.mwi.domain;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Selects worlds using an exact name, a glob, or an {@code r=} regular expression. */
public sealed interface WorldSelector permits WorldSelector.Exact, WorldSelector.Glob, WorldSelector.Regex {
    int MAX_EXPRESSION_LENGTH = 256;
    enum Kind {
        EXACT,
        GLOB,
        REGEX
    }

    String expression();

    Kind kind();

    boolean matches(WorldIdentity world);

    static WorldSelector parse(String expression) {
        String normalized = DomainValidation.requireText(expression, "world selector");
        requireBoundedLength(normalized, "world selector");
        if (normalized.startsWith("r=")) {
            String regularExpression = normalized.substring(2);
            if (regularExpression.isEmpty()) {
                throw new IllegalArgumentException("regular expression selector must not be empty");
            }
            return new Regex(regularExpression);
        }
        if (normalized.indexOf('*') >= 0 || normalized.indexOf('?') >= 0) {
            return new Glob(normalized);
        }
        return new Exact(normalized);
    }

    static List<WorldSelector> parseAll(Collection<String> expressions) {
        Objects.requireNonNull(expressions, "expressions");
        List<WorldSelector> selectors = new ArrayList<>(expressions.size());
        for (String expression : expressions) {
            selectors.add(parse(expression));
        }
        return List.copyOf(selectors);
    }

    private static String requireBoundedLength(String expression, String name) {
        if (expression.length() > MAX_EXPRESSION_LENGTH) {
            throw new IllegalArgumentException(name + " exceeds " + MAX_EXPRESSION_LENGTH + " characters");
        }
        return expression;
    }

    record Exact(String expression) implements WorldSelector {
        public Exact {
            expression = DomainValidation.requireText(expression, "exact world selector");
            requireBoundedLength(expression, "exact world selector");
        }

        @Override
        public Kind kind() {
            return Kind.EXACT;
        }

        @Override
        public boolean matches(WorldIdentity world) {
            return Objects.requireNonNull(world, "world").hasName(expression);
        }
    }

    final class Glob implements WorldSelector {
        private final String expression;
        private final Pattern pattern;

        public Glob(String expression) {
            this.expression = DomainValidation.requireText(expression, "glob world selector");
            requireBoundedLength(this.expression, "glob world selector");
            this.pattern = Pattern.compile(toRegularExpression(this.expression));
        }

        @Override
        public String expression() {
            return expression;
        }

        @Override
        public Kind kind() {
            return Kind.GLOB;
        }

        @Override
        public boolean matches(WorldIdentity world) {
            return Objects.requireNonNull(world, "world")
                    .anyNameMatches(name -> pattern.matcher(name).matches());
        }

        private static String toRegularExpression(String glob) {
            StringBuilder regularExpression = new StringBuilder(glob.length() * 2 + 2).append('^');
            StringBuilder literal = new StringBuilder();
            for (int index = 0; index < glob.length(); index++) {
                char character = glob.charAt(index);
                if (character == '*' || character == '?') {
                    appendQuoted(regularExpression, literal);
                    regularExpression.append(character == '*' ? ".*" : ".");
                } else {
                    literal.append(character);
                }
            }
            appendQuoted(regularExpression, literal);
            return regularExpression.append('$').toString();
        }

        private static void appendQuoted(StringBuilder target, StringBuilder literal) {
            if (!literal.isEmpty()) {
                target.append(Pattern.quote(literal.toString()));
                literal.setLength(0);
            }
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Glob glob && expression.equals(glob.expression);
        }

        @Override
        public int hashCode() {
            return expression.hashCode();
        }

        @Override
        public String toString() {
            return expression;
        }
    }

    final class Regex implements WorldSelector {
        private final String regularExpression;
        private final Pattern pattern;

        public Regex(String regularExpression) {
            this.regularExpression = DomainValidation.requireText(regularExpression, "regular expression");
            requireBoundedLength(this.regularExpression, "regular expression");
            validateSafeRegularExpression(this.regularExpression);
            try {
                this.pattern = Pattern.compile(this.regularExpression);
            } catch (PatternSyntaxException exception) {
                throw new IllegalArgumentException("invalid world regular expression: " + regularExpression, exception);
            }
        }

        private static void validateSafeRegularExpression(String expression) {
            ArrayDeque<Boolean> groupContainsUnboundedQuantifier = new ArrayDeque<>();
            boolean escaped = false;
            boolean characterClass = false;
            for (int index = 0; index < expression.length(); index++) {
                char character = expression.charAt(index);
                if (escaped) {
                    if (Character.isDigit(character)) {
                        throw unsafe("backreferences are not supported");
                    }
                    escaped = false;
                    continue;
                }
                if (character == '\\') {
                    escaped = true;
                    continue;
                }
                if (character == '[') {
                    characterClass = true;
                    continue;
                }
                if (character == ']' && characterClass) {
                    characterClass = false;
                    continue;
                }
                if (characterClass) {
                    continue;
                }
                if (character == '(') {
                    if (index + 1 < expression.length() && expression.charAt(index + 1) == '?') {
                        throw unsafe("lookarounds and special groups are not supported");
                    }
                    groupContainsUnboundedQuantifier.push(false);
                    continue;
                }
                if (character == '*' || character == '+') {
                    markCurrentGroupUnbounded(groupContainsUnboundedQuantifier);
                    continue;
                }
                if (character == '{') {
                    int close = expression.indexOf('}', index + 1);
                    if (close < 0) {
                        continue;
                    }
                    String bounds = expression.substring(index + 1, close);
                    validateBounds(bounds);
                    if (bounds.endsWith(",")) {
                        markCurrentGroupUnbounded(groupContainsUnboundedQuantifier);
                    }
                    index = close;
                    continue;
                }
                if (character == ')' && !groupContainsUnboundedQuantifier.isEmpty()) {
                    boolean containsUnbounded = groupContainsUnboundedQuantifier.pop();
                    if (containsUnbounded && isQuantifierAt(expression, index + 1)) {
                        throw unsafe("nested unbounded quantifiers are not supported");
                    }
                    if (containsUnbounded) {
                        markCurrentGroupUnbounded(groupContainsUnboundedQuantifier);
                    }
                }
            }
        }

        private static void validateBounds(String bounds) {
            String[] values = bounds.split(",", -1);
            if (values.length > 2 || values[0].isEmpty()) {
                return;
            }
            for (String value : values) {
                if (value.isEmpty()) {
                    continue;
                }
                try {
                    if (Integer.parseInt(value) > MAX_EXPRESSION_LENGTH) {
                        throw unsafe("quantifier bounds may not exceed " + MAX_EXPRESSION_LENGTH);
                    }
                } catch (NumberFormatException invalid) {
                    return;
                }
            }
        }

        private static boolean isQuantifierAt(String expression, int index) {
            if (index >= expression.length()) {
                return false;
            }
            char next = expression.charAt(index);
            return next == '?' || next == '*' || next == '+' || next == '{';
        }

        private static void markCurrentGroupUnbounded(ArrayDeque<Boolean> groups) {
            if (!groups.isEmpty()) {
                groups.pop();
                groups.push(true);
            }
        }

        private static IllegalArgumentException unsafe(String detail) {
            return new IllegalArgumentException("unsafe world regular expression: " + detail);
        }

        public String regularExpression() {
            return regularExpression;
        }

        @Override
        public String expression() {
            return "r=" + regularExpression;
        }

        @Override
        public Kind kind() {
            return Kind.REGEX;
        }

        @Override
        public boolean matches(WorldIdentity world) {
            return Objects.requireNonNull(world, "world")
                    .anyNameMatches(name -> pattern.matcher(name).matches());
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Regex regex && regularExpression.equals(regex.regularExpression);
        }

        @Override
        public int hashCode() {
            return regularExpression.hashCode();
        }

        @Override
        public String toString() {
            return expression();
        }
    }
}
