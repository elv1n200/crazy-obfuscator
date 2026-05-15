package dev.crazy.obf.gui;

import dev.crazy.obf.CrazyObfuscator;
import dev.crazy.obf.config.ObfConfig;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;

/**
 * Swing front-end with a modern flat theme (FlatLaf). Choose input/output
 * jars, set the root package, toggle the main passes, hit Obfuscate, watch
 * the live log. Heavy work runs off the EDT.
 */
public final class ObfuscatorGui {

    public static void launch() {
        // Modern look — fall back to system L&F if FlatLaf is unavailable.
        try {
            Class.forName("com.formdev.flatlaf.FlatLightLaf")
                 .getMethod("setup").invoke(null);
            UIManager.put("Button.arc", 12);
            UIManager.put("Component.arc", 10);
            UIManager.put("TextComponent.arc", 8);
            UIManager.put("Component.focusWidth", 1);
        } catch (Throwable t) {
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
            catch (Exception ignored) {}
        }
        SwingUtilities.invokeLater(() -> new ObfuscatorGui().build().setVisible(true));
    }

    private final JTextField input   = new JTextField(34);
    private final JTextField output  = new JTextField(34);
    private final JTextField roots   = new JTextField("", 34);
    private final JTextField mapping = new JTextField("mapping.txt", 34);

    private final JCheckBox cName   = cb("Rename classes / methods / fields", true);
    private final JCheckBox cStr    = cb("Encrypt strings", true);
    private final JCheckBox cNum    = cb("Obfuscate numbers", true);
    private final JCheckBox cFlow   = cb("Control-flow guards", true);
    private final JCheckBox cFlat   = cb("Flatten control flow", false);
    private final JCheckBox cKt     = cb("Rewrite Kotlin metadata (Kotlin / Fabric mods)", true);
    private final JCheckBox cIndy   = cb("Hide references (invokedynamic)", false);
    private final JCheckBox cJunk   = cb("Inject junk + watermark", true);
    private final JCheckBox cStrip  = cb("Strip debug metadata", true);

    private final JTextArea logArea = new JTextArea(15, 70);
    private final JButton run = new JButton("Obfuscate");
    private final JLabel status = new JLabel("Ready");

    private static JCheckBox cb(String t, boolean sel) { return new JCheckBox(t, sel); }

    private JFrame build() {
        JFrame f = new JFrame("Crazy Obfuscator 0.6.0");
        f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        // header
        JLabel title = new JLabel("Crazy Obfuscator");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 18f));
        JLabel sub = new JLabel("Java / Kotlin / Fabric .jar obfuscator");
        sub.setForeground(new Color(0x6B7280));
        JPanel header = new JPanel();
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        title.setAlignmentX(0); sub.setAlignmentX(0);
        header.add(title); header.add(Box.createVerticalStrut(2)); header.add(sub);
        header.setBorder(BorderFactory.createEmptyBorder(0, 2, 12, 0));

        // form
        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(5, 6, 5, 6);
        g.anchor = GridBagConstraints.WEST;
        g.fill = GridBagConstraints.HORIZONTAL;
        int y = 0;
        y = fileRow(form, g, y, "Input .jar", input, false);
        y = fileRow(form, g, y, "Output .jar", output, true);
        y = textRow(form, g, y, "Root package(s)", roots, "comma-separated, e.g. cop");
        y = textRow(form, g, y, "Mapping file", mapping, "for de-obfuscating crash reports");

        // options
        JPanel opts = new JPanel(new GridLayout(0, 2, 4, 2));
        for (JCheckBox c : new JCheckBox[]{cName, cStr, cNum, cFlow, cFlat, cKt, cIndy, cJunk, cStrip}) opts.add(c);
        opts.setBorder(new TitledBorder("Passes"));

        // log
        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane logScroll = new JScrollPane(logArea);
        logScroll.setBorder(new TitledBorder("Log"));

        // footer
        run.putClientProperty("JButton.buttonType", "default");
        status.setForeground(new Color(0x6B7280));
        JPanel south = new JPanel(new BorderLayout());
        south.add(status, BorderLayout.WEST);
        south.add(run, BorderLayout.EAST);
        south.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));

        JPanel top = new JPanel(new BorderLayout());
        top.add(header, BorderLayout.NORTH);
        top.add(form, BorderLayout.CENTER);
        top.add(opts, BorderLayout.SOUTH);

        JPanel root = new JPanel(new BorderLayout(0, 10));
        root.setBorder(BorderFactory.createEmptyBorder(16, 18, 16, 18));
        root.add(top, BorderLayout.NORTH);
        root.add(logScroll, BorderLayout.CENTER);
        root.add(south, BorderLayout.SOUTH);

        f.setContentPane(root);
        run.addActionListener(e -> obfuscate());
        f.pack();
        f.setMinimumSize(f.getSize());
        f.setLocationRelativeTo(null);
        return f;
    }

    private int fileRow(JPanel p, GridBagConstraints g, int y, String label, JTextField tf, boolean save) {
        g.gridx = 0; g.gridy = y; g.weightx = 0; p.add(new JLabel(label), g);
        g.gridx = 1; g.weightx = 1; p.add(tf, g);
        JButton b = new JButton("Browse…");
        b.addActionListener(e -> {
            JFileChooser fc = new JFileChooser();
            int r = save ? fc.showSaveDialog(p) : fc.showOpenDialog(p);
            if (r == JFileChooser.APPROVE_OPTION) tf.setText(fc.getSelectedFile().getAbsolutePath());
        });
        g.gridx = 2; g.weightx = 0; p.add(b, g);
        return y + 1;
    }

    private int textRow(JPanel p, GridBagConstraints g, int y, String label, JTextField tf, String hint) {
        g.gridx = 0; g.gridy = y; g.weightx = 0; p.add(new JLabel(label), g);
        tf.putClientProperty("JTextField.placeholderText", hint);
        g.gridx = 1; g.gridwidth = 2; g.weightx = 1; p.add(tf, g);
        g.gridwidth = 1;
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
        status.setText("Obfuscating…");
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
                    status.setText("Failed");
                    append("\n[ERROR] " + err + "\n");
                    JOptionPane.showMessageDialog(null, String.valueOf(err),
                        "Obfuscation failed", JOptionPane.ERROR_MESSAGE);
                } else {
                    status.setText("Done");
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
