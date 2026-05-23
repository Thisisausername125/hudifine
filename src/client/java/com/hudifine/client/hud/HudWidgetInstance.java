package com.hudifine.client.hud;

import com.hudifine.client.script.HudAst;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class HudWidgetInstance {
    public final UUID id = UUID.randomUUID();
    public final String sourceScript;
    public final HudAst.HudScriptDocument document;
    public final Map<String, Object> settingsValues = new HashMap<>();
    public final Map<String, Boolean> eventState = new HashMap<>();

    public boolean hasManualPosition;
    public double manualX;
    public double manualY;
    public double manualScaleX = 1.0;
    public double manualScaleY = 1.0;
    public boolean hasManualAnchor;
    public String manualAnchor = "top-left";
    public double manualOffsetX;
    public double manualOffsetY;
    public boolean userVisible = true;
    public boolean lastResolvedVisible = true;
    public String extensionKey = "";

    public int lastX;
    public int lastY;
    public int lastWidth;
    public int lastHeight;

    public String budgetWarning = "";

    public HudWidgetInstance(String sourceScript, HudAst.HudScriptDocument document) {
        this.sourceScript = sourceScript;
        this.document = document;
    }

    public boolean contains(double x, double y) {
        if (lastWidth <= 0 || lastHeight <= 0) {
            return false;
        }

        int hitPadding = 6;
        return x >= lastX - hitPadding
            && y >= lastY - hitPadding
            && x <= lastX + lastWidth + hitPadding
            && y <= lastY + lastHeight + hitPadding;
    }
}
