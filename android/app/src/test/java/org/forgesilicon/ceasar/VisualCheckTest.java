package org.forgesilicon.ceasar;

import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowAlertDialog;
import java.io.File;
import java.io.FileOutputStream;
import java.time.Duration;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "w412dp-h892dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class VisualCheckTest {
    private View find(View v, String text) {
        if (v.isShown() && v.isClickable() && v instanceof TextView && text.equals(((TextView) v).getText().toString())) return v;
        if (v.isShown() && v.isClickable() && text.contentEquals(v.getContentDescription() == null ? "" : v.getContentDescription())) return v;
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) { View child = find(((ViewGroup) v).getChildAt(i), text); if (child != null) return child; }
        return null;
    }
    private boolean hasText(View v, String text) {
        if (v instanceof TextView && ((TextView) v).getText().toString().contains(text)) return true;
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) if (hasText(((ViewGroup) v).getChildAt(i), text)) return true;
        return false;
    }
    private void click(MainActivity a, String text) {
        View v = find(a.getWindow().getDecorView(), text);
        assertNotNull("Missing control: " + text, v);
        v.performClick(); settle(a);
    }
    private void settle(MainActivity a) {
        for (int i = 0; i < 100; i++) { boolean ready = a.analysisReady(); shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(100)); if (ready && a.analysisReady()) break; }
        shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
    }
    /** Renders the whole scrolling page, not just the first screen, so every card is checked. */
    private void capture(MainActivity activity, String name) throws Exception {
        View content = activity.findViewById(android.R.id.content);
        int width = 824, height = 1784;
        content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)); content.layout(0, 0, width, height);
        ScrollView scroll = findScroll(content);
        int full = scroll.getChildAt(0).getHeight(), nav = height - scroll.getHeight();
        int total = Math.max(height, full + nav);
        content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(total, View.MeasureSpec.EXACTLY)); content.layout(0, 0, width, total);
        Bitmap bitmap = Bitmap.createBitmap(width, total, Bitmap.Config.ARGB_8888); content.draw(new Canvas(bitmap));
        File folder = new File("build/visual-check"); assertTrue(folder.isDirectory() || folder.mkdirs());
        try (FileOutputStream stream = new FileOutputStream(new File(folder, name + ".png"))) { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)); }
        bitmap.recycle();
        checkActions(content, activity.getResources().getDisplayMetrics().density);
        content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)); content.layout(0, 0, width, height);
    }
    private ScrollView findScroll(View v) {
        if (v instanceof ScrollView) return (ScrollView) v;
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) { ScrollView s = findScroll(((ViewGroup) v).getChildAt(i)); if (s != null) return s; }
        return null;
    }
    private void checkActions(View v, float density) {
        if (v.isShown() && v.isClickable() && !(v instanceof ScrollView) && v.getClass() != android.widget.LinearLayout.class)
            assertTrue("Small touch target: " + v.getClass().getSimpleName() + " " + (v instanceof TextView ? ((TextView) v).getText() : v.getContentDescription()) + " height=" + v.getHeight(), v.getHeight() >= 44 * density);
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) checkActions(((ViewGroup) v).getChildAt(i), density);
    }

    @Test public void emptyStateOffersDemo() throws Exception {
        try (var controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity a = controller.get(); settle(a);
            assertTrue(hasText(a.getWindow().getDecorView(), "No readings yet"));
            capture(a, "empty-dark");
        }
    }

    @Test public void renderDemoScreensInBothThemes() throws Exception {
        try (var controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity a = controller.get(); settle(a);
            click(a, "Device"); click(a, "Try demo");
            click(a, "Today");
            View root = a.getWindow().getDecorView();
            assertTrue(hasText(root, "DEMO PREVIEW"));
            assertTrue(hasText(root, "Possible illness signal"));
            capture(a, "today-dark");
            click(a, "Trends"); capture(a, "trends-day-dark");
            click(a, "Week"); capture(a, "trends-week-dark");
            click(a, "Month"); click(a, "Show heater power"); capture(a, "trends-month-dark");
            click(a, "Sleep"); capture(a, "sleep-dark");
            click(a, "Previous night"); click(a, "Previous night"); capture(a, "sleep-earlier-dark");
            click(a, "Insights");
            assertTrue(hasText(a.getWindow().getDecorView(), "phase"));
            capture(a, "insights-dark");
            click(a, "Device"); shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofSeconds(20)); click(a, "Dev mode"); capture(a, "device-dark");
            click(a, "Settings"); AlertDialog settings = ShadowAlertDialog.getLatestAlertDialog(); settings.getListView().performItemClick(null, 0, 0);
            AlertDialog appearance = ShadowAlertDialog.getLatestAlertDialog(); appearance.getListView().performItemClick(null, 1, 1); settle(a);
            click(a, "Today"); capture(a, "today-light");
            click(a, "Insights"); capture(a, "insights-light");
            click(a, "Sleep"); capture(a, "sleep-light");
            click(a, "Settings"); settings = ShadowAlertDialog.getLatestAlertDialog(); settings.getListView().performItemClick(null, 1, 1);
            AlertDialog units = ShadowAlertDialog.getLatestAlertDialog(); units.getListView().performItemClick(null, 1, 1); settle(a);
            click(a, "Trends"); capture(a, "trends-fahrenheit-light");
            click(a, "Device"); click(a, "Stop demo"); click(a, "Today");
            assertTrue(hasText(a.getWindow().getDecorView(), "No readings yet"));
        }
    }
}
