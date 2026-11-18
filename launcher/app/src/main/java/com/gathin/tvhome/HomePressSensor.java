package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.graphics.PixelFormat;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

/**
 * Tells HomeKeyService about every Home press on firmware that never lets an accessibility service see the Home key
 * (Fire OS: its key policy takes Home before the filter). On each Home press the system asks every window with a
 * surface to close its system dialogs, reason "homekey"; this 1x1 transparent accessibility overlay, which takes no
 * touches and no focus, is such a window. Its view hears it through View's hidden onCloseSystemDialogs callback.
 */
final class HomePressSensor extends View {

    interface Listener {
        void onHomePressed();
    }

    /** PhoneWindowManager's SYSTEM_DIALOG_REASON_HOME_KEY. */
    static final String REASON_HOME_KEY = "homekey";

    private final Listener listener;

    private HomePressSensor(Context context, Listener listener) {
        super(context);
        this.listener = listener;
    }

    /** Overrides View's hidden callback (ViewRootImpl.dispatchCloseSystemDialogs), so no @Override. */
    @SuppressWarnings("unused")
    public void onCloseSystemDialogs(String reason) {
        if (REASON_HOME_KEY.equals(reason)) listener.onHomePressed();
    }

    /** The sensor window, or null where the system refuses an accessibility overlay. */
    static HomePressSensor attach(AccessibilityService service, Listener listener) {
        WindowManager windows = (WindowManager) service.getSystemService(Context.WINDOW_SERVICE);
        if (windows == null) return null;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(1, 1,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.setTitle("HomePressSensor");
        HomePressSensor sensor = new HomePressSensor(service, listener);
        try {
            windows.addView(sensor, params);
            return sensor;
        } catch (RuntimeException refused) {
            return null;
        }
    }

    void detach() {
        WindowManager windows = (WindowManager) getContext().getSystemService(Context.WINDOW_SERVICE);
        try {
            if (windows != null) windows.removeView(this);
        } catch (RuntimeException alreadyGone) {
            // the service is going away and took the window with it
        }
    }
}
