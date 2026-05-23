package com.hudifine.client.script;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class HudScriptParser {
    private final String source;
    private final List<Token> tokens;
    private int cursor;

    public HudScriptParser(String source) {
        this.source = source == null ? "" : source;
        this.tokens = tokenize(this.source);
    }

    public HudAst.HudScriptDocument parse() {
        HudAst.HudScriptDocument document = new HudAst.HudScriptDocument();

        while (!isAtEnd()) {
            if (matchIdentifier("meta")) {
                parseMetaBlock(document);
                continue;
            }

            if (matchIdentifier("settings")) {
                parseSettingsBlock(document);
                continue;
            }

            if (matchIdentifier("widget")) {
                if (document.widget != null) {
                    throw error(previous(), "Only one widget block is allowed per script.");
                }
                document.widget = parseElementBlock("widget");
                continue;
            }

            Token token = advance();
            if (token.type != TokenType.EOF) {
                document.warnings.add("Skipped unexpected token: " + token.text + " at line " + token.line + ".");
            }
        }

        if (document.widget == null) {
            throw new ParseException("Script is missing required widget block.");
        }

        return document;
    }

    private void parseMetaBlock(HudAst.HudScriptDocument document) {
        expect(TokenType.LBRACE, "Expected '{' after meta.");
        while (!check(TokenType.RBRACE) && !isAtEnd()) {
            String key = expectIdentifier("Expected meta field key.").text;
            expect(TokenType.COLON, "Expected ':' after meta key.");
            String raw = readRawValue();
            document.meta.put(key, normalizeRaw(raw));
        }
        expect(TokenType.RBRACE, "Expected '}' to close meta block.");
    }

    private void parseSettingsBlock(HudAst.HudScriptDocument document) {
        expect(TokenType.LBRACE, "Expected '{' after settings.");

        while (!check(TokenType.RBRACE) && !isAtEnd()) {
            Token typeToken = expectIdentifier("Expected setting type.");
            HudAst.SettingType settingType = HudAst.SettingType.parse(typeToken.text);

            String label;
            if (check(TokenType.STRING)) {
                label = stripQuotes(advance().text);
            } else {
                label = toTitle(typeToken.text);
            }

            HudAst.SettingDefinition definition = new HudAst.SettingDefinition(label, settingType);

            expect(TokenType.LBRACE, "Expected '{' after setting declaration.");
            Map<String, String> propertyMap = new HashMap<>();
            parsePropertyMap(propertyMap);
            expect(TokenType.RBRACE, "Expected '}' to close setting block.");

            applySettingPropertyMap(definition, propertyMap);
            document.settings.add(definition);
        }

        expect(TokenType.RBRACE, "Expected '}' to close settings block.");
    }

    private HudAst.WidgetNode parseElementBlock(String type) {
        HudAst.WidgetNode element = new HudAst.WidgetNode(type);
        expect(TokenType.LBRACE, "Expected '{' after " + type + ".");

        while (!check(TokenType.RBRACE) && !isAtEnd()) {
            if (matchIdentifier("if")) {
                element.children.add(parseIfBlock());
                continue;
            }

            if (matchIdentifier("on")) {
                element.children.add(parseEventBlock());
                continue;
            }

            if (isPropertyStart()) {
                Token key = advance();
                expect(TokenType.COLON, "Expected ':' after property key.");
                element.props.put(key.text, readRawValue());
                continue;
            }

            if (isElementStart()) {
                String childType = advance().text;
                element.children.add(parseElementBlock(childType));
                continue;
            }

            Token skipped = advance();
            if (skipped.type != TokenType.EOF) {
                // Skip gracefully to keep editor-friendly behavior.
            }
        }

        expect(TokenType.RBRACE, "Expected '}' to close " + type + " block.");
        return element;
    }

    private HudAst.ConditionalNode parseIfBlock() {
        HudAst.ConditionalNode conditional = new HudAst.ConditionalNode();

        String condition = readRawUntil(TokenType.LBRACE);
        expect(TokenType.LBRACE, "Expected '{' after if condition.");

        HudAst.ConditionalBranch branch = new HudAst.ConditionalBranch(condition.trim());
        branch.children.addAll(parseNodeListUntilBraceClose());
        conditional.branches.add(branch);

        while (matchIdentifier("else")) {
            if (matchIdentifier("if")) {
                String elseIfCondition = readRawUntil(TokenType.LBRACE);
                expect(TokenType.LBRACE, "Expected '{' after else if condition.");
                HudAst.ConditionalBranch elseIfBranch = new HudAst.ConditionalBranch(elseIfCondition.trim());
                elseIfBranch.children.addAll(parseNodeListUntilBraceClose());
                conditional.branches.add(elseIfBranch);
                continue;
            }

            expect(TokenType.LBRACE, "Expected '{' after else.");
            conditional.elseChildren.addAll(parseNodeListUntilBraceClose());
            break;
        }

        return conditional;
    }

    private HudAst.EventNode parseEventBlock() {
        Token typeToken = expectIdentifier("Expected event type after on.");
        String eventName = typeToken.text.toLowerCase(Locale.ROOT);

        HudAst.EventNode eventNode = new HudAst.EventNode();
        switch (eventName) {
            case "tick" -> eventNode.type = HudAst.EventType.TICK;
            case "frame" -> eventNode.type = HudAst.EventType.FRAME;
            case "event" -> eventNode.type = HudAst.EventType.EVENT_CONDITION;
            case "keypress" -> eventNode.type = HudAst.EventType.KEYPRESS;
            case "keyhold" -> eventNode.type = HudAst.EventType.KEYHOLD;
            case "keyrelease" -> eventNode.type = HudAst.EventType.KEYRELEASE;
            default -> throw error(typeToken, "Unknown event type: " + typeToken.text);
        }

        if (match(TokenType.LPAREN)) {
            int start = previous().end;
            int depth = 1;
            int end = start;

            while (!isAtEnd() && depth > 0) {
                Token token = advance();
                if (token.type == TokenType.LPAREN) {
                    depth++;
                } else if (token.type == TokenType.RPAREN) {
                    depth--;
                    if (depth == 0) {
                        end = token.start;
                        break;
                    }
                }
            }

            eventNode.expression = source.substring(start, Math.max(start, end)).trim();
        }

        expect(TokenType.LBRACE, "Expected '{' after event declaration.");
        eventNode.children.addAll(parseNodeListUntilBraceClose());
        return eventNode;
    }

    private List<HudAst.Node> parseNodeListUntilBraceClose() {
        List<HudAst.Node> nodes = new ArrayList<>();

        while (!check(TokenType.RBRACE) && !isAtEnd()) {
            if (matchIdentifier("if")) {
                nodes.add(parseIfBlock());
                continue;
            }

            if (matchIdentifier("on")) {
                nodes.add(parseEventBlock());
                continue;
            }

            if (isElementStart()) {
                String type = advance().text;
                nodes.add(parseElementBlock(type));
                continue;
            }

            advance();
        }

        expect(TokenType.RBRACE, "Expected '}' to close block.");
        return nodes;
    }

    private void parsePropertyMap(Map<String, String> propertyMap) {
        while (!check(TokenType.RBRACE) && !isAtEnd()) {
            if (!isPropertyStart()) {
                advance();
                continue;
            }

            Token key = advance();
            expect(TokenType.COLON, "Expected ':' after property key.");
            propertyMap.put(key.text, readRawValue());
        }
    }

    private void applySettingPropertyMap(HudAst.SettingDefinition definition, Map<String, String> propertyMap) {
        if (propertyMap.containsKey("id")) {
            definition.id = normalizeRaw(propertyMap.get("id"));
        }

        if (propertyMap.containsKey("description")) {
            definition.description = normalizeRaw(propertyMap.get("description"));
        }

        if (propertyMap.containsKey("default")) {
            definition.defaultRaw = propertyMap.get("default").trim();
        }

        if (propertyMap.containsKey("min")) {
            definition.minRaw = propertyMap.get("min").trim();
        }

        if (propertyMap.containsKey("max")) {
            definition.maxRaw = propertyMap.get("max").trim();
        }

        if (propertyMap.containsKey("step")) {
            definition.stepRaw = propertyMap.get("step").trim();
        }

        if (propertyMap.containsKey("maxLength")) {
            definition.maxLengthRaw = propertyMap.get("maxLength").trim();
        }

        if (propertyMap.containsKey("options")) {
            String rawOptions = propertyMap.get("options").trim();
            if (rawOptions.startsWith("[") && rawOptions.endsWith("]")) {
                String inner = rawOptions.substring(1, rawOptions.length() - 1).trim();
                if (!inner.isBlank()) {
                    for (String part : splitArrayValues(inner)) {
                        definition.optionRaws.add(part.trim());
                    }
                }
            }
        }
    }

    private List<String> splitArrayValues(String raw) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inString = false;
        char stringQuote = 0;

        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);

            if (inString) {
                current.append(c);
                if (c == stringQuote && (i == 0 || raw.charAt(i - 1) != '\\')) {
                    inString = false;
                    stringQuote = 0;
                }
                continue;
            }

            if (c == '"' || c == '\'') {
                inString = true;
                stringQuote = c;
                current.append(c);
                continue;
            }

            if (c == ',') {
                values.add(current.toString());
                current.setLength(0);
                continue;
            }

            current.append(c);
        }

        if (!current.isEmpty()) {
            values.add(current.toString());
        }

        return values;
    }

    private String readRawValue() {
        if (check(TokenType.RBRACE)) {
            return "";
        }

        int start = peek().start;
        int parenDepth = 0;
        int bracketDepth = 0;

        while (!isAtEnd()) {
            if (parenDepth == 0 && bracketDepth == 0) {
                if (check(TokenType.RBRACE)) {
                    break;
                }
                if (isPropertyStart()) {
                    break;
                }
                if (isElementStart()) {
                    break;
                }
                if (checkIdentifier("if") || checkIdentifier("on") || checkIdentifier("else")) {
                    break;
                }
            }

            Token token = advance();
            if (token.type == TokenType.LPAREN) {
                parenDepth++;
            } else if (token.type == TokenType.RPAREN) {
                parenDepth = Math.max(0, parenDepth - 1);
            } else if (token.type == TokenType.LBRACKET) {
                bracketDepth++;
            } else if (token.type == TokenType.RBRACKET) {
                bracketDepth = Math.max(0, bracketDepth - 1);
            }
        }

        int end = previousMeaningful().end;
        if (end < start) {
            return "";
        }

        return source.substring(start, end).trim();
    }

    private String readRawUntil(TokenType terminalType) {
        int start = peek().start;
        int parenDepth = 0;
        int bracketDepth = 0;

        while (!isAtEnd()) {
            Token token = peek();
            if (token.type == terminalType && parenDepth == 0 && bracketDepth == 0) {
                break;
            }

            Token consumed = advance();
            if (consumed.type == TokenType.LPAREN) {
                parenDepth++;
            } else if (consumed.type == TokenType.RPAREN) {
                parenDepth = Math.max(0, parenDepth - 1);
            } else if (consumed.type == TokenType.LBRACKET) {
                bracketDepth++;
            } else if (consumed.type == TokenType.RBRACKET) {
                bracketDepth = Math.max(0, bracketDepth - 1);
            }
        }

        Token last = previousMeaningful();
        int end = Math.max(start, last.end);
        return source.substring(start, end);
    }

    private boolean isPropertyStart() {
        return check(TokenType.IDENTIFIER) && checkNext(TokenType.COLON);
    }

    private boolean isElementStart() {
        return check(TokenType.IDENTIFIER) && checkNext(TokenType.LBRACE);
    }

    private boolean check(TokenType type) {
        return peek().type == type;
    }

    private boolean checkNext(TokenType type) {
        if (cursor + 1 >= tokens.size()) {
            return false;
        }
        return tokens.get(cursor + 1).type == type;
    }

    private boolean checkIdentifier(String identifier) {
        if (!check(TokenType.IDENTIFIER)) {
            return false;
        }
        return peek().text.equalsIgnoreCase(identifier);
    }

    private boolean match(TokenType type) {
        if (check(type)) {
            advance();
            return true;
        }
        return false;
    }

    private boolean matchIdentifier(String identifier) {
        if (checkIdentifier(identifier)) {
            advance();
            return true;
        }
        return false;
    }

    private Token expect(TokenType type, String message) {
        if (!check(type)) {
            throw error(peek(), message);
        }
        return advance();
    }

    private Token expectIdentifier(String message) {
        if (!check(TokenType.IDENTIFIER)) {
            throw error(peek(), message);
        }
        return advance();
    }

    private Token peek() {
        return tokens.get(cursor);
    }

    private Token previous() {
        return tokens.get(cursor - 1);
    }

    private Token previousMeaningful() {
        int i = Math.max(0, cursor - 1);
        while (i >= 0) {
            Token token = tokens.get(i);
            if (token.type != TokenType.EOF) {
                return token;
            }
            i--;
        }
        return tokens.get(0);
    }

    private Token advance() {
        if (!isAtEnd()) {
            cursor++;
        }
        return tokens.get(cursor - 1);
    }

    private boolean isAtEnd() {
        return peek().type == TokenType.EOF;
    }

    private ParseException error(Token token, String message) {
        return new ParseException(message + " [line " + token.line + ", column " + token.column + "]");
    }

    private static String normalizeRaw(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        if (trimmed.length() >= 2) {
            char first = trimmed.charAt(0);
            char last = trimmed.charAt(trimmed.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return stripQuotes(trimmed);
            }
        }
        return trimmed;
    }

    private static String stripQuotes(String value) {
        if (value == null || value.length() < 2) {
            return value == null ? "" : value;
        }

        char first = value.charAt(0);
        char last = value.charAt(value.length() - 1);
        if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
            String inner = value.substring(1, value.length() - 1);
            return inner
                .replace("\\n", "\n")
                .replace("\\t", "\t")
                .replace("\\\"", "\"")
                .replace("\\'", "'")
                .replace("\\\\", "\\");
        }
        return value;
    }

    private static String toTitle(String raw) {
        if (raw == null || raw.isBlank()) {
            return "Setting";
        }
        return Character.toUpperCase(raw.charAt(0)) + raw.substring(1).toLowerCase(Locale.ROOT);
    }

    private static List<Token> tokenize(String input) {
        List<Token> output = new ArrayList<>();

        int index = 0;
        int line = 1;
        int column = 1;

        while (index < input.length()) {
            char c = input.charAt(index);

            if (Character.isWhitespace(c)) {
                if (c == '\n') {
                    line++;
                    column = 1;
                } else {
                    column++;
                }
                index++;
                continue;
            }

            if (c == '/' && index + 1 < input.length() && input.charAt(index + 1) == '/') {
                index += 2;
                column += 2;
                while (index < input.length() && input.charAt(index) != '\n') {
                    index++;
                    column++;
                }
                continue;
            }

            if (c == '/' && index + 1 < input.length() && input.charAt(index + 1) == '*') {
                index += 2;
                column += 2;
                while (index + 1 < input.length()) {
                    char current = input.charAt(index);
                    char next = input.charAt(index + 1);
                    if (current == '*' && next == '/') {
                        index += 2;
                        column += 2;
                        break;
                    }
                    if (current == '\n') {
                        line++;
                        column = 1;
                    } else {
                        column++;
                    }
                    index++;
                }
                continue;
            }

            int start = index;
            int tokenLine = line;
            int tokenColumn = column;

            switch (c) {
                case '{' -> {
                    output.add(new Token(TokenType.LBRACE, "{", tokenLine, tokenColumn, start, start + 1));
                    index++;
                    column++;
                }
                case '}' -> {
                    output.add(new Token(TokenType.RBRACE, "}", tokenLine, tokenColumn, start, start + 1));
                    index++;
                    column++;
                }
                case '(' -> {
                    output.add(new Token(TokenType.LPAREN, "(", tokenLine, tokenColumn, start, start + 1));
                    index++;
                    column++;
                }
                case ')' -> {
                    output.add(new Token(TokenType.RPAREN, ")", tokenLine, tokenColumn, start, start + 1));
                    index++;
                    column++;
                }
                case '[' -> {
                    output.add(new Token(TokenType.LBRACKET, "[", tokenLine, tokenColumn, start, start + 1));
                    index++;
                    column++;
                }
                case ']' -> {
                    output.add(new Token(TokenType.RBRACKET, "]", tokenLine, tokenColumn, start, start + 1));
                    index++;
                    column++;
                }
                case ':' -> {
                    output.add(new Token(TokenType.COLON, ":", tokenLine, tokenColumn, start, start + 1));
                    index++;
                    column++;
                }
                case ',' -> {
                    output.add(new Token(TokenType.COMMA, ",", tokenLine, tokenColumn, start, start + 1));
                    index++;
                    column++;
                }
                case '"', '\'' -> {
                    char quote = c;
                    index++;
                    column++;
                    boolean escaped = false;
                    while (index < input.length()) {
                        char current = input.charAt(index);
                        index++;
                        column++;
                        if (current == '\n') {
                            line++;
                            column = 1;
                        }
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
                    output.add(new Token(TokenType.STRING, input.substring(start, index), tokenLine, tokenColumn, start, index));
                }
                case '#' -> {
                    index++;
                    column++;
                    while (index < input.length() && isHexDigit(input.charAt(index))) {
                        index++;
                        column++;
                    }
                    output.add(new Token(TokenType.COLOR, input.substring(start, index), tokenLine, tokenColumn, start, index));
                }
                default -> {
                    if (isNumberStart(c, input, index)) {
                        index++;
                        column++;
                        while (index < input.length()) {
                            char current = input.charAt(index);
                            if (Character.isDigit(current) || current == '.') {
                                index++;
                                column++;
                            } else {
                                break;
                            }
                        }
                        output.add(new Token(TokenType.NUMBER, input.substring(start, index), tokenLine, tokenColumn, start, index));
                        continue;
                    }

                    if (isIdentifierStart(c)) {
                        index++;
                        column++;
                        while (index < input.length() && isIdentifierPart(input.charAt(index))) {
                            index++;
                            column++;
                        }
                        output.add(new Token(TokenType.IDENTIFIER, input.substring(start, index), tokenLine, tokenColumn, start, index));
                        continue;
                    }

                    if (isOperatorStart(c)) {
                        index++;
                        column++;
                        if (index < input.length()) {
                            String maybeTwo = input.substring(start, Math.min(start + 2, input.length()));
                            if (maybeTwo.equals("==") || maybeTwo.equals("!=") || maybeTwo.equals("<=") || maybeTwo.equals(">=") || maybeTwo.equals("&&") || maybeTwo.equals("||")) {
                                index = start + 2;
                                column = tokenColumn + 2;
                            }
                        }
                        output.add(new Token(TokenType.OPERATOR, input.substring(start, index), tokenLine, tokenColumn, start, index));
                        continue;
                    }

                    output.add(new Token(TokenType.OPERATOR, String.valueOf(c), tokenLine, tokenColumn, start, start + 1));
                    index++;
                    column++;
                }
            }
        }

        output.add(new Token(TokenType.EOF, "", line, column, input.length(), input.length()));
        return output;
    }

    private static boolean isHexDigit(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private static boolean isIdentifierStart(char c) {
        return Character.isLetter(c) || c == '_' || c == '$';
    }

    private static boolean isIdentifierPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '.' || c == '-' || c == '$';
    }

    private static boolean isNumberStart(char c, String source, int index) {
        if (Character.isDigit(c)) {
            return true;
        }
        return c == '-' && index + 1 < source.length() && Character.isDigit(source.charAt(index + 1));
    }

    private static boolean isOperatorStart(char c) {
        return c == '+' || c == '-' || c == '*' || c == '/' || c == '%' || c == '!' || c == '=' || c == '<' || c == '>' || c == '&' || c == '|';
    }

    private enum TokenType {
        IDENTIFIER,
        STRING,
        NUMBER,
        COLOR,
        LBRACE,
        RBRACE,
        LPAREN,
        RPAREN,
        LBRACKET,
        RBRACKET,
        COLON,
        COMMA,
        OPERATOR,
        EOF
    }

    private record Token(TokenType type, String text, int line, int column, int start, int end) {
    }

    public static final class ParseException extends RuntimeException {
        public ParseException(String message) {
            super(message);
        }
    }
}
