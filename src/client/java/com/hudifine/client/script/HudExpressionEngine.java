package com.hudifine.client.script;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class HudExpressionEngine {
    private static final int EXPRESSION_CACHE_SIZE = 2048;
    private static final int TEMPLATE_CACHE_SIZE = 1024;
    private static final Map<String, Node> EXPRESSION_CACHE = Collections.synchronizedMap(new LinkedHashMap<>(EXPRESSION_CACHE_SIZE, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Node> eldest) {
            return size() > EXPRESSION_CACHE_SIZE;
        }
    });
    private static final Map<String, List<TemplatePart>> TEMPLATE_CACHE = Collections.synchronizedMap(new LinkedHashMap<>(TEMPLATE_CACHE_SIZE, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, List<TemplatePart>> eldest) {
            return size() > TEMPLATE_CACHE_SIZE;
        }
    });
    private static final DecimalFormatSymbols ROOT_DECIMAL_SYMBOLS = DecimalFormatSymbols.getInstance(Locale.ROOT);
    private static final ThreadLocal<Map<Integer, DecimalFormat>> DECIMAL_FORMAT_CACHE = ThreadLocal.withInitial(HashMap::new);

    private HudExpressionEngine() {
    }

    public interface Resolver {
        Object getData(String path);

        Object getSetting(String id);
    }

    public static Object eval(String expression, Resolver resolver) {
        String raw = expression == null ? "" : expression.trim();
        if (raw.isEmpty()) {
            return "";
        }

        return compileExpression(raw).evaluate(resolver);
    }

    public static double evalNumber(String expression, Resolver resolver, double fallback) {
        Object value = eval(expression, resolver);
        return asDouble(value, fallback);
    }

    public static boolean evalBoolean(String expression, Resolver resolver, boolean fallback) {
        Object value = eval(expression, resolver);
        if (value == null) {
            return fallback;
        }
        return asBoolean(value);
    }

    public static String evalString(String expression, Resolver resolver) {
        Object value = eval(expression, resolver);
        return value == null ? "" : String.valueOf(value);
    }

    public static String evalTemplate(String rawValue, Resolver resolver) {
        String normalized = normalizeRawValue(rawValue);
        List<TemplatePart> parts = compileTemplate(normalized);
        if (parts.size() == 1 && parts.get(0) instanceof LiteralTemplatePart literal) {
            return literal.text();
        }

        StringBuilder output = new StringBuilder(Math.max(16, normalized.length()));
        for (TemplatePart part : parts) {
            part.append(output, resolver);
        }
        return output.toString();
    }

    private static Node compileExpression(String expression) {
        String raw = expression == null ? "" : expression.trim();
        if (raw.isEmpty()) {
            return new LiteralNode("");
        }

        Node cached = EXPRESSION_CACHE.get(raw);
        if (cached != null) {
            return cached;
        }

        Node compiled;
        IfThenElseSplit split = splitIfThenElse(raw);
        if (split != null) {
            compiled = new IfThenElseNode(
                compileExpression(split.condition),
                compileExpression(split.whenTrue),
                compileExpression(split.whenFalse)
            );
        } else {
            Parser parser = new Parser(raw);
            compiled = parser.parseExpression();
        }

        EXPRESSION_CACHE.put(raw, compiled);
        return compiled;
    }

    private static List<TemplatePart> compileTemplate(String normalized) {
        List<TemplatePart> cached = TEMPLATE_CACHE.get(normalized);
        if (cached != null) {
            return cached;
        }

        List<TemplatePart> parts = new ArrayList<>();
        int index = 0;

        while (index < normalized.length()) {
            int open = normalized.indexOf('{', index);
            if (open < 0) {
                if (index < normalized.length()) {
                    parts.add(new LiteralTemplatePart(normalized.substring(index)));
                }
                break;
            }

            int close = findClosingBrace(normalized, open + 1);
            if (close < 0) {
                parts.add(new LiteralTemplatePart(normalized.substring(index)));
                break;
            }

            if (open > index) {
                parts.add(new LiteralTemplatePart(normalized.substring(index, open)));
            }

            String expression = normalized.substring(open + 1, close).trim();
            if (!expression.isEmpty()) {
                parts.add(new ExpressionTemplatePart(compileExpression(expression)));
            }

            index = close + 1;
        }

        if (parts.isEmpty()) {
            parts.add(new LiteralTemplatePart(normalized));
        }

        List<TemplatePart> compiled = List.copyOf(parts);
        TEMPLATE_CACHE.put(normalized, compiled);
        return compiled;
    }

    public static String normalizeRawValue(String rawValue) {
        if (rawValue == null) {
            return "";
        }
        String trimmed = rawValue.trim();
        if (trimmed.length() >= 2) {
            char first = trimmed.charAt(0);
            char last = trimmed.charAt(trimmed.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return unescape(trimmed.substring(1, trimmed.length() - 1));
            }
        }
        return trimmed;
    }

    private static int findClosingBrace(String text, int start) {
        int depth = 1;
        boolean inString = false;
        char quote = 0;

        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (c == quote && text.charAt(i - 1) != '\\') {
                    inString = false;
                    quote = 0;
                }
                continue;
            }

            if (c == '"' || c == '\'') {
                inString = true;
                quote = c;
                continue;
            }

            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }

        return -1;
    }

    private static IfThenElseSplit splitIfThenElse(String expression) {
        String trimmed = expression.trim();
        if (!trimmed.toLowerCase(Locale.ROOT).startsWith("if ")) {
            return null;
        }

        int thenIndex = findKeywordOutsideGroups(trimmed, "then", 3);
        if (thenIndex < 0) {
            return null;
        }

        int elseIndex = findKeywordOutsideGroups(trimmed, "else", thenIndex + 4);
        if (elseIndex < 0) {
            return null;
        }

        String condition = trimmed.substring(2, thenIndex).trim();
        String whenTrue = trimmed.substring(thenIndex + 4, elseIndex).trim();
        String whenFalse = trimmed.substring(elseIndex + 4).trim();

        if (condition.isEmpty() || whenTrue.isEmpty() || whenFalse.isEmpty()) {
            return null;
        }

        return new IfThenElseSplit(condition, whenTrue, whenFalse);
    }

    private static int findKeywordOutsideGroups(String input, String keyword, int startIndex) {
        int parenDepth = 0;
        int bracketDepth = 0;
        int braceDepth = 0;
        boolean inString = false;
        char quote = 0;

        for (int i = startIndex; i <= input.length() - keyword.length(); i++) {
            char c = input.charAt(i);

            if (inString) {
                if (c == quote && input.charAt(i - 1) != '\\') {
                    inString = false;
                    quote = 0;
                }
                continue;
            }

            if (c == '"' || c == '\'') {
                inString = true;
                quote = c;
                continue;
            }

            if (c == '(') {
                parenDepth++;
                continue;
            }
            if (c == ')') {
                parenDepth = Math.max(0, parenDepth - 1);
                continue;
            }
            if (c == '[') {
                bracketDepth++;
                continue;
            }
            if (c == ']') {
                bracketDepth = Math.max(0, bracketDepth - 1);
                continue;
            }
            if (c == '{') {
                braceDepth++;
                continue;
            }
            if (c == '}') {
                braceDepth = Math.max(0, braceDepth - 1);
                continue;
            }

            if (parenDepth == 0 && bracketDepth == 0 && braceDepth == 0) {
                if (input.regionMatches(true, i, keyword, 0, keyword.length())) {
                    boolean leftBoundary = i == 0 || Character.isWhitespace(input.charAt(i - 1));
                    int end = i + keyword.length();
                    boolean rightBoundary = end >= input.length() || Character.isWhitespace(input.charAt(end));
                    if (leftBoundary && rightBoundary) {
                        return i;
                    }
                }
            }
        }

        return -1;
    }

    private static String unescape(String input) {
        return input
            .replace("\\n", "\n")
            .replace("\\t", "\t")
            .replace("\\\"", "\"")
            .replace("\\'", "'")
            .replace("\\\\", "\\");
    }

    public static boolean asBoolean(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return Math.abs(number.doubleValue()) > 1.0e-9;
        }
        String text = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        if (text.isEmpty()) {
            return false;
        }
        if (text.equals("true") || text.equals("yes") || text.equals("on")) {
            return true;
        }
        if (text.equals("false") || text.equals("no") || text.equals("off")) {
            return false;
        }
        try {
            return Math.abs(Double.parseDouble(text)) > 1.0e-9;
        } catch (NumberFormatException ignored) {
            return true;
        }
    }

    public static double asDouble(Object value, double fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value).trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String formatNumber(double value, int decimals) {
        Map<Integer, DecimalFormat> cache = DECIMAL_FORMAT_CACHE.get();
        DecimalFormat formatter = cache.computeIfAbsent(decimals, HudExpressionEngine::buildDecimalFormatter);
        return formatter.format(value);
    }

    private static DecimalFormat buildDecimalFormatter(int decimals) {
        StringBuilder pattern = new StringBuilder("0");
        if (decimals > 0) {
            pattern.append('.');
            pattern.append("0".repeat(Math.max(0, decimals)));
        }
        return new DecimalFormat(pattern.toString(), ROOT_DECIMAL_SYMBOLS);
    }

    private record IfThenElseSplit(String condition, String whenTrue, String whenFalse) {
    }

    private interface TemplatePart {
        void append(StringBuilder output, Resolver resolver);
    }

    private record LiteralTemplatePart(String text) implements TemplatePart {
        @Override
        public void append(StringBuilder output, Resolver resolver) {
            output.append(text);
        }
    }

    private record ExpressionTemplatePart(Node expression) implements TemplatePart {
        @Override
        public void append(StringBuilder output, Resolver resolver) {
            Object value = expression.evaluate(resolver);
            output.append(value == null ? "" : value);
        }
    }

    private interface Node {
        Object evaluate(Resolver resolver);
    }

    private record LiteralNode(Object value) implements Node {
        @Override
        public Object evaluate(Resolver resolver) {
            return value;
        }
    }

    private record VariableNode(String name) implements Node {
        @Override
        public Object evaluate(Resolver resolver) {
            if (name.equalsIgnoreCase("true")) {
                return true;
            }
            if (name.equalsIgnoreCase("false")) {
                return false;
            }
            return resolver.getData(name);
        }
    }

    private record UnaryNode(String operator, Node right) implements Node {
        @Override
        public Object evaluate(Resolver resolver) {
            Object value = right.evaluate(resolver);
            return switch (operator) {
                case "!" -> !asBoolean(value);
                case "-" -> -asDouble(value, 0.0);
                default -> value;
            };
        }
    }

    private record IfThenElseNode(Node condition, Node whenTrue, Node whenFalse) implements Node {
        @Override
        public Object evaluate(Resolver resolver) {
            return asBoolean(condition.evaluate(resolver)) ? whenTrue.evaluate(resolver) : whenFalse.evaluate(resolver);
        }
    }

    private record BinaryNode(Node left, String operator, Node right) implements Node {
        @Override
        public Object evaluate(Resolver resolver) {
            Object leftValue = left.evaluate(resolver);
            Object rightValue = right.evaluate(resolver);

            return switch (operator) {
                case "+" -> {
                    if (leftValue instanceof String || rightValue instanceof String) {
                        yield String.valueOf(leftValue) + rightValue;
                    }
                    yield asDouble(leftValue, 0.0) + asDouble(rightValue, 0.0);
                }
                case "-" -> asDouble(leftValue, 0.0) - asDouble(rightValue, 0.0);
                case "*" -> asDouble(leftValue, 0.0) * asDouble(rightValue, 0.0);
                case "/" -> {
                    double denominator = asDouble(rightValue, 0.0);
                    if (Math.abs(denominator) < 1.0e-9) {
                        yield 0.0;
                    }
                    yield asDouble(leftValue, 0.0) / denominator;
                }
                case "%" -> {
                    double denominator = asDouble(rightValue, 0.0);
                    if (Math.abs(denominator) < 1.0e-9) {
                        yield 0.0;
                    }
                    yield asDouble(leftValue, 0.0) % denominator;
                }
                case "==" -> equalsValue(leftValue, rightValue);
                case "!=" -> !equalsValue(leftValue, rightValue);
                case "<" -> asDouble(leftValue, 0.0) < asDouble(rightValue, 0.0);
                case "<=" -> asDouble(leftValue, 0.0) <= asDouble(rightValue, 0.0);
                case ">" -> asDouble(leftValue, 0.0) > asDouble(rightValue, 0.0);
                case ">=" -> asDouble(leftValue, 0.0) >= asDouble(rightValue, 0.0);
                case "&&" -> asBoolean(leftValue) && asBoolean(rightValue);
                case "||" -> asBoolean(leftValue) || asBoolean(rightValue);
                default -> 0.0;
            };
        }

        private boolean equalsValue(Object leftValue, Object rightValue) {
            if (leftValue == null && rightValue == null) {
                return true;
            }
            if (leftValue == null || rightValue == null) {
                return false;
            }
            if (leftValue instanceof Number || rightValue instanceof Number) {
                return Math.abs(asDouble(leftValue, 0.0) - asDouble(rightValue, 0.0)) < 1.0e-9;
            }
            return String.valueOf(leftValue).equalsIgnoreCase(String.valueOf(rightValue));
        }
    }

    private record CallNode(String functionName, List<Node> arguments) implements Node {
        @Override
        public Object evaluate(Resolver resolver) {
            String function = functionName.toLowerCase(Locale.ROOT);

            if (function.equals("get")) {
                if (arguments.isEmpty()) {
                    return "";
                }
                Node first = arguments.get(0);
                if (first instanceof VariableNode variableNode) {
                    return resolver.getData(variableNode.name());
                }
                Object key = first.evaluate(resolver);
                return resolver.getData(String.valueOf(key));
            }

            if (function.equals("setting")) {
                if (arguments.isEmpty()) {
                    return "";
                }
                Node first = arguments.get(0);
                if (first instanceof VariableNode variableNode) {
                    return resolver.getSetting(variableNode.name());
                }
                Object key = first.evaluate(resolver);
                return resolver.getSetting(String.valueOf(key));
            }

            List<Object> args = new ArrayList<>();
            for (Node argument : arguments) {
                args.add(argument.evaluate(resolver));
            }

            return switch (function) {
                case "round" -> (double) Math.round(asDouble(getArg(args, 0), 0.0));
                case "floor" -> Math.floor(asDouble(getArg(args, 0), 0.0));
                case "ceil" -> Math.ceil(asDouble(getArg(args, 0), 0.0));
                case "abs" -> Math.abs(asDouble(getArg(args, 0), 0.0));
                case "min" -> Math.min(asDouble(getArg(args, 0), 0.0), asDouble(getArg(args, 1), 0.0));
                case "max" -> Math.max(asDouble(getArg(args, 0), 0.0), asDouble(getArg(args, 1), 0.0));
                case "clamp" -> {
                    double value = asDouble(getArg(args, 0), 0.0);
                    double min = asDouble(getArg(args, 1), 0.0);
                    double max = asDouble(getArg(args, 2), 1.0);
                    yield Math.max(min, Math.min(max, value));
                }
                case "lerp" -> {
                    double a = asDouble(getArg(args, 0), 0.0);
                    double b = asDouble(getArg(args, 1), 1.0);
                    double t = asDouble(getArg(args, 2), 0.0);
                    yield a + (b - a) * t;
                }
                case "pct" -> {
                    double value = asDouble(getArg(args, 0), 0.0);
                    double max = asDouble(getArg(args, 1), 1.0);
                    if (Math.abs(max) < 1.0e-9) {
                        yield 0.0;
                    }
                    yield (value / max) * 100.0;
                }
                case "format" -> {
                    double value = asDouble(getArg(args, 0), 0.0);
                    int decimals = (int) Math.round(asDouble(getArg(args, 1), 2.0));
                    yield formatNumber(value, decimals);
                }
                case "upper" -> String.valueOf(getArg(args, 0)).toUpperCase(Locale.ROOT);
                case "lower" -> String.valueOf(getArg(args, 0)).toLowerCase(Locale.ROOT);
                case "concat" -> String.valueOf(getArg(args, 0)) + getArg(args, 1);
                case "trim" -> {
                    String value = String.valueOf(getArg(args, 0));
                    int max = (int) Math.round(asDouble(getArg(args, 1), value.length()));
                    if (max < 0) {
                        yield "";
                    }
                    yield value.length() <= max ? value : value.substring(0, max);
                }
                case "replace" -> String.valueOf(getArg(args, 0)).replace(String.valueOf(getArg(args, 1)), String.valueOf(getArg(args, 2)));
                default -> "";
            };
        }

        private Object getArg(List<Object> args, int index) {
            if (index < 0 || index >= args.size()) {
                return "";
            }
            return args.get(index);
        }
    }

    private static final class Parser {
        private final List<Token> tokens;
        private int cursor;

        private Parser(String source) {
            this.tokens = lex(source);
        }

        private Node parseExpression() {
            return parseOr();
        }

        private Node parseOr() {
            Node node = parseAnd();
            while (match(TokenType.OPERATOR, "||")) {
                Node right = parseAnd();
                node = new BinaryNode(node, "||", right);
            }
            return node;
        }

        private Node parseAnd() {
            Node node = parseEquality();
            while (match(TokenType.OPERATOR, "&&")) {
                Node right = parseEquality();
                node = new BinaryNode(node, "&&", right);
            }
            return node;
        }

        private Node parseEquality() {
            Node node = parseComparison();
            while (true) {
                if (match(TokenType.OPERATOR, "==")) {
                    node = new BinaryNode(node, "==", parseComparison());
                } else if (match(TokenType.OPERATOR, "!=")) {
                    node = new BinaryNode(node, "!=", parseComparison());
                } else {
                    break;
                }
            }
            return node;
        }

        private Node parseComparison() {
            Node node = parseTerm();
            while (true) {
                if (match(TokenType.OPERATOR, "<")) {
                    node = new BinaryNode(node, "<", parseTerm());
                } else if (match(TokenType.OPERATOR, "<=")) {
                    node = new BinaryNode(node, "<=", parseTerm());
                } else if (match(TokenType.OPERATOR, ">")) {
                    node = new BinaryNode(node, ">", parseTerm());
                } else if (match(TokenType.OPERATOR, ">=")) {
                    node = new BinaryNode(node, ">=", parseTerm());
                } else {
                    break;
                }
            }
            return node;
        }

        private Node parseTerm() {
            Node node = parseFactor();
            while (true) {
                if (match(TokenType.OPERATOR, "+")) {
                    node = new BinaryNode(node, "+", parseFactor());
                } else if (match(TokenType.OPERATOR, "-")) {
                    node = new BinaryNode(node, "-", parseFactor());
                } else {
                    break;
                }
            }
            return node;
        }

        private Node parseFactor() {
            Node node = parseUnary();
            while (true) {
                if (match(TokenType.OPERATOR, "*")) {
                    node = new BinaryNode(node, "*", parseUnary());
                } else if (match(TokenType.OPERATOR, "/")) {
                    node = new BinaryNode(node, "/", parseUnary());
                } else if (match(TokenType.OPERATOR, "%")) {
                    node = new BinaryNode(node, "%", parseUnary());
                } else {
                    break;
                }
            }
            return node;
        }

        private Node parseUnary() {
            if (match(TokenType.OPERATOR, "!")) {
                return new UnaryNode("!", parseUnary());
            }
            if (match(TokenType.OPERATOR, "-")) {
                return new UnaryNode("-", parseUnary());
            }
            return parseCall();
        }

        private Node parseCall() {
            Node primary = parsePrimary();

            while (match(TokenType.LPAREN)) {
                if (!(primary instanceof VariableNode variableNode)) {
                    throw new RuntimeException("Only named functions can be called.");
                }

                List<Node> arguments = new ArrayList<>();
                if (!check(TokenType.RPAREN)) {
                    do {
                        arguments.add(parseExpression());
                    } while (match(TokenType.COMMA));
                }

                consume(TokenType.RPAREN, "Expected ')' after function call.");
                primary = new CallNode(variableNode.name, arguments);
            }

            return primary;
        }

        private Node parsePrimary() {
            if (match(TokenType.NUMBER)) {
                try {
                    return new LiteralNode(Double.parseDouble(previous().text));
                } catch (NumberFormatException ignored) {
                    return new LiteralNode(0.0);
                }
            }

            if (match(TokenType.STRING)) {
                return new LiteralNode(unescape(previous().text.substring(1, previous().text.length() - 1)));
            }

            if (match(TokenType.COLOR)) {
                return new LiteralNode(previous().text);
            }

            if (match(TokenType.IDENTIFIER)) {
                return new VariableNode(previous().text);
            }

            if (match(TokenType.LPAREN)) {
                Node expression = parseExpression();
                consume(TokenType.RPAREN, "Expected ')' after expression.");
                return expression;
            }

            return new LiteralNode(0.0);
        }

        private boolean match(TokenType tokenType) {
            if (check(tokenType)) {
                cursor++;
                return true;
            }
            return false;
        }

        private boolean match(TokenType tokenType, String text) {
            if (check(tokenType) && peek().text.equals(text)) {
                cursor++;
                return true;
            }
            return false;
        }

        private void consume(TokenType tokenType, String message) {
            if (!match(tokenType)) {
                throw new RuntimeException(message);
            }
        }

        private boolean check(TokenType tokenType) {
            return peek().type == tokenType;
        }

        private Token peek() {
            return tokens.get(cursor);
        }

        private Token previous() {
            return tokens.get(Math.max(0, cursor - 1));
        }

        private static List<Token> lex(String source) {
            List<Token> list = new ArrayList<>();

            int index = 0;
            while (index < source.length()) {
                char c = source.charAt(index);
                if (Character.isWhitespace(c)) {
                    index++;
                    continue;
                }

                if (c == '"' || c == '\'') {
                    char quote = c;
                    int start = index;
                    index++;
                    boolean escaped = false;
                    while (index < source.length()) {
                        char current = source.charAt(index++);
                        if (escaped) {
                            escaped = false;
                            continue;
                        }
                        if (current == '\\') {
                            escaped = true;
                            continue;
                        }
                        if (current == quote) {
                            break;
                        }
                    }
                    list.add(new Token(TokenType.STRING, source.substring(start, index)));
                    continue;
                }

                if (c == '#') {
                    int start = index;
                    index++;
                    while (index < source.length() && isHexDigit(source.charAt(index))) {
                        index++;
                    }
                    list.add(new Token(TokenType.COLOR, source.substring(start, index)));
                    continue;
                }

                if (Character.isDigit(c) || (c == '-' && index + 1 < source.length() && Character.isDigit(source.charAt(index + 1)))) {
                    int start = index;
                    index++;
                    while (index < source.length()) {
                        char current = source.charAt(index);
                        if (Character.isDigit(current) || current == '.') {
                            index++;
                        } else {
                            break;
                        }
                    }
                    list.add(new Token(TokenType.NUMBER, source.substring(start, index)));
                    continue;
                }

                if (Character.isLetter(c) || c == '_' || c == '$') {
                    int start = index;
                    index++;
                    while (index < source.length()) {
                        char current = source.charAt(index);
                        if (Character.isLetterOrDigit(current) || current == '_' || current == '.' || current == '-' || current == '$') {
                            index++;
                        } else {
                            break;
                        }
                    }
                    list.add(new Token(TokenType.IDENTIFIER, source.substring(start, index)));
                    continue;
                }

                if (c == '(') {
                    list.add(new Token(TokenType.LPAREN, "("));
                    index++;
                    continue;
                }
                if (c == ')') {
                    list.add(new Token(TokenType.RPAREN, ")"));
                    index++;
                    continue;
                }
                if (c == ',') {
                    list.add(new Token(TokenType.COMMA, ","));
                    index++;
                    continue;
                }

                String maybeTwo = index + 1 < source.length() ? source.substring(index, index + 2) : "";
                if (maybeTwo.equals("==") || maybeTwo.equals("!=") || maybeTwo.equals("<=") || maybeTwo.equals(">=") || maybeTwo.equals("&&") || maybeTwo.equals("||")) {
                    list.add(new Token(TokenType.OPERATOR, maybeTwo));
                    index += 2;
                    continue;
                }

                if ("+-*/%<>!".indexOf(c) >= 0) {
                    list.add(new Token(TokenType.OPERATOR, String.valueOf(c)));
                    index++;
                    continue;
                }

                index++;
            }

            list.add(new Token(TokenType.EOF, ""));
            return list;
        }

        private static boolean isHexDigit(char c) {
            return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
        }

        private enum TokenType {
            NUMBER,
            STRING,
            COLOR,
            IDENTIFIER,
            LPAREN,
            RPAREN,
            COMMA,
            OPERATOR,
            EOF
        }

        private record Token(TokenType type, String text) {
        }
    }
}
