package app.quantusnode.smoke;

import android.app.Activity;
import android.content.pm.ApplicationInfo;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Smoke test: run the bundled quantus-node binary with --version as a child
 * process and show exactly what happened (stdout / stderr / exit code, or the
 * startup error). Nothing is hidden: if the binary cannot be executed on this
 * device, the failure itself is the result we want to see.
 */
public class MainActivity extends Activity {

    private static final String NODE_LIB = "libquantus_node.so";
    private static final long TIMEOUT_SEC = 30;

    private TextView outputView;
    private Button runButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Quantus Node — smoke test");
        title.setTextSize(18);
        root.addView(title);

        TextView info = new TextView(this);
        ApplicationInfo ai = getApplicationInfo();
        File bin = new File(ai.nativeLibraryDir, NODE_LIB);
        info.setText("nativeLibraryDir: " + ai.nativeLibraryDir
                + "\nbinary: " + bin.getAbsolutePath()
                + "\nexists: " + bin.exists()
                + (bin.exists() ? "  size: " + bin.length() + " bytes" : "")
                + "\nTap the button to run it with --version.");
        root.addView(info);

        runButton = new Button(this);
        runButton.setText("Run --version");
        runButton.setOnClickListener(v -> runSmokeTest());
        root.addView(runButton);

        outputView = new TextView(this);
        outputView.setTypeface(Typeface.MONOSPACE);
        outputView.setTextSize(12);
        outputView.setText("(no run yet)");

        ScrollView scroll = new ScrollView(this);
        scroll.addView(outputView);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
    }

    private void runSmokeTest() {
        runButton.setEnabled(false);
        outputView.setText("running...");
        new Thread(() -> {
            String result = doRun();
            runOnUiThread(() -> {
                outputView.setText(result);
                runButton.setEnabled(true);
            });
        }, "smoke-runner").start();
    }

    private String doRun() {
        StringBuilder sb = new StringBuilder();
        ApplicationInfo ai = getApplicationInfo();
        String nativeDir = ai.nativeLibraryDir;
        File bin = new File(nativeDir, NODE_LIB);

        sb.append("binary exists: ").append(bin.exists()).append('\n');
        if (bin.exists()) {
            sb.append("binary size: ").append(bin.length()).append(" bytes\n");
            sb.append("readable: ").append(bin.canRead())
              .append("  executable-bit: ").append(bin.canExecute()).append('\n');
        }
        sb.append('\n');

        boolean directOk = attempt(sb, "attempt 1: direct exec",
                new String[]{bin.getAbsolutePath(), "--version"}, nativeDir);

        if (!directOk) {
            // Fallback: run the PIE through the system linker explicitly.
            // Same binary, different door in; the result is recorded too.
            attempt(sb, "attempt 2: via /system/bin/linker64",
                    new String[]{"/system/bin/linker64", bin.getAbsolutePath(), "--version"},
                    nativeDir);
        }
        return sb.toString();
    }

    /** @return true if the process started and exited with code 0. */
    private boolean attempt(StringBuilder sb, String label, String[] cmd, String nativeDir) {
        sb.append("== ").append(label).append(" ==\n");
        sb.append("cmd: ").append(String.join(" ", cmd)).append('\n');
        Process proc = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            // The bundled libc++_shared.so sits next to the binary; make sure
            // the dynamic linker can find it there.
            pb.environment().put("LD_LIBRARY_PATH", nativeDir);
            proc = pb.start();

            StreamCollector out = new StreamCollector(proc.getInputStream());
            StreamCollector err = new StreamCollector(proc.getErrorStream());
            Thread outThread = new Thread(out, "stdout-reader");
            Thread errThread = new Thread(err, "stderr-reader");
            outThread.start();
            errThread.start();

            boolean finished = proc.waitFor(TIMEOUT_SEC, TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                sb.append("TIMEOUT after ").append(TIMEOUT_SEC).append("s — killed\n");
                return false;
            }
            outThread.join(2000);
            errThread.join(2000);

            int code = proc.exitValue();
            sb.append("exit code: ").append(code).append('\n');
            sb.append("-- stdout --\n").append(out.text()).append('\n');
            sb.append("-- stderr --\n").append(err.text()).append('\n');
            return code == 0;
        } catch (IOException | InterruptedException e) {
            sb.append("FAILED TO RUN: ").append(e.getClass().getSimpleName())
              .append(": ").append(e.getMessage()).append('\n');
            return false;
        } finally {
            if (proc != null) {
                proc.destroy();
            }
            sb.append('\n');
        }
    }

    private static class StreamCollector implements Runnable {
        private final InputStream in;
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream();

        StreamCollector(InputStream in) {
            this.in = in;
        }

        @Override
        public void run() {
            byte[] chunk = new byte[8192];
            try {
                int n;
                while ((n = in.read(chunk)) != -1) {
                    buf.write(chunk, 0, n);
                }
            } catch (IOException ignored) {
                // Stream closed because the process died; whatever was
                // collected so far is the honest output.
            }
        }

        String text() {
            String s = buf.toString(StandardCharsets.UTF_8);
            return s.isEmpty() ? "(empty)" : s.trim();
        }
    }
}
