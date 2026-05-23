package com.hudifine.client.hud;

import com.hudifine.client.script.HudAst;
import com.hudifine.client.script.HudExpressionEngine;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public final class HudRenderer {
    private static final double MIN_MANUAL_SCALE = 0.25;
    private static final double MAX_MANUAL_SCALE = 6.0;
    private static final double[] CURVE_AA_SAMPLE_OFFSETS = {1.0 / 6.0, 0.5, 5.0 / 6.0};
    private static final int CURVE_AA_SAMPLE_COUNT = CURVE_AA_SAMPLE_OFFSETS.length * CURVE_AA_SAMPLE_OFFSETS.length;

    private final Minecraft client;
    private final HudDataSource dataSource;

    public HudRenderer(Minecraft client, HudDataSource dataSource) {
        this.client = client;
        this.dataSource = dataSource;
    }

    public void renderWidget(
        GuiGraphicsExtractor context,
        HudWidgetInstance widget,
        int screenWidth,
        int screenHeight,
        int mouseX,
        int mouseY,
        boolean editorMode,
        boolean selected,
        boolean hovered,
        boolean forceHiddenContent
    ) {
        if (widget.document.widget == null) {
            return;
        }

        Resolver resolver = new Resolver(widget);

        boolean scriptVisible = true;
        if (widget.document.widget.props.containsKey("visible")) {
            scriptVisible = HudExpressionEngine.evalBoolean(widget.document.widget.props.get("visible"), resolver, true);
        }
        widget.lastResolvedVisible = scriptVisible;

        Size measured = measureNode(widget.document.widget, resolver, widget);
        if (measured.width <= 0 || measured.height <= 0) {
            widget.lastWidth = 0;
            widget.lastHeight = 0;
            return;
        }

        double manualScale = widget.hasManualPosition
            ? clamp((widget.manualScaleX + widget.manualScaleY) * 0.5, MIN_MANUAL_SCALE, MAX_MANUAL_SCALE)
            : 1.0;

        widget.manualScaleX = manualScale;
        widget.manualScaleY = manualScale;

        double desiredWidth = measured.width * manualScale;
        double desiredHeight = measured.height * manualScale;

        double budget = (double) screenWidth * (double) screenHeight * 0.30;
        double area = desiredWidth * desiredHeight;

        double budgetScale = 1.0;
        if (area > budget && budget > 0.0) {
            budgetScale = Math.sqrt(budget / area);
        }

        double finalScaleX = manualScale * budgetScale;
        double finalScaleY = manualScale * budgetScale;

        int finalWidth = Math.max(1, (int) Math.round(measured.width * finalScaleX));
        int finalHeight = Math.max(1, (int) Math.round(measured.height * finalScaleY));

        double baseX = resolveAnchorX(widget.document.widget, finalWidth, screenWidth, resolver);
        double baseY = resolveAnchorY(widget.document.widget, finalHeight, screenHeight, resolver);

        baseX += evalNumber(widget.document.widget.props.getOrDefault("offsetX", "0"), resolver, 0.0);
        baseY += evalNumber(widget.document.widget.props.getOrDefault("offsetY", "0"), resolver, 0.0);

        if (widget.hasManualPosition) {
            if (widget.hasManualAnchor) {
                baseX = resolveAnchorX(widget.manualAnchor, finalWidth, screenWidth) + widget.manualOffsetX;
                baseY = resolveAnchorY(widget.manualAnchor, finalHeight, screenHeight) + widget.manualOffsetY;
            } else {
                baseX = widget.manualX;
                baseY = widget.manualY;
            }

            widget.manualX = baseX;
            widget.manualY = baseY;

            if (widget.hasManualAnchor) {
                double anchorBaseX = resolveAnchorX(widget.manualAnchor, finalWidth, screenWidth);
                double anchorBaseY = resolveAnchorY(widget.manualAnchor, finalHeight, screenHeight);
                widget.manualOffsetX = baseX - anchorBaseX;
                widget.manualOffsetY = baseY - anchorBaseY;
            }
        }

        double opacity = evalNumber(widget.document.widget.props.getOrDefault("opacity", "1.0"), resolver, 1.0);
        opacity = Math.max(0.0, Math.min(1.0, opacity));

        widget.lastX = (int) Math.round(baseX);
        widget.lastY = (int) Math.round(baseY);
        widget.lastWidth = finalWidth;
        widget.lastHeight = finalHeight;

        boolean renderContent = scriptVisible && !forceHiddenContent;

        if (renderContent) {
            context.pose().pushMatrix();
            context.pose().translate((float) baseX, (float) baseY);
            context.pose().scale((float) finalScaleX, (float) finalScaleY);
            renderNode(widget.document.widget, context, 0, 0, resolver, widget, opacity);
            context.pose().popMatrix();
        }

        if (editorMode) {
            boolean showOff = !renderContent;
            boolean drawBorder = selected || hovered || showOff;
            if (drawBorder) {
                int alpha = selected ? 0xFF : (hovered ? 0x78 : 0x5C);
                int border = (alpha << 24) | 0xFFFFFF;
                context.fill(widget.lastX, widget.lastY, widget.lastX + widget.lastWidth, widget.lastY + 1, border);
                context.fill(widget.lastX, widget.lastY + widget.lastHeight - 1, widget.lastX + widget.lastWidth, widget.lastY + widget.lastHeight, border);
                context.fill(widget.lastX, widget.lastY, widget.lastX + 1, widget.lastY + widget.lastHeight, border);
                context.fill(widget.lastX + widget.lastWidth - 1, widget.lastY, widget.lastX + widget.lastWidth, widget.lastY + widget.lastHeight, border);
            }

            if (selected) {
                drawResizeHandles(context, widget.lastX, widget.lastY, widget.lastWidth, widget.lastHeight, mouseX, mouseY);
            }

            if (showOff) {
                String off = "OFF";
                int labelX = widget.lastX + Math.max(2, (widget.lastWidth - client.font.width(off)) / 2);
                int labelY = widget.lastY + Math.max(2, (widget.lastHeight - client.font.lineHeight) / 2);
                context.text(client.font, off, labelX, labelY, 0xFFFF6B6B, true);
            }
        }
    }

    private void drawResizeHandles(GuiGraphicsExtractor context, int x, int y, int width, int height, int mouseX, int mouseY) {
        int radius = HudScriptManager.RESIZE_HANDLE_RADIUS;

        int left = x;
        int top = y;
        int right = x + width - 1;
        int bottom = y + height - 1;

        drawResizeHandleSquare(context, left, top, radius);
        drawResizeHandleSquare(context, right, top, radius);
        drawResizeHandleSquare(context, left, bottom, radius);
        drawResizeHandleSquare(context, right, bottom, radius);
    }

    private void drawResizeHandleSquare(
        GuiGraphicsExtractor context,
        int centerX,
        int centerY,
        int radius
    ) {
        int fill = 0xFFD9E5F0;

        int x1 = centerX - radius;
        int y1 = centerY - radius;
        int x2 = centerX + radius + 1;
        int y2 = centerY + radius + 1;

        context.fill(x1, y1, x2, y2, fill);
    }

    private void renderNode(HudAst.Node node, GuiGraphicsExtractor context, int x, int y, Resolver resolver, HudWidgetInstance widget, double opacity) {
        if (node instanceof HudAst.WidgetNode widgetNode) {
            renderWidgetNode(widgetNode, context, x, y, resolver, widget, opacity);
            return;
        }

        if (node instanceof HudAst.ConditionalNode conditionalNode) {
            List<HudAst.Node> active = resolveConditionalChildren(conditionalNode, resolver, widget);
            for (HudAst.Node child : active) {
                renderNode(child, context, x, y, resolver, widget, opacity);
            }
            return;
        }

        if (node instanceof HudAst.EventNode eventNode) {
            if (isEventActive(eventNode, resolver, widget)) {
                for (HudAst.Node child : eventNode.children) {
                    renderNode(child, context, x, y, resolver, widget, opacity);
                }
            }
        }
    }

    private void renderWidgetNode(HudAst.WidgetNode node, GuiGraphicsExtractor context, int x, int y, Resolver resolver, HudWidgetInstance widget, double parentOpacity) {
        String type = node.type.toLowerCase(Locale.ROOT);
        if (isContainer(type)) {
            renderContainer(node, context, x, y, resolver, widget, parentOpacity);
            return;
        }

        renderLeaf(node, context, x, y, resolver, parentOpacity);
    }

    private void renderContainer(HudAst.WidgetNode node, GuiGraphicsExtractor context, int x, int y, Resolver resolver, HudWidgetInstance widget, double parentOpacity) {
        String direction = value(node, "direction", "column", resolver).toLowerCase(Locale.ROOT);
        int padding = (int) Math.round(evalNumber(node.props.getOrDefault("padding", "0"), resolver, 0.0));
        int gap = (int) Math.round(evalNumber(node.props.getOrDefault("gap", "0"), resolver, 0.0));

        List<HudAst.Node> children = flattenChildren(node.children, resolver, widget);
        List<Size> childSizes = new ArrayList<>();

        int contentWidth = 0;
        int contentHeight = 0;
        for (HudAst.Node child : children) {
            Size size = measureNode(child, resolver, widget);
            childSizes.add(size);
            if (direction.equals("row")) {
                contentWidth += size.width;
                contentHeight = Math.max(contentHeight, size.height);
            } else {
                contentHeight += size.height;
                contentWidth = Math.max(contentWidth, size.width);
            }
        }

        if (!children.isEmpty()) {
            if (direction.equals("row")) {
                contentWidth += gap * (children.size() - 1);
            } else {
                contentHeight += gap * (children.size() - 1);
            }
        }

        int width = (int) Math.round(evalNumber(node.props.getOrDefault("width", "0"), resolver, 0.0));
        int height = (int) Math.round(evalNumber(node.props.getOrDefault("height", "0"), resolver, 0.0));

        if (width <= 0) {
            width = contentWidth + padding * 2;
        }
        if (height <= 0 || value(node, "height", "auto", resolver).equalsIgnoreCase("auto")) {
            height = contentHeight + padding * 2;
        }

        int backgroundColor = parseColor(node.props.getOrDefault("background", "transparent"), resolver, 0x00000000);
        int borderColor = parseBorderColor(node.props.getOrDefault("border", ""), resolver);
        int borderWidth = parseBorderWidth(node.props.getOrDefault("border", ""), resolver);
        int borderRadius = (int) Math.round(evalNumber(node.props.getOrDefault("borderRadius", "0"), resolver, 0.0));
        borderRadius = clampRadius(borderRadius, width, height);

        int finalBackground = applyOpacity(backgroundColor, parentOpacity * evalNumber(node.props.getOrDefault("opacity", "1.0"), resolver, 1.0));
        if ((finalBackground >>> 24) > 0) {
            fillRoundedRect(context, x, y, x + width, y + height, finalBackground, borderRadius);
        }

        if (borderWidth > 0 && (borderColor >>> 24) > 0) {
            int finalBorder = applyOpacity(borderColor, parentOpacity);
            for (int i = 0; i < borderWidth; i++) {
                drawRoundedOutline(
                    context,
                    x + i,
                    y + i,
                    x + width - i,
                    y + height - i,
                    finalBorder,
                    Math.max(0, borderRadius - i)
                );
            }
        }

        int cursorX = x + padding;
        int cursorY = y + padding;

        for (int i = 0; i < children.size(); i++) {
            HudAst.Node child = children.get(i);
            Size size = childSizes.get(i);

            renderNode(child, context, cursorX, cursorY, resolver, widget, parentOpacity);

            if (direction.equals("row")) {
                cursorX += size.width + gap;
            } else {
                cursorY += size.height + gap;
            }
        }
    }

    private void renderLeaf(HudAst.WidgetNode node, GuiGraphicsExtractor context, int x, int y, Resolver resolver, double parentOpacity) {
        String type = node.type.toLowerCase(Locale.ROOT);

        switch (type) {
            case "text" -> renderText(node, context, x, y, resolver, parentOpacity);
            case "bar" -> renderBar(node, context, x, y, resolver, parentOpacity);
            case "icon" -> renderIcon(node, context, x, y, resolver);
            case "circle" -> renderCircle(node, context, x, y, resolver, parentOpacity);
            case "image" -> renderImage(node, context, x, y, resolver, parentOpacity);
            case "keyindicator" -> renderKeyIndicator(node, context, x, y, resolver, parentOpacity);
            case "separator" -> renderSeparator(node, context, x, y, resolver, parentOpacity);
            case "spacer" -> {
                // Spacer only affects layout.
            }
            default -> {
                // Unknown node types are ignored for stability.
            }
        }
    }

    private void renderText(HudAst.WidgetNode node, GuiGraphicsExtractor context, int x, int y, Resolver resolver, double parentOpacity) {
        Font textRenderer = client.font;
        String value = HudExpressionEngine.evalTemplate(node.props.getOrDefault("value", ""), resolver);

        if (HudExpressionEngine.evalBoolean(node.props.getOrDefault("uppercase", "false"), resolver, false)) {
            value = value.toUpperCase(Locale.ROOT);
        }

        int truncate = (int) Math.round(evalNumber(node.props.getOrDefault("truncate", "0"), resolver, 0.0));
        if (truncate > 0 && value.length() > truncate) {
            value = value.substring(0, truncate);
        }

        int color = parseColor(node.props.getOrDefault("color", "#FFFFFF"), resolver, 0xFFFFFFFF);
        int finalColor = applyOpacity(color, parentOpacity);

        double fontSize = evalNumber(node.props.getOrDefault("fontSize", "12"), resolver, 12.0);
        double scale = Math.max(0.5, fontSize / 9.0);

        context.pose().pushMatrix();
        context.pose().translate((float) x, (float) y);
        context.pose().scale((float) scale, (float) scale);

        boolean shadow = HudExpressionEngine.evalBoolean(node.props.getOrDefault("shadow", "false"), resolver, false);
        context.text(textRenderer, value, 0, 0, finalColor, shadow);

        context.pose().popMatrix();
    }

    private void renderBar(HudAst.WidgetNode node, GuiGraphicsExtractor context, int x, int y, Resolver resolver, double parentOpacity) {
        double value = evalNumber(node.props.getOrDefault("value", "0"), resolver, 0.0);
        double max = evalNumber(node.props.getOrDefault("max", "1"), resolver, 1.0);
        int width = (int) Math.round(evalNumber(node.props.getOrDefault("width", "100"), resolver, 100.0));
        int height = (int) Math.round(evalNumber(node.props.getOrDefault("height", "8"), resolver, 8.0));
        int borderRadius = (int) Math.round(evalNumber(node.props.getOrDefault("borderRadius", "0"), resolver, 0.0));
        borderRadius = clampRadius(borderRadius, width, height);

        int backgroundColor = applyOpacity(parseColor(node.props.getOrDefault("backgroundColor", "#00000066"), resolver, 0x66000000), parentOpacity);
        int fillColor = applyOpacity(parseColor(node.props.getOrDefault("fillColor", "#FFFFFF"), resolver, 0xFFFFFFFF), parentOpacity);

        fillRoundedRect(context, x, y, x + width, y + height, backgroundColor, borderRadius);

        if (max <= 0.0) {
            return;
        }

        double progress = Math.max(0.0, Math.min(1.0, value / max));
        int filled = (int) Math.round(width * progress);

        String direction = value(node, "direction", "left-to-right", resolver).toLowerCase(Locale.ROOT);
        switch (direction) {
            case "right-to-left" -> {
                int fillX = x + width - filled;
                fillRoundedRect(context, fillX, y, x + width, y + height, fillColor, clampRadius(borderRadius, filled, height));
            }
            case "bottom-to-top" -> {
                int fillHeight = (int) Math.round(height * progress);
                int fillY = y + height - fillHeight;
                fillRoundedRect(context, x, fillY, x + width, y + height, fillColor, clampRadius(borderRadius, width, fillHeight));
            }
            case "top-to-bottom" -> {
                int fillHeight = (int) Math.round(height * progress);
                fillRoundedRect(context, x, y, x + width, y + fillHeight, fillColor, clampRadius(borderRadius, width, fillHeight));
            }
            default -> fillRoundedRect(context, x, y, x + filled, y + height, fillColor, clampRadius(borderRadius, filled, height));
        }
    }

    private void renderIcon(HudAst.WidgetNode node, GuiGraphicsExtractor context, int x, int y, Resolver resolver) {
        String itemId = HudExpressionEngine.evalTemplate(node.props.getOrDefault("item", "minecraft:air"), resolver);
        int size = (int) Math.round(evalNumber(node.props.getOrDefault("size", "16"), resolver, 16.0));

        Identifier identifier = Identifier.tryParse(itemId);
        if (identifier == null) {
            return;
        }

        Item item = BuiltInRegistries.ITEM.getOptional(identifier).orElse(null);
        if (item == null) {
            return;
        }

        ItemStack stack = new ItemStack(item);

        double scale = Math.max(0.25, size / 16.0);
        context.pose().pushMatrix();
        context.pose().translate((float) x, (float) y);
        context.pose().scale((float) scale, (float) scale);
        context.item(stack, 0, 0);
        context.pose().popMatrix();
    }

    private void renderCircle(HudAst.WidgetNode node, GuiGraphicsExtractor context, int x, int y, Resolver resolver, double parentOpacity) {
        // Circle fallback renderer: progress panel with centered percentage text.
        double value = evalNumber(node.props.getOrDefault("value", "0"), resolver, 0.0);
        int radius = (int) Math.round(evalNumber(node.props.getOrDefault("radius", "12"), resolver, 12.0));
        int diameter = Math.max(8, radius * 2);

        int backgroundColor = applyOpacity(parseColor(node.props.getOrDefault("backgroundColor", "#FFFFFF22"), resolver, 0x22FFFFFF), parentOpacity);
        int color = applyOpacity(parseColor(node.props.getOrDefault("color", "#FFFFFF"), resolver, 0xFFFFFFFF), parentOpacity);

        context.fill(x, y, x + diameter, y + diameter, backgroundColor);

        int fillHeight = (int) Math.round(diameter * Math.max(0.0, Math.min(1.0, value)));
        context.fill(x, y + diameter - fillHeight, x + diameter, y + diameter, color);

        String text = (int) Math.round(Math.max(0.0, Math.min(1.0, value)) * 100.0) + "%";
        int textWidth = client.font.width(text);
        context.text(client.font, text, x + (diameter - textWidth) / 2, y + (diameter - client.font.lineHeight) / 2, 0xFFFFFFFF, true);
    }

    private void renderImage(HudAst.WidgetNode node, GuiGraphicsExtractor context, int x, int y, Resolver resolver, double parentOpacity) {
        int width = (int) Math.round(evalNumber(node.props.getOrDefault("width", "16"), resolver, 16.0));
        int height = (int) Math.round(evalNumber(node.props.getOrDefault("height", "16"), resolver, 16.0));
        int tint = applyOpacity(parseColor(node.props.getOrDefault("tint", "#FFFFFF66"), resolver, 0x66FFFFFF), parentOpacity);

        // Safe fallback: visible placeholder box when custom texture rendering is not available.
        context.fill(x, y, x + width, y + height, tint);
        context.text(client.font, "IMG", x + 2, y + 2, 0xFF000000);
    }

    private void renderKeyIndicator(HudAst.WidgetNode node, GuiGraphicsExtractor context, int x, int y, Resolver resolver, double parentOpacity) {
        String label = HudExpressionEngine.normalizeRawValue(node.props.getOrDefault("label", ""));
        boolean active = HudExpressionEngine.evalBoolean(node.props.getOrDefault("key", "false"), resolver, false);

        int size = (int) Math.round(evalNumber(node.props.getOrDefault("size", "18"), resolver, 18.0));
        int width = (int) Math.round(evalNumber(node.props.getOrDefault("width", String.valueOf(size)), resolver, size));
        int borderRadius = (int) Math.round(evalNumber(node.props.getOrDefault("borderRadius", "0"), resolver, 0.0));
        borderRadius = clampRadius(borderRadius, width, size);
        int activeColor = applyOpacity(parseColor(node.props.getOrDefault("activeColor", "#FFFFFF"), resolver, 0xFFFFFFFF), parentOpacity);
        int inactiveColor = applyOpacity(parseColor(node.props.getOrDefault("inactiveColor", "#FFFFFF44"), resolver, 0x44FFFFFF), parentOpacity);

        fillRoundedRect(context, x, y, x + width, y + size, active ? activeColor : inactiveColor, borderRadius);

        double fontSize = evalNumber(node.props.getOrDefault("fontSize", "9"), resolver, 9.0);
        double scale = Math.max(0.5, fontSize / 9.0);
        int textWidth = client.font.width(label);
        int textColor = applyOpacity(parseColor(node.props.getOrDefault("color", "#000000"), resolver, 0xFF000000), parentOpacity);

        context.pose().pushMatrix();
        context.pose().translate((float) (x + (width - (textWidth * scale)) / 2.0), (float) (y + (size - (client.font.lineHeight * scale)) / 2.0));
        context.pose().scale((float) scale, (float) scale);
        context.text(client.font, label, 0, 0, textColor, HudExpressionEngine.evalBoolean(node.props.getOrDefault("shadow", "false"), resolver, false));
        context.pose().popMatrix();
    }

    private void renderSeparator(HudAst.WidgetNode node, GuiGraphicsExtractor context, int x, int y, Resolver resolver, double parentOpacity) {
        String direction = value(node, "direction", "horizontal", resolver).toLowerCase(Locale.ROOT);
        int thickness = (int) Math.round(evalNumber(node.props.getOrDefault("thickness", "1"), resolver, 1.0));
        int length = (int) Math.round(evalNumber(node.props.getOrDefault("length", "80"), resolver, 80.0));
        int color = applyOpacity(parseColor(node.props.getOrDefault("color", "#FFFFFF22"), resolver, 0x22FFFFFF), parentOpacity);

        if (direction.equals("vertical")) {
            context.fill(x, y, x + thickness, y + Math.max(1, length), color);
        } else {
            context.fill(x, y, x + Math.max(1, length), y + thickness, color);
        }
    }

    private Size measureNode(HudAst.Node node, Resolver resolver, HudWidgetInstance widget) {
        if (node instanceof HudAst.WidgetNode widgetNode) {
            return measureWidgetNode(widgetNode, resolver, widget);
        }

        if (node instanceof HudAst.ConditionalNode conditionalNode) {
            List<HudAst.Node> active = resolveConditionalChildren(conditionalNode, resolver, widget);
            return measureNodeList(active, resolver, widget, "column", 0, 0);
        }

        if (node instanceof HudAst.EventNode eventNode) {
            if (isEventActive(eventNode, resolver, widget)) {
                return measureNodeList(eventNode.children, resolver, widget, "column", 0, 0);
            }
            return new Size(0, 0);
        }

        return new Size(0, 0);
    }

    private Size measureWidgetNode(HudAst.WidgetNode node, Resolver resolver, HudWidgetInstance widget) {
        String type = node.type.toLowerCase(Locale.ROOT);
        if (isContainer(type)) {
            String direction = value(node, "direction", "column", resolver).toLowerCase(Locale.ROOT);
            int padding = (int) Math.round(evalNumber(node.props.getOrDefault("padding", "0"), resolver, 0.0));
            int gap = (int) Math.round(evalNumber(node.props.getOrDefault("gap", "0"), resolver, 0.0));

            List<HudAst.Node> flattened = flattenChildren(node.children, resolver, widget);
            Size children = measureNodeList(flattened, resolver, widget, direction, gap, padding);

            int width = (int) Math.round(evalNumber(node.props.getOrDefault("width", "0"), resolver, 0.0));
            int height = (int) Math.round(evalNumber(node.props.getOrDefault("height", "0"), resolver, 0.0));
            String heightRaw = value(node, "height", "auto", resolver);

            if (width <= 0) {
                width = children.width;
            }
            if (height <= 0 || heightRaw.equalsIgnoreCase("auto")) {
                height = children.height;
            }

            return new Size(Math.max(0, width), Math.max(0, height));
        }

        return measureLeaf(node, resolver);
    }

    private Size measureLeaf(HudAst.WidgetNode node, Resolver resolver) {
        String type = node.type.toLowerCase(Locale.ROOT);
        return switch (type) {
            case "text" -> {
                String value = HudExpressionEngine.evalTemplate(node.props.getOrDefault("value", ""), resolver);
                if (HudExpressionEngine.evalBoolean(node.props.getOrDefault("uppercase", "false"), resolver, false)) {
                    value = value.toUpperCase(Locale.ROOT);
                }
                int truncate = (int) Math.round(evalNumber(node.props.getOrDefault("truncate", "0"), resolver, 0.0));
                if (truncate > 0 && value.length() > truncate) {
                    value = value.substring(0, truncate);
                }

                double fontSize = evalNumber(node.props.getOrDefault("fontSize", "12"), resolver, 12.0);
                double scale = Math.max(0.5, fontSize / 9.0);
                int width = (int) Math.ceil(client.font.width(value) * scale);
                int height = (int) Math.ceil(client.font.lineHeight * scale);
                yield new Size(width, height);
            }
            case "bar" -> new Size(
                (int) Math.round(evalNumber(node.props.getOrDefault("width", "100"), resolver, 100.0)),
                (int) Math.round(evalNumber(node.props.getOrDefault("height", "8"), resolver, 8.0))
            );
            case "icon" -> {
                int size = (int) Math.round(evalNumber(node.props.getOrDefault("size", "16"), resolver, 16.0));
                yield new Size(size, size);
            }
            case "circle" -> {
                int radius = (int) Math.round(evalNumber(node.props.getOrDefault("radius", "12"), resolver, 12.0));
                yield new Size(radius * 2, radius * 2);
            }
            case "image" -> new Size(
                (int) Math.round(evalNumber(node.props.getOrDefault("width", "16"), resolver, 16.0)),
                (int) Math.round(evalNumber(node.props.getOrDefault("height", "16"), resolver, 16.0))
            );
            case "keyindicator" -> {
                int size = (int) Math.round(evalNumber(node.props.getOrDefault("size", "18"), resolver, 18.0));
                int width = (int) Math.round(evalNumber(node.props.getOrDefault("width", String.valueOf(size)), resolver, size));
                yield new Size(width, size);
            }
            case "separator" -> {
                String direction = value(node, "direction", "horizontal", resolver).toLowerCase(Locale.ROOT);
                int thickness = (int) Math.round(evalNumber(node.props.getOrDefault("thickness", "1"), resolver, 1.0));
                int length = (int) Math.round(evalNumber(node.props.getOrDefault("length", "80"), resolver, 80.0));
                if (direction.equals("vertical")) {
                    yield new Size(Math.max(1, thickness), Math.max(1, length));
                }
                yield new Size(Math.max(1, length), Math.max(1, thickness));
            }
            case "spacer" -> {
                int size = (int) Math.round(evalNumber(node.props.getOrDefault("size", "8"), resolver, 8.0));
                yield new Size(size, size);
            }
            default -> new Size(0, 0);
        };
    }

    private Size measureNodeList(List<HudAst.Node> nodes, Resolver resolver, HudWidgetInstance widget, String direction, int gap, int padding) {
        int width = 0;
        int height = 0;

        for (HudAst.Node child : nodes) {
            Size size = measureNode(child, resolver, widget);
            if (direction.equals("row")) {
                width += size.width;
                height = Math.max(height, size.height);
            } else {
                height += size.height;
                width = Math.max(width, size.width);
            }
        }

        if (!nodes.isEmpty()) {
            if (direction.equals("row")) {
                width += gap * (nodes.size() - 1);
            } else {
                height += gap * (nodes.size() - 1);
            }
        }

        width += padding * 2;
        height += padding * 2;

        return new Size(Math.max(0, width), Math.max(0, height));
    }

    private List<HudAst.Node> flattenChildren(List<HudAst.Node> children, Resolver resolver, HudWidgetInstance widget) {
        List<HudAst.Node> flattened = new ArrayList<>();
        for (HudAst.Node node : children) {
            if (node instanceof HudAst.ConditionalNode conditionalNode) {
                flattened.addAll(resolveConditionalChildren(conditionalNode, resolver, widget));
            } else if (node instanceof HudAst.EventNode eventNode) {
                if (isEventActive(eventNode, resolver, widget)) {
                    flattened.addAll(eventNode.children);
                }
            } else {
                flattened.add(node);
            }
        }
        return sortByOrder(flattened, resolver);
    }

    private List<HudAst.Node> sortByOrder(List<HudAst.Node> nodes, Resolver resolver) {
        List<OrderEntry> entries = new ArrayList<>(nodes.size());
        for (int i = 0; i < nodes.size(); i++) {
            HudAst.Node node = nodes.get(i);
            entries.add(new OrderEntry(node, nodeOrder(node, resolver), i));
        }

        entries.sort(
            Comparator.comparingInt(OrderEntry::order)
                .thenComparingInt(OrderEntry::index)
        );

        List<HudAst.Node> sorted = new ArrayList<>(nodes.size());
        for (OrderEntry entry : entries) {
            sorted.add(entry.node());
        }
        return sorted;
    }

    private int nodeOrder(HudAst.Node node, Resolver resolver) {
        if (!(node instanceof HudAst.WidgetNode widgetNode)) {
            return 0;
        }

        return (int) Math.round(evalNumber(widgetNode.props.getOrDefault("order", "0"), resolver, 0.0));
    }

    private List<HudAst.Node> resolveConditionalChildren(HudAst.ConditionalNode conditionalNode, Resolver resolver, HudWidgetInstance widget) {
        for (HudAst.ConditionalBranch branch : conditionalNode.branches) {
            if (HudExpressionEngine.evalBoolean(branch.expression, resolver, false)) {
                return branch.children;
            }
        }
        return conditionalNode.elseChildren;
    }

    private boolean isEventActive(HudAst.EventNode eventNode, Resolver resolver, HudWidgetInstance widget) {
        String key = eventNode.type + ":" + eventNode.expression;

        return switch (eventNode.type) {
            case TICK, FRAME -> true;
            case EVENT_CONDITION -> HudExpressionEngine.evalBoolean(eventNode.expression, resolver, false);
            case KEYHOLD -> HudExpressionEngine.evalBoolean(eventNode.expression, resolver, false);
            case KEYPRESS -> {
                boolean current = HudExpressionEngine.evalBoolean(eventNode.expression, resolver, false);
                boolean previous = widget.eventState.getOrDefault(key, false);
                widget.eventState.put(key, current);
                yield current && !previous;
            }
            case KEYRELEASE -> {
                boolean current = HudExpressionEngine.evalBoolean(eventNode.expression, resolver, false);
                boolean previous = widget.eventState.getOrDefault(key, false);
                widget.eventState.put(key, current);
                yield !current && previous;
            }
        };
    }

    private boolean isContainer(String type) {
        return type.equals("widget") || type.equals("group");
    }

    private static double resolveAnchorX(HudAst.WidgetNode node, int width, int screenWidth, Resolver resolver) {
        String anchor = value(node, "anchor", "top-left", resolver).toLowerCase(Locale.ROOT);
        return resolveAnchorX(anchor, width, screenWidth);
    }

    private static double resolveAnchorX(String anchor, int width, int screenWidth) {
        String normalized = anchor == null ? "top-left" : anchor.toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "top-center", "center", "middle-center", "bottom-center" -> (screenWidth - width) / 2.0;
            case "top-right", "middle-right", "bottom-right" -> screenWidth - width;
            default -> 0.0;
        };
    }

    private static double resolveAnchorY(HudAst.WidgetNode node, int height, int screenHeight, Resolver resolver) {
        String anchor = value(node, "anchor", "top-left", resolver).toLowerCase(Locale.ROOT);
        return resolveAnchorY(anchor, height, screenHeight);
    }

    private static double resolveAnchorY(String anchor, int height, int screenHeight) {
        String normalized = anchor == null ? "top-left" : anchor.toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "middle-left", "center", "middle-right", "middle-center" -> (screenHeight - height) / 2.0;
            case "bottom-left", "bottom-center", "bottom-right" -> screenHeight - height;
            default -> 0.0;
        };
    }

    private static String value(HudAst.WidgetNode node, String key, String fallback, Resolver resolver) {
        String raw = node.props.get(key);
        if (raw == null) {
            return fallback;
        }
        String evaluated = HudExpressionEngine.evalTemplate(raw, resolver);
        if (evaluated == null || evaluated.isBlank()) {
            return fallback;
        }
        return evaluated;
    }

    private static double evalNumber(String expression, Resolver resolver, double fallback) {
        return HudExpressionEngine.asDouble(HudExpressionEngine.eval(expression, resolver), fallback);
    }

    private static int parseColor(String raw, Resolver resolver, int fallback) {
        String value = HudExpressionEngine.evalTemplate(raw, resolver).trim();
        if (value.equalsIgnoreCase("transparent")) {
            return 0x00000000;
        }

        if (value.startsWith("#")) {
            String hex = value.substring(1);
            if (hex.length() == 3) {
                hex = "" + hex.charAt(0) + hex.charAt(0)
                    + hex.charAt(1) + hex.charAt(1)
                    + hex.charAt(2) + hex.charAt(2);
            }
            if (hex.length() == 6) {
                try {
                    return (int) (0xFF000000L | Long.parseLong(hex, 16));
                } catch (NumberFormatException ignored) {
                    return fallback;
                }
            }
            if (hex.length() == 8) {
                try {
                    long rgba = Long.parseLong(hex, 16);
                    int r = (int) ((rgba >> 24) & 0xFF);
                    int g = (int) ((rgba >> 16) & 0xFF);
                    int b = (int) ((rgba >> 8) & 0xFF);
                    int a = (int) (rgba & 0xFF);
                    return (a << 24) | (r << 16) | (g << 8) | b;
                } catch (NumberFormatException ignored) {
                    return fallback;
                }
            }
        }

        return fallback;
    }

    private static int parseBorderWidth(String borderRaw, Resolver resolver) {
        String border = HudExpressionEngine.evalTemplate(borderRaw, resolver).trim();
        if (border.isEmpty()) {
            return 0;
        }
        String[] parts = border.split("\\s+");
        if (parts.length == 0) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(parts[0]));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static int parseBorderColor(String borderRaw, Resolver resolver) {
        String border = HudExpressionEngine.evalTemplate(borderRaw, resolver).trim();
        if (border.isEmpty()) {
            return 0;
        }
        String[] parts = border.split("\\s+");
        if (parts.length < 2) {
            return 0;
        }
        return parseColor(parts[1], resolver, 0xFFFFFFFF);
    }

    private static int clampRadius(int radius, int width, int height) {
        int maxRadius = Math.max(0, Math.min(width, height) / 2);
        return Math.max(0, Math.min(radius, maxRadius));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(value, max));
    }

    private static double squaredDistance(double x1, double y1, double x2, double y2) {
        double dx = x1 - x2;
        double dy = y1 - y2;
        return (dx * dx) + (dy * dy);
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static int withAlphaCoverage(int argb, double coverage) {
        int alpha = (argb >>> 24) & 0xFF;
        int coveredAlpha = (int) Math.round(alpha * clamp01(coverage));
        if (coveredAlpha <= 0) {
            return 0;
        }
        return (coveredAlpha << 24) | (argb & 0x00FFFFFF);
    }

    private static void drawPixel(GuiGraphicsExtractor context, int x, int y, int argb, double coverage) {
        int color = withAlphaCoverage(argb, coverage);
        if (((color >>> 24) & 0xFF) <= 0) {
            return;
        }
        context.fill(x, y, x + 1, y + 1, color);
    }

    private static boolean isCornerSampleInside(
        int radius,
        int localX,
        int localY,
        Corner corner,
        double sampleOffsetX,
        double sampleOffsetY,
        double radiusSquared
    ) {
        if (radius <= 0) {
            return false;
        }

        double px = localX + sampleOffsetX;
        double py = localY + sampleOffsetY;

        double dx = switch (corner) {
            case TOP_LEFT, BOTTOM_LEFT -> radius - px;
            case TOP_RIGHT, BOTTOM_RIGHT -> px;
        };

        double dy = switch (corner) {
            case TOP_LEFT, TOP_RIGHT -> radius - py;
            case BOTTOM_LEFT, BOTTOM_RIGHT -> py;
        };

        return (dx * dx) + (dy * dy) <= radiusSquared;
    }

    private enum Corner {
        TOP_LEFT,
        TOP_RIGHT,
        BOTTOM_LEFT,
        BOTTOM_RIGHT
    }

    private static boolean isLeftCorner(Corner corner) {
        return corner == Corner.TOP_LEFT || corner == Corner.BOTTOM_LEFT;
    }

    private static double cornerRowXExtent(int radius, int localY, Corner corner, double boundaryRadius) {
        if (boundaryRadius <= 0.0) {
            return -1.0;
        }

        double py = localY + 0.5;
        double dy = switch (corner) {
            case TOP_LEFT, TOP_RIGHT -> radius - py;
            case BOTTOM_LEFT, BOTTOM_RIGHT -> py;
        };

        double remaining = (boundaryRadius * boundaryRadius) - (dy * dy);
        if (remaining <= 0.0) {
            return -1.0;
        }
        return Math.sqrt(remaining);
    }

    private static int cornerRangeStart(int radius, Corner corner, double extent) {
        if (extent <= 0.0) {
            return 1;
        }
        if (isLeftCorner(corner)) {
            return (int) Math.ceil((radius - 0.5) - extent);
        }
        return 0;
    }

    private static int cornerRangeEnd(int radius, Corner corner, double extent) {
        if (extent <= 0.0) {
            return 0;
        }
        if (isLeftCorner(corner)) {
            return radius - 1;
        }
        return (int) Math.floor(extent - 0.5);
    }

    private static double cornerFillCoverage(int radius, int localX, int localY, Corner corner) {
        if (radius <= 0) {
            return 0.0;
        }

        double radiusSquared = (double) radius * radius;
        int inside = 0;

        for (double sampleY : CURVE_AA_SAMPLE_OFFSETS) {
            for (double sampleX : CURVE_AA_SAMPLE_OFFSETS) {
                if (isCornerSampleInside(radius, localX, localY, corner, sampleX, sampleY, radiusSquared)) {
                    inside++;
                }
            }
        }

        return inside / (double) CURVE_AA_SAMPLE_COUNT;
    }

    private static void fillRoundedCornerAA(
        GuiGraphicsExtractor context,
        int startX,
        int startY,
        int radius,
        Corner corner,
        int color
    ) {
        if (radius <= 0) {
            return;
        }

        double outerBoundary = radius + 0.5;
        double fullBoundary = Math.max(0.0, radius - 0.5);

        for (int y = 0; y < radius; y++) {
            double outerExtent = cornerRowXExtent(radius, y, corner, outerBoundary);
            int outerStart = Math.max(0, cornerRangeStart(radius, corner, outerExtent));
            int outerEnd = Math.min(radius - 1, cornerRangeEnd(radius, corner, outerExtent));
            if (outerStart > outerEnd) {
                continue;
            }

            int fullStart = 1;
            int fullEnd = 0;
            if (fullBoundary > 0.0) {
                double fullExtent = cornerRowXExtent(radius, y, corner, fullBoundary);
                fullStart = Math.max(0, cornerRangeStart(radius, corner, fullExtent));
                fullEnd = Math.min(radius - 1, cornerRangeEnd(radius, corner, fullExtent));
            }

            int rowY = startY + y;
            if (fullStart <= fullEnd) {
                context.fill(startX + fullStart, rowY, startX + fullEnd + 1, rowY + 1, color);
                for (int x = outerStart; x < fullStart; x++) {
                    double coverage = cornerFillCoverage(radius, x, y, corner);
                    if (coverage > 0.0) {
                        drawPixel(context, startX + x, rowY, color, coverage);
                    }
                }
                for (int x = fullEnd + 1; x <= outerEnd; x++) {
                    double coverage = cornerFillCoverage(radius, x, y, corner);
                    if (coverage > 0.0) {
                        drawPixel(context, startX + x, rowY, color, coverage);
                    }
                }
            } else {
                for (int x = outerStart; x <= outerEnd; x++) {
                    double coverage = cornerFillCoverage(radius, x, y, corner);
                    if (coverage > 0.0) {
                        drawPixel(context, startX + x, rowY, color, coverage);
                    }
                }
            }
        }
    }

    private static void drawRoundedCornerStrokeAA(
        GuiGraphicsExtractor context,
        int startX,
        int startY,
        int radius,
        Corner corner,
        int color
    ) {
        if (radius <= 0) {
            return;
        }

        int innerRadius = Math.max(0, radius - 1);
        double outerBoundary = radius + 0.5;
        double innerCoreBoundary = Math.max(0.0, innerRadius - 0.5);

        for (int y = 0; y < radius; y++) {
            double outerExtent = cornerRowXExtent(radius, y, corner, outerBoundary);
            int outerStart = Math.max(0, cornerRangeStart(radius, corner, outerExtent));
            int outerEnd = Math.min(radius - 1, cornerRangeEnd(radius, corner, outerExtent));
            if (outerStart > outerEnd) {
                continue;
            }

            int skipStart = 1;
            int skipEnd = 0;
            if (innerCoreBoundary > 0.0) {
                double innerCoreExtent = cornerRowXExtent(radius, y, corner, innerCoreBoundary);
                skipStart = Math.max(0, cornerRangeStart(radius, corner, innerCoreExtent));
                skipEnd = Math.min(radius - 1, cornerRangeEnd(radius, corner, innerCoreExtent));
            }

            int rowY = startY + y;
            for (int x = outerStart; x <= outerEnd; x++) {
                if (x >= skipStart && x <= skipEnd) {
                    continue;
                }
                double outerCoverage = cornerFillCoverage(radius, x, y, corner);
                double innerCoverage = innerRadius > 0 ? cornerFillCoverage(innerRadius, x, y, corner) : 0.0;
                double strokeCoverage = clamp01(outerCoverage - innerCoverage);
                if (strokeCoverage > 0.0) {
                    drawPixel(context, startX + x, rowY, color, strokeCoverage);
                }
            }
        }
    }

    private static void fillRoundedRect(GuiGraphicsExtractor context, int x1, int y1, int x2, int y2, int color, int radius) {
        fillRoundedRectBase(context, x1, y1, x2, y2, color, radius);
    }

    private static void fillRoundedRectBase(GuiGraphicsExtractor context, int x1, int y1, int x2, int y2, int color, int radius) {
        int width = x2 - x1;
        int height = y2 - y1;
        if (width <= 0 || height <= 0) {
            return;
        }

        int clampedRadius = clampRadius(radius, width, height);
        if (clampedRadius <= 0) {
            context.fill(x1, y1, x2, y2, color);
            return;
        }

        int r = clampedRadius;
        int innerLeft = x1 + r;
        int innerRight = x2 - r;
        int innerTop = y1 + r;
        int innerBottom = y2 - r;

        if (innerTop < innerBottom) {
            context.fill(x1, innerTop, x2, innerBottom, color);
        }
        if (innerLeft < innerRight) {
            context.fill(innerLeft, y1, innerRight, innerTop, color);
            context.fill(innerLeft, innerBottom, innerRight, y2, color);
        }

        fillRoundedCornerAA(context, x1, y1, r, Corner.TOP_LEFT, color);
        fillRoundedCornerAA(context, x2 - r, y1, r, Corner.TOP_RIGHT, color);
        fillRoundedCornerAA(context, x1, y2 - r, r, Corner.BOTTOM_LEFT, color);
        fillRoundedCornerAA(context, x2 - r, y2 - r, r, Corner.BOTTOM_RIGHT, color);
    }

    private static void drawRoundedOutline(GuiGraphicsExtractor context, int x1, int y1, int x2, int y2, int color, int radius) {
        drawRoundedOutlineBase(context, x1, y1, x2, y2, color, radius);
    }

    private static void drawRoundedOutlineBase(GuiGraphicsExtractor context, int x1, int y1, int x2, int y2, int color, int radius) {
        int width = x2 - x1;
        int height = y2 - y1;
        if (width <= 0 || height <= 0) {
            return;
        }

        int clampedRadius = clampRadius(radius, width, height);
        if (clampedRadius <= 0) {
            context.fill(x1, y1, x2, y1 + 1, color);
            context.fill(x1, y2 - 1, x2, y2, color);
            context.fill(x1, y1, x1 + 1, y2, color);
            context.fill(x2 - 1, y1, x2, y2, color);
            return;
        }

        int r = clampedRadius;

        if (x2 - r > x1 + r) {
            context.fill(x1 + r, y1, x2 - r, y1 + 1, color);
            context.fill(x1 + r, y2 - 1, x2 - r, y2, color);
        }
        if (y2 - r > y1 + r) {
            context.fill(x1, y1 + r, x1 + 1, y2 - r, color);
            context.fill(x2 - 1, y1 + r, x2, y2 - r, color);
        }

        drawRoundedCornerStrokeAA(context, x1, y1, r, Corner.TOP_LEFT, color);
        drawRoundedCornerStrokeAA(context, x2 - r, y1, r, Corner.TOP_RIGHT, color);
        drawRoundedCornerStrokeAA(context, x1, y2 - r, r, Corner.BOTTOM_LEFT, color);
        drawRoundedCornerStrokeAA(context, x2 - r, y2 - r, r, Corner.BOTTOM_RIGHT, color);
    }

    private static int applyOpacity(int argb, double opacity) {
        opacity = Math.max(0.0, Math.min(1.0, opacity));
        int alpha = (argb >>> 24) & 0xFF;
        int adjustedAlpha = (int) Math.round(alpha * opacity);
        return (adjustedAlpha << 24) | (argb & 0x00FFFFFF);
    }

    private record OrderEntry(HudAst.Node node, int order, int index) {
    }

    private record Size(int width, int height) {
    }

    private final class Resolver implements HudExpressionEngine.Resolver {
        private final HudWidgetInstance widget;

        private Resolver(HudWidgetInstance widget) {
            this.widget = widget;
        }

        @Override
        public Object getData(String path) {
            return dataSource.getValue(path);
        }

        @Override
        public Object getSetting(String id) {
            if (widget.settingsValues.containsKey(id)) {
                return widget.settingsValues.get(id);
            }

            HudAst.SettingDefinition definition = widget.document.findSetting(id);
            if (definition == null) {
                return "";
            }

            return switch (definition.type) {
                case TOGGLE -> HudExpressionEngine.evalBoolean(definition.defaultRaw, this, false);
                case SLIDER -> HudExpressionEngine.evalNumber(definition.defaultRaw, this, 0.0);
                case COLOR, SELECT, TEXT, KEYBIND -> HudExpressionEngine.normalizeRawValue(definition.defaultRaw);
            };
        }
    }
}
