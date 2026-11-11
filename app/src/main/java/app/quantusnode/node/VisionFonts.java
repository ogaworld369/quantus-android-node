package app.quantusnode.node;

import android.content.Context;
import android.graphics.Typeface;

/**
 * Typefaces of the vision UI (spec: depin-vision-ui-spec.md
 * section 2). Three roles only: display (Space Grotesk), body
 * (the device font — the spec leaves non-Latin scripts to the
 * platform face), mono (IBM Plex Mono). The two Latin faces are
 * bundled under assets/fonts (both SIL Open Font License 1.1;
 * the licence texts ship beside them). Space Grotesk ships as
 * its variable font, so the 500 / 700 instances are cut with a
 * 'wght' variation setting; IBM Plex Mono ships as static
 * Regular / Medium files.
 *
 * Loading is best-effort by design: any failure (asset missing,
 * variation unsupported) falls back to the platform faces the
 * rest of the app already uses, so a font problem can degrade
 * the look, never break the screen.
 */
public final class VisionFonts {

    private static Typeface displayMedium;
    private static Typeface displayBold;
    private static Typeface monoRegular;
    private static Typeface monoMedium;
    private static boolean tried;

    private VisionFonts() {
    }

    private static void load(Context ctx) {
        if (tried) {
            return;
        }
        tried = true;
        try {
            displayMedium = new Typeface.Builder(
                    ctx.getAssets(), "fonts/SpaceGrotesk.ttf")
                    .setFontVariationSettings("'wght' 500")
                    .build();
        } catch (Throwable ignored) {
            displayMedium = null;
        }
        try {
            displayBold = new Typeface.Builder(
                    ctx.getAssets(), "fonts/SpaceGrotesk.ttf")
                    .setFontVariationSettings("'wght' 700")
                    .build();
        } catch (Throwable ignored) {
            displayBold = null;
        }
        try {
            monoRegular = Typeface.createFromAsset(
                    ctx.getAssets(), "fonts/IBMPlexMono-Regular.ttf");
        } catch (Throwable ignored) {
            monoRegular = null;
        }
        try {
            monoMedium = Typeface.createFromAsset(
                    ctx.getAssets(), "fonts/IBMPlexMono-Medium.ttf");
        } catch (Throwable ignored) {
            monoMedium = null;
        }
    }

    /** Display face, weight 500 (stat values, headings). */
    public static Typeface displayMedium(Context ctx) {
        load(ctx);
        return displayMedium != null ? displayMedium
                : Typeface.create("sans-serif-medium", Typeface.NORMAL);
    }

    /** Display face, weight 700 (the big numbers, the price). */
    public static Typeface displayBold(Context ctx) {
        load(ctx);
        return displayBold != null ? displayBold
                : Typeface.create("sans-serif", Typeface.BOLD);
    }

    /** Mono face, weight 400 (member number, times). */
    public static Typeface mono(Context ctx) {
        load(ctx);
        return monoRegular != null ? monoRegular : Typeface.MONOSPACE;
    }

    /** Mono face, weight 500. */
    public static Typeface monoMedium(Context ctx) {
        load(ctx);
        return monoMedium != null ? monoMedium : Typeface.MONOSPACE;
    }
}
