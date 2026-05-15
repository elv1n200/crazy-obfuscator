package dev.crazy.obf.transform;

import dev.crazy.obf.model.ObfContext;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Strips compile-time-only metadata that aids decompilation but is not needed
 * to run the code:
 *   - SourceFile / SourceDebugExtension
 *   - LineNumberTable (set MethodNode.tryCatchBlocks alone; remove labels with line info)
 *   - LocalVariableTable / LocalVariableTypeTable
 *   - method parameter names
 *
 * We keep StackMapTable (required by the verifier on class file v51+).
 */
public final class MetadataStripTransformer implements Transformer {

    @Override public String name() { return "strip"; }

    @Override
    public void apply(ObfContext ctx) {
        for (ClassNode cn : ctx.contents().classes().values()) {
            if (ctx.exclusions().isClassNoTouch(cn.name)) continue;
            cn.sourceFile = null;
            cn.sourceDebug = null;

            if (cn.methods != null) {
                for (MethodNode m : cn.methods) {
                    m.localVariables = null;
                    m.parameters = null;
                    // Remove LineNumberNodes
                    if (m.instructions != null) {
                        var arr = m.instructions.toArray();
                        for (var ins : arr) {
                            if (ins instanceof org.objectweb.asm.tree.LineNumberNode) {
                                m.instructions.remove(ins);
                            }
                        }
                    }
                }
            }
        }
    }
}
