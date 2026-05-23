package com.hudifine.client.script;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class HudAst {
    private HudAst() {
    }

    public static final class HudScriptDocument {
        public final Map<String, String> meta = new LinkedHashMap<>();
        public final List<SettingDefinition> settings = new ArrayList<>();
        public WidgetNode widget;
        public final List<String> warnings = new ArrayList<>();

        public SettingDefinition findSetting(String id) {
            for (SettingDefinition definition : settings) {
                if (definition.id.equals(id)) {
                    return definition;
                }
            }
            return null;
        }
    }

    public enum SettingType {
        TOGGLE,
        COLOR,
        SLIDER,
        SELECT,
        TEXT,
        KEYBIND;

        public static SettingType parse(String rawType) {
            return switch (rawType.toLowerCase()) {
                case "toggle" -> TOGGLE;
                case "color" -> COLOR;
                case "slider" -> SLIDER;
                case "select" -> SELECT;
                case "text" -> TEXT;
                case "keybind" -> KEYBIND;
                default -> throw new IllegalArgumentException("Unknown setting type: " + rawType);
            };
        }
    }

    public static final class SettingDefinition {
        public SettingType type;
        public String label;
        public String id;
        public String description = "";
        public String defaultRaw = "";
        public String minRaw = "";
        public String maxRaw = "";
        public String stepRaw = "";
        public String maxLengthRaw = "";
        public final List<String> optionRaws = new ArrayList<>();

        public SettingDefinition(String label, SettingType type) {
            this.label = Objects.requireNonNull(label, "label");
            this.type = Objects.requireNonNull(type, "type");
            this.id = toSafeId(label);
        }

        private static String toSafeId(String label) {
            String candidate = label.toLowerCase().replaceAll("[^a-z0-9]+", "_");
            if (candidate.startsWith("_")) {
                candidate = candidate.substring(1);
            }
            if (candidate.endsWith("_")) {
                candidate = candidate.substring(0, candidate.length() - 1);
            }
            if (candidate.isBlank()) {
                return "setting";
            }
            return candidate;
        }
    }

    public sealed interface Node permits WidgetNode, ConditionalNode, EventNode {
    }

    public static final class WidgetNode implements Node {
        public final String type;
        public final Map<String, String> props = new LinkedHashMap<>();
        public final List<Node> children = new ArrayList<>();

        public WidgetNode(String type) {
            this.type = type;
        }
    }

    public static final class ConditionalNode implements Node {
        public final List<ConditionalBranch> branches = new ArrayList<>();
        public final List<Node> elseChildren = new ArrayList<>();
    }

    public static final class ConditionalBranch {
        public String expression;
        public final List<Node> children = new ArrayList<>();

        public ConditionalBranch(String expression) {
            this.expression = expression;
        }
    }

    public enum EventType {
        TICK,
        FRAME,
        EVENT_CONDITION,
        KEYPRESS,
        KEYHOLD,
        KEYRELEASE
    }

    public static final class EventNode implements Node {
        public EventType type;
        public String expression = "";
        public final List<Node> children = new ArrayList<>();
    }
}
