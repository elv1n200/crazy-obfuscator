package dev.crazy.obf.gui;

import dev.crazy.obf.CrazyObfuscator;
import dev.crazy.obf.config.ObfConfig;

import javax.swing.*;
import java.awt.*;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;

/**
 * Minimal Swing front-end so the packaged app is a real clickable window:
 * choose input/output jars, set the root package, toggle the main passes,
 * hit Obfuscate, watch the live log. Heavy work runs off the EDT.
 */
public final class ObfuscatorGui {

    public static void launch() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {}
        SwingUtilities.invokeLater(() -> new ObfuscatorGui().build().setVisible(true));
    }

    private final JTextField input  = new JTextField(34);
    private final JTextField output = new JTextField(34);
    private final JTextField roots  = new JTextField("com.example.mymod", 34);
    private final JTextField mapping = new JTextField("mapping.txt", 34);

    private final JCheckBox cName   = cb("Rename classes/methods/fields", true);
    private final JCheckBox cStr    = cb("Encrypt strings", true);
    private final JCheckBox cNum    = cb("Obfuscate numbers", true);
    private final JCheckBox cFlow   = cb("Control-flow guards", true);
    private final JCheckBox cFlat   = cb("Flatten control flow", false);
    private final JCheckBox cKt     = cb("Rewrite Kotlin metadata (Kotlin/Fabric mods)", true);
    private final JCheckBox cIndy   = cb("Hide references (invokedynamic)", false);
    private final JCheckBox cJunk   = cb("Inject junk + watermark", true);
    private final JCheckBox cStrip  = cb("Strip debug metadata", true);

    private final JTextArea logArea = new JTextArea(16, 70);
    private final JButton run = new JButton("Obfuscate");

    private static JCheckBox cb(String t, boolean sel) { JCheckBox c = new JCheckBox(t, sel); return c; }

    private JFrame build() {
        JFrame f = new JFrame("Crazy Obfuscator 0.6.0");
        f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(4, 6, 4, 6);
        g.anchor = GridBagConstraints.WEST;
        int y = 0;
        y = fileRow(form, g, y, "Input .jar", input, false);
        y = fileRow(form, g, y, "Output .jar", output, true);
        y = textRow(form, g, y, "Root package(s), comma-sep", roots);
        y = textRow(form, g, y, "Mapping file", mapping);

        JPanel opts = new JPanel(new GridLayout(0, 2));
        for (JCheckBox c : new JCheckBox[]{cName, cStr, cNum, cFlow, cFlat, cKt, cIndy, cJunk, cStrip}) opts.add(c);
        g.gridx = 0; g.gridy = y++; g.gridwidth = 3;
        form.add(opts, g);

        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

        JPanel south = new JPanel(new BorderLayout());
        south.add(run, BorderLayout.EAST);

        f.setLayout(new BorderLayout(8, 8));
        ((JComponent) f.getContentPane()).setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        f.add(form, BorderLayout.NORTH);
        f.add(new JScrollPane(logArea), BorderLayout.CENTER);
        f.add(south, BorderLayout.SOUTH);

        run.addActionListener(e -> obfuscate());
        f.pack();
        f.setLocationRelativeTo(null);
        return f;
    }

    private int fileRow(JPanel p, GridBagConstraints g, int y, String label, JTextField tf, boolean save) {
        g.gridx = 0; g.gridy = y; g.gridwidth = 1; p.add(new JLabel(label), g);
        g.gridx = 1; p.add(tf, g);
        JButton b = new JButton("Browse…");
        b.addActionListener(e -> {
            JFileChooser fc = new JFileChooser();
            int r = save ? fc.showSaveDialog(p) : fc.showOpenDialog(p);
            if (r == JFileChooser.APPROVE_OPTION) tf.setText(fc.getSelectedFile().getAbsolutePath());
        });
        g.gridx = 2; p.add(b, g);
        return y + 1;
    }

    private int textRow(JPanel p, GridBagConstraints g, int y, String label, JTextField tf) {
        g.gridx = 0; g.gridy = y; g.gridwidth = 1; p.add(new JLabel(label), g);
        g.gridx = 1; g.gridwidth = 2; p.add(tf, g);
        return y + 1;
    }

    private void obfuscate() {
        String in = input.getText().trim(), out = output.getText().trim();
        if (in.isEmpty() || out.isEmpty()) {
            JOptionPane.showMessageDialog(null, "Pick an input and output .jar first.",
                "Missing paths", JOptionPane.WARNING_MESSAGE);
            return;
        }
        run.setEnabled(false);
        logArea.setText("");
        ObfConfig cfg = new ObfConfig();
        cfg.rootPackages = new ArrayList<>();
        for (String s : roots.getText().split(",")) { s = s.trim(); if (!s.isEmpty()) cfg.rootPackages.add(s); }
        boolean rn = cName.isSelected();
        cfg.renameClasses = rn; cfg.renameMethods = rn; cfg.renameFields = rn;
        cfg.encryptStrings = cStr.isSelected();
        cfg.obfuscateNumbers = cNum.isSelected();
        cfg.obfuscateFlow = cFlow.isSelected();
        cfg.flattenControlFlow = cFlat.isSelected();
        cfg.rewriteKotlinMetadata = cKt.isSelected();
        cfg.hideReferences = cIndy.isSelected();
        cfg.injectJunk = cJunk.isSelected();
        cfg.stripMetadata = cStrip.isSelected();
        if (cJunk.isSelected()) cfg.watermark = "gui-build";
        String mp = mapping.getText().trim();
        if (!mp.isEmpty()) cfg.mappingOutput = mp;

        PrintStream ps = new PrintStream(new OutputStream() {
            @Override public void write(int b) { append(String.valueOf((char) b)); }
            @Override public void write(byte[] b, int o, int l) { append(new String(b, o, l)); }
        }, true);

        new SwingWorker<Void, Void>() {
            Throwable err;
            @Override protected Void doInBackground() {
                try { CrazyObfuscator.run(Path.of(in), Path.of(out), cfg, ps); }
                catch (Throwable t) { err = t; }
                return null;
            }
            @Override protected void done() {
                run.setEnabled(true);
                if (err != null) {
                    append("\n[ERROR] " + err + "\n");
                    JOptionPane.showMessageDialog(null, String.valueOf(err),
                        "Obfuscation failed", JOptionPane.ERROR_MESSAGE);
                } else {
                    JOptionPane.showMessageDialog(null, "Done:\n" + out,
                        "Obfuscation complete", JOptionPane.INFORMATION_MESSAGE);
                }
            }
        }.execute();
    }

    private void append(String s) {
        SwingUtilities.invokeLater(() -> {
            logArea.append(s);
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }
}
