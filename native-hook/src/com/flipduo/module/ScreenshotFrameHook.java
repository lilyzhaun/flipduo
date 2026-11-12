package com.flipduo.module;

import android.app.Application;
import android.content.Context;
import android.graphics.Bitmap;
import android.hardware.display.DisplayManager;
import android.util.DisplayMetrics;
import android.view.Display;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/** Frames a completed full cover capture at Xiaomi's export boundary, after native capture permissions are enforced. */
final class ScreenshotFrameHook {
    static final Bitmap.CompressFormat FRAME_FORMAT = Bitmap.CompressFormat.PNG;
    private static Context context;
    private static final Map<Bitmap, Integer> COVER_CAPTURES = Collections.synchronizedMap(new WeakHashMap<>());

    static void install(NativeModule module, ClassLoader loader) throws ReflectiveOperationException {
        ScreenshotHostApi api = ScreenshotHostApi.resolve(loader);
        if (api.softwareCaller != null && !module.deoptimize(api.softwareCaller))
            throw new ReflectiveOperationException("Cannot deoptimize screenshot software-conversion caller");
        if (api.modern()) {
            module.hook(Application.class.getDeclaredMethod("attach", Context.class)).intercept(chain -> {
                Object result = chain.proceed();
                context = (Application) chain.getThisObject();
                return result;
            });
            module.hook(api.setBitmap).intercept(chain -> {
                Object result = chain.proceed();
                Bitmap image = (Bitmap) chain.getArg(0);
                if (context != null && isFullCover(image) && (Integer) api.getType.invoke(chain.getThisObject()) == 1) {
                    int id = (Integer) api.getDisplayId.invoke(chain.getThisObject());
                    remember(image, context.getSystemService(DisplayManager.class).getDisplay(id));
                }
                return result;
            });
            if (api.softwareBitmap != null) module.hook(api.softwareBitmap).intercept(chain -> {
                Bitmap original = (Bitmap) chain.getArg(0);
                Integer rotation = COVER_CAPTURES.get(original);
                boolean tracked = rotation != null && isFullCover(original);
                int width = tracked ? original.getWidth() : 0, height = tracked ? original.getHeight() : 0;
                Object result = chain.proceed(); // OEM owns conversion, optional recycling, and the returned bitmap.
                if (tracked && result instanceof Bitmap converted && isFullCover(converted)
                        && converted.getWidth() == width && converted.getHeight() == height)
                    COVER_CAPTURES.put(converted, rotation);
                return result;
            });
        } else {
            for (var caller : api.legacyCallers)
                if (!module.deoptimize(caller)) NativeModule.info("Legacy screenshot caller deoptimization unavailable: " + caller.getName());
            module.hook(api.legacyComplete).intercept(chain -> {
                // The worker has already finished OEM capture and its permission checks.
                // Register before this callback starts the original export/thumbnail path.
                Object owner = chain.getArg(0);
                Bitmap image = (Bitmap) api.legacyBitmap.get(owner);
                if (isFullCover(image)) remember(image, (Display) api.legacyDisplay.invoke(null, (Context) api.legacyContext.get(owner)));
                return chain.proceed();
            });
        }
        module.hook(api.save).intercept(chain -> {
            Bitmap image = (Bitmap) chain.getArg(1);
            Integer rotation = COVER_CAPTURES.get(image);
            DockConfig config = NativeModule.config();
            if (!config.enabled || !config.framedScreenshots || rotation == null || !isFullCover(image)) return chain.proceed();
            Bitmap framed;
            Context frameContext = api.modern() ? context : (Context) chain.getArg(0);
            try { framed = new CoverFrame(CoverGeometry.resolve(frameContext), config).compose(image, rotation); }
            catch (RuntimeException error) {
                NativeModule.warning("Cover frame composition failed; exporting native capture", error);
                return chain.proceed();
            }
            // JPEG flattens CoverFrame's transparent outer corners to black. The OEM saver derives the
            // filename extension and encoder from this same format argument, so only framed exports use PNG.
            Object[] args = chain.getArgs().toArray(); args[1] = framed; args[3] = FRAME_FORMAT;
            NativeModule.info("Exporting framed cover screenshot: " + framed.getWidth() + "x" + framed.getHeight()
                    + " rotation=" + rotation + " format=PNG alpha=" + framed.hasAlpha());
            return chain.proceed(args);
        });
        NativeModule.info("Cover screenshot export installed: device-selected artwork, " + (api.modern() ? "metadata" : "native-completed-callback")
                + " save=" + api.save.getDeclaringClass().getName() + " software-copy=" + (api.softwareBitmap != null)
                + "; unprofiled devices retain original pixels");
    }

    private static void remember(Bitmap image, Display display) {
        if (display == null) return;
        DisplayMetrics metrics = new DisplayMetrics(); display.getRealMetrics(metrics);
        int rotation = display.getRotation();
        if (acceptCapture(image, metrics.widthPixels, metrics.heightPixels, rotation)) {
            COVER_CAPTURES.put(image, rotation);
            NativeModule.info("Full cover screenshot captured: display=" + display.getDisplayId() + " rotation=" + rotation);
        }
    }

    static boolean acceptCapture(Bitmap image, int width, int height, int rotation) {
        return rotation >= 0 && rotation <= 3 && isFullCover(image)
                && width == CoverRotation.width(rotation) && height == CoverRotation.height(rotation)
                && image.getWidth() == width && image.getHeight() == height;
    }
    private static boolean isFullCover(Bitmap image) {
        return image != null && !image.isRecycled() && CutoutAppConfig.isCoverDisplay(image.getWidth(), image.getHeight());
    }
}
