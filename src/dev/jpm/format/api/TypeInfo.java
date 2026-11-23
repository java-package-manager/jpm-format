package dev.jpm.format.api;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;

import javax.lang.model.element.Element;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

/**
 * An attributed view of a file: javac has resolved names and types against the project's classpath.
 * Positions in {@link #tree()} are the same as in {@link Context#source()}.
 */
public record TypeInfo(CompilationUnitTree tree, Trees trees, Types types, Elements elements) {
    /**
     * The type of {@code node} (a node of {@link #tree()}), or null if javac could not resolve it.
     */
    public TypeMirror typeOf(Tree node) {
        TreePath path = TreePath.getPath(tree, node);
        if (path == null) return null;
        TypeMirror t = trees.getTypeMirror(path);
        return t == null || t.getKind() == TypeKind.ERROR ? null : t;
    }

    /**
     * The symbol {@code node} declares or refers to, or null.
     */
    public Element elementOf(Tree node) {
        TreePath path = TreePath.getPath(tree, node);
        return path == null ? null : trees.getElement(path);
    }

    /**
     * True if {@code t} is exactly the class {@code qualifiedName}.
     */
    public boolean is(TypeMirror t, String qualifiedName) {
        if (t == null || t.getKind() != TypeKind.DECLARED) return false;
        var e = elements.getTypeElement(qualifiedName);
        return e != null && types.isSameType(types.erasure(t), types.erasure(e.asType()));
    }
}
