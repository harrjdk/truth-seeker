package dev.truthseeker.archive;

import org.objectweb.asm.Type;

import java.util.ArrayList;
import java.util.List;

/** Helpers for turning JVM internal names and descriptors into readable Java. */
public final class Names {
    private Names() {}

    public static String dotted(String internalName) {
        return internalName == null ? null : internalName.replace('/', '.');
    }

    /** {@code com/acme/Outer$Inner} → {@code Inner}; {@code com/acme/Foo} → {@code Foo}. */
    public static String simple(String internalName) {
        if (internalName == null) return null;
        String s = internalName.substring(internalName.lastIndexOf('/') + 1);
        int d = s.lastIndexOf('$');
        if (d >= 0 && d < s.length() - 1 && !Character.isDigit(s.charAt(d + 1))) s = s.substring(d + 1);
        return s;
    }

    public static String packageOf(String internalName) {
        int i = internalName.lastIndexOf('/');
        return i < 0 ? "" : internalName.substring(0, i).replace('/', '.');
    }

    /** Java source-ish type name for a descriptor, using simple names: {@code Ljava/util/List;} → {@code List}. */
    public static String typeName(String descriptor) {
        return typeName(Type.getType(descriptor));
    }

    public static String typeName(Type t) {
        return switch (t.getSort()) {
            case Type.ARRAY -> typeName(t.getElementType()) + "[]".repeat(t.getDimensions());
            case Type.OBJECT -> simple(t.getInternalName());
            default -> t.getClassName();
        };
    }

    /** Fully qualified source name for a descriptor (used for imports). */
    public static String qualifiedTypeName(Type t) {
        return switch (t.getSort()) {
            case Type.ARRAY -> qualifiedTypeName(t.getElementType()) + "[]".repeat(t.getDimensions());
            case Type.OBJECT -> t.getClassName().replace('$', '.');
            default -> t.getClassName();
        };
    }

    public static List<String> paramSimpleNames(String methodDesc) {
        List<String> out = new ArrayList<>();
        for (Type t : Type.getArgumentTypes(methodDesc)) out.add(typeName(t));
        return out;
    }

    public static String methodSignature(String name, String desc) {
        Type ret = Type.getReturnType(desc);
        return typeName(ret) + " " + name + "(" + String.join(", ", paramSimpleNames(desc)) + ")";
    }

    public static String javaVersion(int major) {
        if (major >= 53) return "Java " + (major - 44);
        return switch (major) {
            case 52 -> "Java 8";
            case 51 -> "Java 7";
            case 50 -> "Java 6";
            case 49 -> "Java 5";
            case 48 -> "Java 1.4";
            case 47 -> "Java 1.3";
            case 46 -> "Java 1.2";
            default -> "Java 1.0/1.1 (major " + major + ")";
        };
    }
}
