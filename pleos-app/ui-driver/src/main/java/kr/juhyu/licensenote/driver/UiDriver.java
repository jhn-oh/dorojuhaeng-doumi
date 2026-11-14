package kr.juhyu.licensenote.driver;

import android.app.Instrumentation;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.os.Bundle;
import android.util.Base64;
import android.util.Xml;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.graphics.Rect;
import org.xmlpull.v1.XmlSerializer;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** A separate test APK, so starting a UI probe never restarts the recording app. */
public class UiDriver extends Instrumentation {
    private Bundle args;
    @Override public void onCreate(Bundle arguments) { args = arguments; start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            AccessibilityServiceInfo info = getUiAutomation().getServiceInfo();
            info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            getUiAutomation().setServiceInfo(info);
            String targetPackage = args.getString("package", "kr.juhyu.licensenote");
            AccessibilityNodeInfo root = null;
            for (int attempt = 0; attempt < 12 && root == null; attempt++) {
                List<AccessibilityWindowInfo> windows = new ArrayList<>();
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    android.util.SparseArray<List<AccessibilityWindowInfo>> displays = getUiAutomation().getWindowsOnAllDisplays();
                    for (int i = 0; i < displays.size(); i++) windows.addAll(displays.valueAt(i));
                } else windows.addAll(getUiAutomation().getWindows());
                List<String> inventory = new ArrayList<>();
                for (AccessibilityWindowInfo window : windows) {
                    AccessibilityNodeInfo candidate = window.getRoot();
                    Rect frame = new Rect(); window.getBoundsInScreen(frame);
                    inventory.add("display=" + (android.os.Build.VERSION.SDK_INT >= 30 ? window.getDisplayId() : 0) + " focused=" + window.isFocused() + " active=" + window.isActive() + " bounds=" + frame.toShortString() + " package=" + (candidate == null ? "null" : candidate.getPackageName()));
                    if (candidate != null && targetPackage.contentEquals(candidate.getPackageName())) { root = candidate; break; }
                }
                result.putString("windowInventory", String.join(" | ", inventory));
                if (root == null) Thread.sleep(100);
            }
            if (root == null) throw new IllegalStateException("App accessibility window not found");
            StringWriter writer = new StringWriter();
            XmlSerializer xml = Xml.newSerializer();
            xml.setOutput(writer); xml.startTag(null, "hierarchy"); writeNode(xml, root); xml.endTag(null, "hierarchy"); xml.flush();
            result.putString("xml64", Base64.encodeToString(writer.toString().getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP));
            String text = args.getString("click");
            String scroll = args.getString("scroll");
            if (text != null && scroll != null) throw new IllegalArgumentException("Choose click or scroll in one request");
            if (text != null) {
                AccessibilityNodeInfo node = find(root, text);
                if (node == null) throw new IllegalStateException("Text not found: " + text);
                while (!node.isClickable() && node.getParent() != null) node = node.getParent();
                if (!node.isEnabled() || !node.isClickable() || !node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) throw new IllegalStateException("Click rejected: " + text);
                result.putString("clicked", text);
            }
            if (scroll != null) {
                if (!"forward".equals(scroll) && !"backward".equals(scroll)) throw new IllegalArgumentException("Scroll must be forward or backward");
                int index = Integer.parseInt(args.getString("scrollIndex", "0"));
                if (index < 0) throw new IllegalArgumentException("Negative scroll index");
                List<AccessibilityNodeInfo> nodes = new ArrayList<>();
                collectScrollable(root, nodes);
                result.putInt("scrollableCount", nodes.size());
                result.putInt("scrollIndex", nodes.isEmpty() ? -1 : index);
                if (nodes.isEmpty()) {
                    result.putString("scrollStatus", "NO_SCROLLABLE");
                    result.putBoolean("scrollAccepted", false);
                } else {
                    if (index >= nodes.size()) throw new IllegalArgumentException("Scroll index out of range: " + index);
                    int action = "forward".equals(scroll) ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;
                    boolean accepted = nodes.get(index).performAction(action);
                    result.putBoolean("scrollAccepted", accepted);
                    result.putString("scrollStatus", accepted ? "SCROLL_REQUESTED" : "END_REACHED");
                }
            }
            finish(0, result);
        } catch (Exception error) { result.putString("error", error.toString()); finish(1, result); }
    }
    private AccessibilityNodeInfo find(AccessibilityNodeInfo node, String text) {
        if (node.isVisibleToUser() && node.getText() != null && text.contentEquals(node.getText())) return node;
        for (int i = 0; i < node.getChildCount(); i++) { AccessibilityNodeInfo child = node.getChild(i); if (child != null) { AccessibilityNodeInfo match = find(child, text); if (match != null) return match; } }
        return null;
    }
    private void collectScrollable(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> result) {
        if (!node.isVisibleToUser()) return;
        if (node.isScrollable()) result.add(node);
        for (int i = 0; i < node.getChildCount(); i++) { AccessibilityNodeInfo child = node.getChild(i); if (child != null) collectScrollable(child, result); }
    }
    private void writeNode(XmlSerializer xml, AccessibilityNodeInfo node) throws Exception {
        if (!node.isVisibleToUser()) return;
        xml.startTag(null, "node");
        xml.attribute(null, "text", node.getText() == null ? "" : node.getText().toString());
        xml.attribute(null, "description", node.getContentDescription() == null ? "" : node.getContentDescription().toString());
        xml.attribute(null, "enabled", Boolean.toString(node.isEnabled()));
        xml.attribute(null, "clickable", Boolean.toString(node.isClickable()));
        xml.attribute(null, "scrollable", Boolean.toString(node.isScrollable()));
        Rect bounds = new Rect(); node.getBoundsInScreen(bounds); xml.attribute(null, "bounds", bounds.toShortString());
        for (int i = 0; i < node.getChildCount(); i++) { AccessibilityNodeInfo child = node.getChild(i); if (child != null) writeNode(xml, child); }
        xml.endTag(null, "node");
    }
}
