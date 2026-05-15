package dev.crazy.obf;

import dev.crazy.obf.analysis.ReflectionScanner;
import dev.crazy.obf.config.ExclusionRules;
import dev.crazy.obf.io.JarContents;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

import java.io.PrintStream;
import java.io.OutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * B5: a constant class name stashed in a local before Class.forName must be
 * detected and excluded from renaming (the literal-immediately-before scan
 * would miss this).
 */
public class ReflectionDataflowTest {

    @Test
    void constantThroughLocalReachesForName() throws Exception {
        // class app/Sec; class app/Target;
        // app/Sec.boot(): String c = "app.Target"; Class.forName(c);
        ClassWriter tw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        tw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "app/Target", null, "java/lang/Object", null);
        var tc = tw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        tc.visitVarInsn(Opcodes.ALOAD, 0);
        tc.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        tc.visitInsn(Opcodes.RETURN);
        tc.visitMaxs(0, 0);
        tw.visitEnd();

        ClassWriter sw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        sw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "app/Sec", null, "java/lang/Object", null);
        var bc = sw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        bc.visitVarInsn(Opcodes.ALOAD, 0);
        bc.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        bc.visitInsn(Opcodes.RETURN);
        bc.visitMaxs(0, 0);
        var boot = sw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "boot", "()V", null, null);
        boot.visitLdcInsn("app.Target");
        boot.visitVarInsn(Opcodes.ASTORE, 0);            // String c = "app.Target"
        boot.visitVarInsn(Opcodes.ALOAD, 0);             // load it back
        boot.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Class", "forName",
            "(Ljava/lang/String;)Ljava/lang/Class;", false);
        boot.visitInsn(Opcodes.POP);
        boot.visitInsn(Opcodes.RETURN);
        boot.visitMaxs(0, 0);
        sw.visitEnd();

        JarContents jc = new JarContents();
        ClassNode tn = new ClassNode();
        new org.objectweb.asm.ClassReader(tw.toByteArray()).accept(tn, 0);
        ClassNode sn = new ClassNode();
        new org.objectweb.asm.ClassReader(sw.toByteArray()).accept(sn, 0);
        jc.classes().put(tn.name, tn);
        jc.classes().put(sn.name, sn);

        ExclusionRules ex = new ExclusionRules();
        new ReflectionScanner(jc, ex,
            new PrintStream(OutputStream.nullOutputStream())).scan();

        assertTrue(ex.isClassExcluded("app/Target"),
            "Class.forName target reached through a local must be excluded");
        assertTrue(ex.shouldPreserveString("app.Target"),
            "the constant string must be preserved (not encrypted)");
    }
}
