package org.javacs;

import com.sun.source.tree.*;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.SymbolInformation;

import javax.lang.model.element.*;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import java.lang.reflect.Constructor;
import java.util.*;
import java.util.function.Function;
import java.util.logging.Logger;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public class Completions implements Function<TreePath, Stream<CompletionItem>> {

    public static Stream<CompletionItem> at(FocusedResult compiled) {
        return compiled.cursor
                .map(new Completions(compiled.task, compiled.classPath, compiled.sourcePath))
                .orElseGet(Stream::empty);
    }

    private final JavacTask task;
    private final ClassPathIndex classPath;
    private final SymbolIndex sourcePath;
    private final Trees trees;
    private final Elements elements;
    private final Name thisName, superName;

    private Completions(JavacTask task, ClassPathIndex classPath, SymbolIndex sourcePath) {
        this.task = task;
        this.trees = Trees.instance(task);
        this.elements = task.getElements();
        this.thisName = task.getElements().getName("this");
        this.superName = task.getElements().getName("super");
        this.classPath = classPath;
        this.sourcePath = sourcePath;
    }

    @Override
    public Stream<CompletionItem> apply(TreePath path) {
        Tree leaf = path.getLeaf();
        Scope scope = trees.getScope(path);

        if (leaf instanceof MemberSelectTree) {
            MemberSelectTree select = (MemberSelectTree) leaf;
            TreePath expressionPath = new TreePath(path.getParentPath(), select.getExpression());

            return completeMembers(expressionPath, scope);
        }
        else if (leaf instanceof MemberReferenceTree) {
            MemberReferenceTree select = (MemberReferenceTree) leaf;
            TreePath expressionPath = new TreePath(path.getParentPath(), select.getQualifierExpression());

            return completeMembers(expressionPath, scope);
        }
        else if (leaf instanceof NewClassTree) {
            return constructors(scope);
        }
        else if (leaf instanceof IdentifierTree) {
            return allSymbols(scope);
        }
        else return Stream.empty();
    }

    /**
     * Suggest all accessible members of expression
     */
    private Stream<CompletionItem> completeMembers(TreePath expression, Scope from) {
        Element element = trees.getElement(expression);

        if (element instanceof PackageElement) {
            PackageElement packageElement = (PackageElement) element;

            return completeImport(packageElement.getQualifiedName().toString(), from);
        }

        TypeMirror type = trees.getTypeMirror(expression);

        if (element == null || type == null)
            return Stream.empty();

        boolean isStatic = isTypeSymbol(element);
        List<? extends Element> all = members(type);

        Stream<CompletionItem> filter = all.stream()
                .filter(e -> isAccessible(e, from))
                .filter(e -> e.getModifiers().contains(Modifier.STATIC) == isStatic)
                .flatMap(this::completionItem);

        if (isStatic) {
            filter = Stream.concat(
                    Stream.of(namedProperty("class")),
                    filter
            );

            if (thisScopes(from).contains(element)) {
                filter = Stream.concat(
                        Stream.of(namedProperty("this"), namedProperty("super")),
                        filter
                );
            }
        }

        return filter;
    }

    private Stream<CompletionItem> completeImport(String parentPackage, Scope from) {
        String fromPackage = packageOf(from);
        Stream<String> sourcePathNames = sourcePath.allSymbols(ElementKind.CLASS)
                .flatMap(this::topLevelClassElement)
                .filter(el -> trees.isAccessible(from, el))
                .map(el -> el.getQualifiedName().toString());
        Stream<String> classPathNames = classPath.topLevelClasses(fromPackage)
                .map(info -> info.getName());

        return Stream.concat(sourcePathNames, classPathNames)
                .filter(name -> !isAlreadyImported(name))
                .filter(name -> name.startsWith(parentPackage))
                .map(name -> completeImport(name, parentPackage));
    }

    private String packageOf(Scope from) {
        TypeElement enclosingClass = from.getEnclosingClass();

        if (enclosingClass == null)
            return "";

        PackageElement enclosingPackage = elements.getPackageOf(enclosingClass);

        if (enclosingPackage == null)
            return "";

        return enclosingPackage.getQualifiedName().toString();
    }

    private Stream<TypeElement> topLevelClassElement(SymbolInformation info) {
        String qualifiedName = info.getContainerName().isEmpty() ? info.getName() : info.getContainerName() + "." + info.getName();
        TypeElement candidate = elements.getTypeElement(qualifiedName);

        if (candidate == null || candidate.getKind() != ElementKind.CLASS)
            return Stream.empty();

        Element parent = candidate.getEnclosingElement();

        if (parent == null || parent.getKind() == ElementKind.PACKAGE)
            return Stream.of(candidate);

        return Stream.empty();
    }

    private boolean isAlreadyImported(String fullyQualifiedName) {
        return false; // TODO
    }

    private CompletionItem completeImport(String qualifiedName, String parentPackage) {
        assert qualifiedName.startsWith(parentPackage);

        CompletionItem item = new CompletionItem();

        item.setKind(CompletionItemKind.Class);
        item.setLabel(qualifiedName);
        item.setInsertText(importInsertText(qualifiedName, parentPackage));

        return item;
    }

    private String importInsertText(String qualifiedName, String parentPackage) {
        StringJoiner insertText = new StringJoiner(".");
        String[] parts = qualifiedName.substring(parentPackage.length()).split("\\.");

        for (String each : parts) {
            if (!each.isEmpty())
                insertText.add(each);
        }

        return insertText.toString();
    }

    /**
     * All members of element, if it is TypeElement
     */
    private List<? extends Element> members(TypeMirror expressionType) {
        if (expressionType == null)
            return Collections.emptyList();

        return typeElement(expressionType)
                .map(elements::getAllMembers)
                .orElseGet(Collections::emptyList);
    }

    /**
     * Suggest a simple completion 'name'
     */
    private static CompletionItem namedProperty(String name) {
        CompletionItem item = new CompletionItem();

        item.setKind(CompletionItemKind.Property);
        item.setLabel(name);

        return item;
    }

    private boolean isTypeSymbol(Element element) {
        if (element == null)
            return false;

        switch (element.getKind()) {
            case CLASS:
            case INTERFACE:
            case ENUM:
                return true;
            default:
                return false;
        }
    }

    private Optional<TypeElement> typeElement(TypeMirror type) {
        if (type instanceof DeclaredType) {
            DeclaredType declared = (DeclaredType) type;
            Element element = declared.asElement();

            if (element instanceof TypeElement)
                return Optional.of((TypeElement) element);
        }

        return Optional.empty();
    }

    private Stream<CompletionItem> constructors(Scope scope) {
        Stream<CompletionItem> sourcePathItems = sourcePath.allSymbols(ElementKind.CLASS)
                .flatMap(this::topLevelClassElement)
                .flatMap(this::explodeConstructors)
                .map(this::completeJavacConstructor);
        Stream<CompletionItem> classPathItems = classPath.topLevelConstructors(packageOf(scope))
                .map(this::completeReflectedConstructor);

        return Stream.concat(sourcePathItems, classPathItems);
    }

    private CompletionItem completeReflectedConstructor(Constructor<?> method) {
        String name = method.getDeclaringClass().getSimpleName();
        Optional<String> docString = Optional.empty(); // TODO doc path
        boolean hasTypeParameters = method.getTypeParameters().length > 0;
        String methodSignature = Hovers.reflectedMethodSignature(method);

        return completeConstructor(name, hasTypeParameters, methodSignature, docString);
    }

    private CompletionItem completeJavacConstructor(ExecutableElement method) {
        String name = Hovers.constructorName(method);
        Optional<String> docString = docstring(method);
        boolean hasTypeParameters = !method.getTypeParameters().isEmpty();
        String methodSignature = Hovers.methodSignature(method);

        return completeConstructor(name, hasTypeParameters, methodSignature, docString);
    }

    private CompletionItem completeConstructor(String name, boolean hasTypeParameters, String methodSignature, Optional<String> docString) {
        CompletionItem item = new CompletionItem();
        String insertText = name;

        if (hasTypeParameters)
            insertText += "<>";

        item.setKind(CompletionItemKind.Constructor);
        item.setLabel(methodSignature);
        docString.ifPresent(item::setDocumentation);
        item.setInsertText(insertText);
        item.setSortText(name);
        item.setFilterText(name);
        // TODO edit imports if necessary

        return item;
    }

    private Stream<ExecutableElement> explodeConstructors(Element element) {
        switch (element.getKind()) {
            case CLASS:
                return members(element.asType()).stream()
                        .filter(e -> e.getKind() == ElementKind.CONSTRUCTOR)
                        .map(e -> (ExecutableElement) e);
            case CONSTRUCTOR:
                return Stream.of((ExecutableElement) element);
            default:
                return Stream.empty();
        }
    }

    /**
     * Suggest all completions that are visible from scope
     */
    private Stream<CompletionItem> allSymbols(Scope scope) {
        Stream<CompletionItem> sourcePathItems = allSourcePathSymbols(scope)
                .filter(e -> isAccessible(e, scope))
                .flatMap(this::completionItem);
        Stream<CompletionItem> classPathItems = classPath.topLevelClasses(packageOf(scope))
                .map(this::completeTopLevelClassSymbol);

        return Stream.concat(sourcePathItems, classPathItems);
    }

    private CompletionItem completeTopLevelClassSymbol(Class<?> c) {
        CompletionItem item = new CompletionItem();

        item.setKind(CompletionItemKind.Class);
        item.setLabel(c.getSimpleName());
        item.setInsertText(c.getSimpleName());
        // TODO edit imports if necessary

        return item;
    }

    private Stream<? extends Element> allSourcePathSymbols(Scope scope) {
        Collection<TypeElement> thisScopes = thisScopes(scope);
        Collection<TypeElement> classScopes = classScopes(scope);
        List<Scope> methodScopes = methodScopes(scope);
        Stream<? extends Element> elements = Stream.empty();

        if (!isStaticMethod(scope))
            elements = Stream.concat(elements, thisAndSuper(scope));

        elements = Stream.concat(elements, methodScopes.stream().flatMap(this::locals));
        elements = Stream.concat(elements, thisScopes.stream().flatMap(this::instanceMembers));
        elements = Stream.concat(elements, classScopes.stream().flatMap(this::staticMembers));
        elements = Stream.concat(elements, sourcePath.allSymbols(ElementKind.CLASS).flatMap(this::topLevelClassElement));

        return elements;
    }

    private Collection<TypeElement> thisScopes(Scope scope) {
        Map<Name, TypeElement> acc = new LinkedHashMap<>();

        while (scope != null && scope.getEnclosingClass() != null) {
            TypeElement each = scope.getEnclosingClass();
            boolean staticMethod = isStaticMethod(scope);
            boolean staticClass = each.getModifiers().contains(Modifier.STATIC);
            boolean anonymousClass = isAnonymousClass(each);

            // If this scope is a static method, terminate
            if (staticMethod)
                break;
            // If the user has indicated this is a static class, it's the last scope in the chain
            else if (staticClass && !anonymousClass) {
                acc.put(each.getQualifiedName(), each);

                break;
            }
            // If this is an inner class, add it to the chain and keep going
            else {
                acc.put(each.getQualifiedName(), each);

                scope = scope.getEnclosingScope();
            }
        }

        return acc.values();
    }

    private List<Scope> methodScopes(Scope scope) {
        List<Scope> acc = new ArrayList<>();

        while (scope != null && scope.getEnclosingClass() != null) {
            if (scope.getEnclosingMethod() != null)
                acc.add(scope);

            scope = scope.getEnclosingScope();
        }

        return acc;
    }

    private Collection<TypeElement> classScopes(Scope scope) {
        Map<Name, TypeElement> acc = new LinkedHashMap<>();

        while (scope != null && scope.getEnclosingClass() != null) {
            TypeElement each = scope.getEnclosingClass();

            acc.putIfAbsent(each.getQualifiedName(), each);

            scope = scope.getEnclosingScope();
        }

        return acc.values();
    }

    private Stream<? extends Element> instanceMembers(TypeElement enclosingClass) {
        return elements.getAllMembers(enclosingClass).stream()
                .filter(each -> !each.getModifiers().contains(Modifier.STATIC));
    }

    private Stream<? extends Element> staticMembers(TypeElement enclosingClass) {
        return elements.getAllMembers(enclosingClass).stream()
                .filter(each -> each.getModifiers().contains(Modifier.STATIC));
    }

    private Stream<? extends Element> locals(Scope scope) {
        return StreamSupport.stream(scope.getLocalElements().spliterator(), false)
                .filter(e -> !isThisOrSuper(e));
    }

    private Stream<? extends Element> thisAndSuper(Scope scope) {
        return StreamSupport.stream(scope.getLocalElements().spliterator(), false)
                .filter(e -> isThisOrSuper(e));
    }

    private boolean isStaticMethod(Scope scope) {
        return scope.getEnclosingMethod() != null && scope.getEnclosingMethod().getModifiers().contains(Modifier.STATIC);
    }

    private boolean isAnonymousClass(Element candidate) {
        return candidate != null && candidate instanceof TypeElement && ((TypeElement) candidate).getNestingKind() == NestingKind.ANONYMOUS;
    }

    private boolean isThisOrSuper(Element each) {
        Name name = each.getSimpleName();

        return name.equals(thisName) || name.equals(superName);
    }

    private Stream<CompletionItem> completionItem(Element e) {
        String name = e.getSimpleName().toString();

        switch (e.getKind()) {
            case PACKAGE:
                return Stream.empty();
            case ENUM:
            case CLASS: {
                CompletionItem item = new CompletionItem();

                item.setKind(CompletionItemKind.Class);
                item.setLabel(name);
                item.setInsertText(name);
                // TODO edit imports if necessary

                return Stream.of(item);
            }
            case ANNOTATION_TYPE:
            case INTERFACE:
            case TYPE_PARAMETER: {
                CompletionItem item = new CompletionItem();

                item.setKind(CompletionItemKind.Reference);
                item.setLabel(name);

                return Stream.of(item);
            }
            case ENUM_CONSTANT: {
                CompletionItem item = new CompletionItem();

                item.setKind(CompletionItemKind.Enum);
                item.setLabel(name);
                item.setDetail(e.getEnclosingElement().getSimpleName().toString());

                return Stream.of(item);
            }
            case FIELD: {
                CompletionItem item = new CompletionItem();

                item.setKind(CompletionItemKind.Property);
                item.setLabel(name);
                item.setDetail(ShortTypePrinter.print(e.asType()));
                docstring(e).ifPresent(item::setDocumentation);

                return Stream.of(item);
            }
            case PARAMETER:
            case LOCAL_VARIABLE:
            case EXCEPTION_PARAMETER: {
                CompletionItem item = new CompletionItem();

                item.setKind(CompletionItemKind.Variable);
                item.setLabel(name);

                return Stream.of(item);
            }
            case METHOD: {
                ExecutableElement method = (ExecutableElement) e;
                CompletionItem item = new CompletionItem();

                item.setKind(CompletionItemKind.Method);
                item.setLabel(Hovers.methodSignature(method));
                item.setDetail(ShortTypePrinter.print(method.getReturnType()));
                docstring(e).ifPresent(item::setDocumentation);
                item.setInsertText(name); // TODO
                item.setSortText(name);
                item.setFilterText(name);

                return Stream.of(item);
            }
            case CONSTRUCTOR: {
                // Constructors are completed differently
                return Stream.empty();
            }
            case STATIC_INIT:
            case INSTANCE_INIT:
            case OTHER:
            case RESOURCE_VARIABLE:
            default:
                // Nothing user-enterable
                // Nothing user-enterable
                return Stream.empty();
        }
    }

    private boolean isAccessible(Element e, Scope scope) {
        if (e == null)
            return false;

        TypeMirror enclosing = e.getEnclosingElement().asType();

        if (enclosing instanceof DeclaredType)
            return trees.isAccessible(scope, e, (DeclaredType) enclosing);
        else if (e instanceof TypeElement)
            return trees.isAccessible(scope, (TypeElement) e);
        else
            return true;
    }

    private Optional<String> docstring(Element e) {
        return Optional.ofNullable(elements.getDocComment(e));
    }

    private static final Logger LOG = Logger.getLogger("main");
}